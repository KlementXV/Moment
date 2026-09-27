#!/usr/bin/env python3
"""Offline evaluation on labelled pairs of sanitized JPEGs; never promotes a policy automatically.
CSV columns: rear,front,label (0=SFW pair, 1=at least one NSFW image).
Paths are relative to the CSV. Use separate calibration and held-out evaluation files.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps
from preprocess import CONTRACT, prepare


def metrics(scores, labels, threshold):
    flagged = scores >= threshold
    positive = labels == 1
    tp = int(np.sum(flagged & positive))
    fp = int(np.sum(flagged & ~positive))
    fn = int(np.sum(~flagged & positive))
    tn = int(np.sum(~flagged & ~positive))
    return {"threshold": threshold, "tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "recall": tp / (tp + fn), "falsePositiveRate": fp / (fp + tn)}


def evaluate(csv_path, session, crop):
    scores, labels = [], []
    with csv_path.open(newline="") as file:
        rows = list(csv.DictReader(file))
    if not rows:
        raise ValueError("Empty dataset")
    for row in rows:
        label = int(row["label"])
        if label not in (0, 1):
            raise ValueError("Labels must be 0 or 1")
        pair = []
        for camera in ("rear", "front"):
            with Image.open(csv_path.parent / row[camera]) as source:
                if source.format != "JPEG" or max(source.size) > 1280 or source.getexif():
                    raise ValueError("Use sanitized JPEGs <=1280 with no EXIF, as produced by PhotoSanitizer")
                image = source.convert("RGB")
                if crop:
                    image = ImageOps.fit(image, (384, 384), method=Image.Resampling.BICUBIC, centering=(.5, .5))
                    value = np.asarray(image, dtype=np.uint8)[None]
                else:
                    value = prepare(np.asarray(image))
            pair.append(float(session.run(["nsfw"], {"image": value})[0][0]))
        scores.append(max(pair))
        labels.append(label)
    scores, labels = np.array(scores), np.array(labels)
    if set(labels) != {0, 1} or not np.all(np.isfinite(scores)):
        raise ValueError("Both SFW and NSFW pairs and finite scores are required")
    return scores, labels


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--calibration", type=Path, required=True)
    parser.add_argument("--evaluation", type=Path, required=True, help="Independent held-out CSV")
    parser.add_argument("--model", type=Path, default=Path("app/app/src/main/assets/moderation/marqo-nsfw.onnx"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--minimum-review-recall", type=float, default=.98)
    parser.add_argument("--maximum-block-fpr", type=float, default=.01)
    args = parser.parse_args()
    if args.calibration.resolve() == args.evaluation.resolve():
        parser.error("Calibration and evaluation must use separate datasets")
    if not 0 < args.minimum_review_recall <= 1 or not 0 <= args.maximum_block_fpr < 1:
        parser.error("Invalid recall/FPR targets")
    def image_hashes(path):
        with path.open(newline="") as file:
            return {hashlib.sha256((path.parent / row[camera]).read_bytes()).hexdigest()
                    for row in csv.DictReader(file) for camera in ("rear", "front")}
    if image_hashes(args.calibration) & image_hashes(args.evaluation):
        parser.error("Image overlap between calibration and held-out evaluation")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(args.model), options, providers=["CPUExecutionProvider"])
    report = {"modelSha256": hashlib.sha256(args.model.read_bytes()).hexdigest(), "calibrated": False,
              "note": "Candidates only. Human review and representative held-out/device evaluation required.",
              "preprocessing": CONTRACT, "comparisons": {}}
    for name, crop in [(CONTRACT, False), ("center-crop-bicubic-reference", True)]:
        train, labels = evaluate(args.calibration, session, crop)
        test, test_labels = evaluate(args.evaluation, session, crop)
        grid = [metrics(train, labels, float(t)) for t in np.linspace(.001, 1, 1000)]
        review_candidates = [m for m in grid if m["recall"] >= args.minimum_review_recall]
        review = max(review_candidates, key=lambda m: m["threshold"]) if review_candidates else None
        block_candidates = [m for m in grid if m["falsePositiveRate"] <= args.maximum_block_fpr
                            and (review is None or m["threshold"] > review["threshold"])]
        block = min(block_candidates, key=lambda m: m["threshold"]) if block_candidates else None
        report["comparisons"][name] = {
            "calibrationPairs": len(train), "evaluationPairs": len(test),
            "calibrationPositivePairs": int(sum(labels)), "evaluationPositivePairs": int(sum(test_labels)),
            "reviewCandidate": review, "blockCandidate": block,
            "heldOutReview": metrics(test, test_labels, review["threshold"]) if review else None,
            "heldOutBlock": metrics(test, test_labels, block["threshold"]) if block else None,
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Wrote candidate metrics to {args.output}; production policy unchanged.")


if __name__ == "__main__":
    main()
