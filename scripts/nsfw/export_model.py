#!/usr/bin/env python3
"""Pinned Marqo export, ONNX checker, PyTorch/ORT parity, golden RGB fixtures."""
import argparse
import hashlib
import importlib.metadata
import json
import tempfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import timm
import torch
from huggingface_hub import hf_hub_download
from PIL import Image, ImageOps
from safetensors.torch import load_file
from preprocess import CONTRACT, prepare

REPO = "Marqo/nsfw-image-detection-384"
REVISION = "0c26ec22111b83f106d72a55f611ec35962bcb65"
ROOT = Path(__file__).resolve().parents[2]


class Wrapped(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, image):
        image = image.permute(0, 3, 1, 2).float() / 127.5 - 1.0
        return self.model(image).softmax(-1)[:, 0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--validation-images", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "app/app/src/main/assets/moderation")
    parser.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "moment-hf-cache")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    config_path = hf_hub_download(REPO, "config.json", revision=REVISION, cache_dir=args.cache)
    config = json.loads(Path(config_path).read_text())
    assert config["label_names"] == ["NSFW", "SFW"]
    assert config["pretrained_cfg"]["mean"] == [0.5] * 3
    assert config["pretrained_cfg"]["std"] == [0.5] * 3
    timm.layers.set_fused_attn(False)
    torch.manual_seed(0)
    torch.set_num_threads(2)
    model = timm.create_model(config["architecture"], pretrained=False, num_classes=2).eval()
    weights = hf_hub_download(REPO, "model.safetensors", revision=REVISION, cache_dir=args.cache)
    model.load_state_dict(load_file(weights), strict=True)
    wrapped = Wrapped(model).eval()
    target = args.output / "marqo-nsfw.onnx"
    dummy = torch.full((1, 384, 384, 3), 128, dtype=torch.uint8)
    with torch.inference_mode():
        torch.onnx.export(wrapped, dummy, str(target), input_names=["image"],
                          output_names=["nsfw"], opset_version=17, dynamo=False)
    graph = onnx.load(target)
    onnx.checker.check_model(graph, full_check=True)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(target), options, providers=["CPUExecutionProvider"])
    images = sorted(p for p in args.validation_images.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if not images:
        raise ValueError("At least one real validation image is required")
    rows = []
    fixtures = ROOT / "app/app/src/androidTest/assets/moderation"
    fixtures.mkdir(parents=True, exist_ok=True)
    for index, image_path in enumerate(images):
        with Image.open(image_path) as source:
            rgb = np.asarray(ImageOps.exif_transpose(source).convert("RGB"))
        for variant, pixels in [("original", rgb), ("portrait", rgb[:, :max(1, rgb.shape[1] // 3)]), ("landscape", rgb[:max(1, rgb.shape[0] // 3)])]:
            value = prepare(pixels)
            with torch.inference_mode():
                expected = wrapped(torch.from_numpy(value)).item()
            actual = float(session.run(["nsfw"], {"image": value})[0][0])
            error = abs(expected - actual)
            if error > 1e-5:
                raise AssertionError(f"ORT/PyTorch mismatch: {error}")
            rows.append({"image": image_path.name, "variant": variant, "pytorch": expected, "ort": actual, "absolute_error": error})
            if index == 0 and variant == "original":
                (fixtures / "input.rgb").write_bytes(value.tobytes())
                (fixtures / "expected.json").write_text(json.dumps({"nsfw": actual}))
                fixture_image = Image.fromarray(rgb)
                fixture_image.thumbnail((1280, 1280))
                fixture_image.save(fixtures / "rear.jpg", quality=88)
                fixture_image.crop((0, 0, max(1, fixture_image.width // 2), fixture_image.height)).save(fixtures / "front.jpg", quality=88)
                jpeg_scores = {}
                for camera in ("rear", "front"):
                    with Image.open(fixtures / f"{camera}.jpg") as decoded:
                        tensor = prepare(np.asarray(decoded.convert("RGB")))
                    jpeg_scores[camera] = float(session.run(["nsfw"], {"image": tensor})[0][0])
                (fixtures / "jpeg-expected.json").write_text(json.dumps(jpeg_scores))
    rgb = np.arange(13 * 7 * 3, dtype=np.uint8).reshape(13, 7, 3)
    (fixtures / "source-7x13.rgb").write_bytes(rgb.tobytes())
    (fixtures / "padded-7x13.rgb").write_bytes(prepare(rgb).tobytes())
    manifest = {
        "model": REPO, "revision": REVISION, "license": "Apache-2.0", "format": "FP32",
        "sha256": hashlib.sha256(target.read_bytes()).hexdigest(), "bytes": target.stat().st_size,
        "input": {"name": "image", "dtype": "uint8", "shape": [1, 384, 384, 3], "color": "RGB"},
        "output": {"name": "nsfw", "shape": [1], "class_index": 0},
        "preprocessing": CONTRACT, "opset": 17,
        "versions": {name: importlib.metadata.version(name) for name in ["torch", "torchvision", "timm", "onnx", "onnxruntime", "numpy", "pillow", "huggingface-hub", "safetensors"]},
        "validation": rows,
    }
    (args.output / "model.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"bytes": manifest["bytes"], "sha256": manifest["sha256"], "max_error": max(row["absolute_error"] for row in rows), "comparisons": len(rows)}))


if __name__ == "__main__":
    main()
