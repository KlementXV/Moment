//! Server-side review is mandatory and has no client-side override.
use crate::{
    error::{Error, Result},
    protocol::{hash, MAX_PHOTO},
};
use anyhow::{ensure, Context};
use axum::http::StatusCode;
use ort::{session::Session, value::TensorRef};
use serde::Deserialize;
use std::{path::Path, sync::Mutex};

const SIZE: usize = 384;
pub trait Reviewer: Send + Sync {
    fn review(&self, rear: &[u8], front: &[u8]) -> Result<()>;
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Policy {
    id: String,
    preprocessing: String,
    review_threshold: f32,
    block_threshold: f32,
    calibrated: bool,
    model_sha256: String,
}
impl Policy {
    fn validate(&self, model: &[u8], allow_uncalibrated: bool) -> anyhow::Result<()> {
        ensure!(
            !self.id.trim().is_empty() && self.id.len() <= 128,
            "invalid moderation policy id"
        );
        ensure!(
            self.preprocessing == "pad-rgb128-bilinear-v1",
            "unsupported preprocessing"
        );
        ensure!(
            self.review_threshold.is_finite()
                && self.block_threshold.is_finite()
                && self.review_threshold > 0.
                && self.review_threshold < self.block_threshold
                && self.block_threshold <= 1.,
            "invalid moderation thresholds"
        );
        ensure!(
            self.calibrated || allow_uncalibrated,
            "moderation policy is not calibrated; production startup denied"
        );
        ensure!(
            self.model_sha256 == hex::encode(hash(model)),
            "moderation model digest mismatch"
        );
        Ok(())
    }
}
pub struct OnnxReviewer {
    session: Mutex<Session>,
    threshold: f32,
}
impl OnnxReviewer {
    pub fn new(model: &Path, policy: &Path, allow_uncalibrated: bool) -> anyhow::Result<Self> {
        let policy: Policy = serde_json::from_slice(&std::fs::read(policy)?)?;
        let bytes = std::fs::read(model).context("read moderation model")?;
        policy.validate(&bytes, allow_uncalibrated)?;
        let mut session = Session::builder()?
            .with_intra_threads(2)?
            .commit_from_memory(&bytes)?;
        infer(&mut session, &vec![128; SIZE * SIZE * 3]).context("warm up moderation model")?;
        Ok(Self {
            session: Mutex::new(session),
            threshold: policy.review_threshold,
        })
    }
}
fn infer(session: &mut Session, rgb: &[u8]) -> anyhow::Result<f32> {
    let tensor = TensorRef::from_array_view(([1, SIZE, SIZE, 3], rgb))?;
    let output = session.run(ort::inputs! { "image" => tensor })?;
    let value = output.get("nsfw").context("missing nsfw model output")?;
    let (shape, values) = value.try_extract_tensor::<f32>()?;
    ensure!(
        shape.as_ref() == [1] && values.len() == 1,
        "invalid model output shape"
    );
    let score = values[0];
    ensure!(
        score.is_finite() && (0.0..=1.0).contains(&score),
        "invalid model output"
    );
    Ok(score)
}
impl Reviewer for OnnxReviewer {
    fn review(&self, rear: &[u8], front: &[u8]) -> Result<()> {
        // Check both containers before decoding either; never infer malformed photos.
        validate_jpeg(rear)?;
        validate_jpeg(front)?;
        let mut session = self.session.lock().map_err(|_| Error::unavailable())?;
        for photo in [rear, front] {
            let decoded = image::load_from_memory_with_format(photo, image::ImageFormat::Jpeg)
                .map_err(|_| invalid_photo())?
                .into_rgb8();
            let rgb = prepare(
                decoded.as_raw(),
                decoded.width() as usize,
                decoded.height() as usize,
            );
            let score = infer(&mut session, &rgb).map_err(|_| Error::unavailable())?;
            if score >= self.threshold {
                return Err(Error(StatusCode::UNPROCESSABLE_ENTITY,
                    "Une photo nécessite une vérification de contenu. Reprenez vos photos sans contenu explicite."));
            }
        }
        Ok(())
    }
}
fn invalid_photo() -> Error {
    Error::bad("Photo JPEG invalide, trop grande ou contenant des métadonnées. Reprenez les photos dans l’application.")
}

/// Parse every marker, including markers between progressive scans and after entropy data.
/// Only a bare JFIF APP0 is permitted; EXIF, ICC, comments and trailing bytes are refused.
fn validate_jpeg(bytes: &[u8]) -> Result<()> {
    if bytes.len() > MAX_PHOTO || !bytes.starts_with(&[0xff, 0xd8]) {
        return Err(invalid_photo());
    }
    let mut pos = 2;
    let mut frame = false;
    let mut scan = false;
    let mut entropy = false;
    let mut jfif = false;
    loop {
        if entropy {
            while pos < bytes.len() && bytes[pos] != 0xff {
                pos += 1;
            }
        }
        if bytes.get(pos) != Some(&0xff) {
            return Err(invalid_photo());
        }
        pos += 1;
        while bytes.get(pos) == Some(&0xff) {
            pos += 1;
        }
        let marker = *bytes.get(pos).ok_or_else(invalid_photo)?;
        pos += 1;
        if entropy && (marker == 0 || (0xd0..=0xd7).contains(&marker)) {
            continue;
        }
        entropy = false;
        if marker == 0xd9 {
            return if frame && scan && pos == bytes.len() {
                Ok(())
            } else {
                Err(invalid_photo())
            };
        }
        if marker == 0 || marker == 0xd8 || (0xd0..=0xd7).contains(&marker) || marker == 1 {
            return Err(invalid_photo());
        }
        let length_bytes = bytes.get(pos..pos + 2).ok_or_else(invalid_photo)?;
        let length = u16::from_be_bytes([length_bytes[0], length_bytes[1]]) as usize;
        if length < 2 {
            return Err(invalid_photo());
        }
        let data = bytes.get(pos + 2..pos + length).ok_or_else(invalid_photo)?;
        pos += length;
        match marker {
            0xe0 => {
                if jfif
                    || scan
                    || data.len() != 14
                    || !data.starts_with(b"JFIF\0")
                    || data[12] != 0
                    || data[13] != 0
                {
                    return Err(invalid_photo());
                }
                jfif = true;
            }
            0xe1..=0xef | 0xfe => return Err(invalid_photo()),
            0xc0..=0xc3 | 0xc5..=0xc7 | 0xc9..=0xcb | 0xcd..=0xcf => {
                if frame || scan || !matches!(marker, 0xc0..=0xc2) || data.len() < 6 || data[0] != 8
                {
                    return Err(invalid_photo());
                }
                let h = u16::from_be_bytes([data[1], data[2]]);
                let w = u16::from_be_bytes([data[3], data[4]]);
                let channels = data[5] as usize;
                if w == 0
                    || h == 0
                    || w > 1280
                    || h > 1280
                    || !matches!(channels, 1 | 3)
                    || data.len() != 6 + 3 * channels
                {
                    return Err(invalid_photo());
                }
                frame = true;
            }
            0xda => {
                if !frame || data.is_empty() || data.len() != 4 + 2 * data[0] as usize {
                    return Err(invalid_photo());
                }
                scan = true;
                entropy = true;
            }
            0xc4 | 0xdb | 0xdd => {} // Huffman, quantization and restart tables.
            _ => return Err(invalid_photo()),
        }
    }
}
fn prepare(rgb: &[u8], width: usize, height: usize) -> Vec<u8> {
    let scale = SIZE as f64 / width.max(height) as f64;
    let dw = ((width as f64 * scale + 0.5).floor() as usize).max(1);
    let dh = ((height as f64 * scale + 0.5).floor() as usize).max(1);
    let left = (SIZE - dw) / 2;
    let top = (SIZE - dh) / 2;
    let mut output = vec![128; SIZE * SIZE * 3];
    for y in 0..dh {
        let sy =
            ((y as f64 + 0.5) * height as f64 / dh as f64 - 0.5).clamp(0., (height - 1) as f64);
        let y0 = sy as usize;
        let y1 = (y0 + 1).min(height - 1);
        let fy = sy - y0 as f64;
        for x in 0..dw {
            let sx =
                ((x as f64 + 0.5) * width as f64 / dw as f64 - 0.5).clamp(0., (width - 1) as f64);
            let x0 = sx as usize;
            let x1 = (x0 + 1).min(width - 1);
            let fx = sx - x0 as f64;
            for c in 0..3 {
                let component = |px, py| rgb[(py * width + px) * 3 + c] as f64;
                let upper = component(x0, y0) * (1. - fx) + component(x1, y0) * fx;
                let lower = component(x0, y1) * (1. - fx) + component(x1, y1) * fx;
                output[((top + y) * SIZE + left + x) * 3 + c] =
                    (upper * (1. - fy) + lower * fy + 0.5).floor() as u8;
            }
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    fn jpeg() -> Vec<u8> {
        let mut out = vec![];
        image::codecs::jpeg::JpegEncoder::new(&mut out)
            .encode(&[100; 12], 2, 2, image::ExtendedColorType::Rgb8)
            .unwrap();
        out
    }
    #[test]
    fn matches_android_resize_fixture() {
        let source =
            include_bytes!("../../app/app/src/androidTest/assets/moderation/source-7x13.rgb");
        let expected =
            include_bytes!("../../app/app/src/androidTest/assets/moderation/padded-7x13.rgb");
        assert_eq!(prepare(source, 7, 13), expected.as_slice());
    }
    #[test]
    fn strict_jpeg_container() {
        let good = jpeg();
        validate_jpeg(&good).unwrap();
        assert!(validate_jpeg(&good[..good.len() - 2]).is_err());
        let mut trailing = good.clone();
        trailing.push(0);
        assert!(validate_jpeg(&trailing).is_err());
        for marker in [0xe1, 0xe2, 0xfe] {
            for offset in [2, good.len() - 2] {
                let mut bad = good.clone();
                bad.splice(offset..offset, [0xff, marker, 0, 3, 42]);
                assert!(validate_jpeg(&bad).is_err());
            }
        }
        let mut huge = good.clone();
        let sof = huge.windows(2).position(|w| w == [0xff, 0xc0]).unwrap();
        huge[sof + 5..sof + 7].copy_from_slice(&65535u16.to_be_bytes());
        assert!(validate_jpeg(&huge).is_err());
        assert!(validate_jpeg(&vec![0; MAX_PHOTO + 1]).is_err());
        let mut thumb = good.clone();
        let app = thumb.windows(2).position(|w| w == [0xff, 0xe0]).unwrap();
        thumb[app + 16] = 1;
        assert!(validate_jpeg(&thumb).is_err());
    }
    #[test]
    fn calibration_and_digest_fail_closed() {
        let mut p = Policy {
            id: "test".into(),
            preprocessing: "pad-rgb128-bilinear-v1".into(),
            review_threshold: 0.5,
            block_threshold: 0.9,
            calibrated: false,
            model_sha256: hex::encode(hash(b"model")),
        };
        assert!(p.validate(b"model", false).is_err());
        assert!(p.validate(b"model", true).is_ok());
        assert!(p.validate(b"other", true).is_err());
        p.review_threshold = f32::NAN;
        assert!(p.validate(b"model", true).is_err());
    }
    #[test]
    #[ignore = "requires ORT_DYLIB_PATH pointing to ONNX Runtime 1.23.2 and the real model"]
    fn real_model_matches_android_golden() {
        ort::init_from(std::env::var("ORT_DYLIB_PATH").expect("ORT_DYLIB_PATH"))
            .commit()
            .unwrap();
        let root =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/app/src/main/assets/moderation");
        let engine = OnnxReviewer::new(
            &root.join("marqo-nsfw.onnx"),
            &root.join("policy.json"),
            true,
        )
        .unwrap();
        let rgb = include_bytes!("../../app/app/src/androidTest/assets/moderation/input.rgb");
        let score = infer(&mut engine.session.lock().unwrap(), rgb).unwrap();
        assert!((score - 0.058171317).abs() < 1e-5);
        // Exercise the actual JPEG -> resize -> two inferences -> policy path too.
        let rear = include_bytes!("../../app/app/src/androidTest/assets/moderation/rear.jpg");
        let front = include_bytes!("../../app/app/src/androidTest/assets/moderation/front.jpg");
        engine.review(rear, front).unwrap();
        let stricter = OnnxReviewer {
            session: engine.session,
            threshold: 0.001,
        };
        assert_eq!(
            stricter.review(rear, front).unwrap_err().0,
            StatusCode::UNPROCESSABLE_ENTITY
        );
    }
}
