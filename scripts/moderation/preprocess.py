"""Reference for pad-rgb128-bilinear-v1; accepts already oriented RGB pixels.
JPEG decoding is platform-specific. Golden .rgb fixtures test resize parity.
"""
import numpy as np

SIZE = 384
CONTRACT = "pad-rgb128-bilinear-v1"


def prepare(rgb):
    rgb = np.asarray(rgb, dtype=np.uint8)
    h, w, channels = rgb.shape
    assert channels == 3 and h > 0 and w > 0
    scale = SIZE / max(w, h)
    dw, dh = max(1, int(w * scale + 0.5)), max(1, int(h * scale + 0.5))
    xs = np.maximum(0, np.minimum(w - 1, (np.arange(dw) + 0.5) * w / dw - 0.5))
    ys = np.maximum(0, np.minimum(h - 1, (np.arange(dh) + 0.5) * h / dh - 0.5))
    x0, y0 = xs.astype(int), ys.astype(int)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    fx, fy = (xs - x0)[None, :, None], (ys - y0)[:, None, None]
    top = rgb[y0[:, None], x0].astype(float) * (1 - fx) + rgb[y0[:, None], x1] * fx
    bottom = rgb[y1[:, None], x0].astype(float) * (1 - fx) + rgb[y1[:, None], x1] * fx
    resized = np.floor(top * (1 - fy) + bottom * fy + 0.5).astype(np.uint8)
    output = np.full((SIZE, SIZE, 3), 128, dtype=np.uint8)
    left, upper = (SIZE - dw) // 2, (SIZE - dh) // 2
    output[upper:upper + dh, left:left + dw] = resized
    return output[None]
