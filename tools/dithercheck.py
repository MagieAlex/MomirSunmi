"""
Does the device ditherer still agree with the builder's?

`momirdeck.dither_art` reduces artwork to one bit on a PC; `Dither.kt` repeats
it on the device for anything an on-device resync pulls down. Both files claim
in their comments that they are the same pipeline, and for a while they were
not: the device skipped autocontrast, resampled bilinear where Pillow uses
Lanczos, and pivoted its contrast lift around a fixed mid-grey instead of the
image mean. A corpus built on a PC and one topped up by a resync printed at
visibly different densities - by a mean of 92 levels out of 255 on dark art.

This re-implements the Kotlin side in Python, line for line, and diffs it
against Pillow's real pipeline over a few synthetic art crops. Run it after
touching either implementation:

    python tools/dithercheck.py

It exits non-zero if they have drifted apart by more than JPEG rounding.
"""
import io
import math
import sys

from PIL import Image, ImageEnhance, ImageOps

W = 384
MAX_H = 300
GAMMA = 0.85
CONTRAST = 1.25
SUPPORT = 3.0


# ---- Pillow pipeline: momirdeck.dither_art, minus the packing ---------------

def pillow_plane(data: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(data)).convert("L")
    height = max(1, round(W * img.height / img.width))
    img = img.resize((W, height), Image.LANCZOS)
    if height > MAX_H:
        top = (height - MAX_H) // 2
        img = img.crop((0, top, W, top + MAX_H))
    img = ImageOps.autocontrast(img, cutoff=1)
    lut = [min(255, int(((i / 255.0) ** GAMMA) * 255 + 0.5)) for i in range(256)]
    img = img.point(lut)
    img = ImageEnhance.Contrast(img).enhance(CONTRAST)
    return img


# ---- the Kotlin port -------------------------------------------------------

def kernel(x):
    if x == 0.0:
        return 1.0
    if x <= -SUPPORT or x >= SUPPORT:
        return 0.0
    px = math.pi * x
    return SUPPORT * math.sin(px) * math.sin(px / SUPPORT) / (px * px)


def resample_axis(src, src_w, src_h, target, horizontal):
    in_size = src_w if horizontal else src_h
    out_w = target if horizontal else src_w
    out_h = src_h if horizontal else target
    out = [0.0] * (out_w * out_h)

    scale = in_size / target
    filter_scale = max(1.0, scale)
    support = SUPPORT * filter_scale

    for index in range(target):
        center = (index + 0.5) * scale
        first = max(0, math.floor(center - support))
        last = min(in_size - 1, math.ceil(center + support))
        weights = [kernel((first + tap + 0.5 - center) / filter_scale)
                   for tap in range(last - first + 1)]
        total = sum(weights)
        if total == 0:
            continue
        weights = [w / total for w in weights]
        if horizontal:
            for y in range(out_h):
                row = y * src_w
                out[y * out_w + index] = sum(
                    w * src[row + first + tap] for tap, w in enumerate(weights))
        else:
            row = index * out_w
            for x in range(out_w):
                out[row + x] = sum(
                    w * src[(first + tap) * out_w + x] for tap, w in enumerate(weights))
    return out


def kotlin_plane(data: bytes):
    rgb = Image.open(io.BytesIO(data)).convert("RGB")
    sw, sh = rgb.size
    # tobytes() on an RGB image is the three planes interleaved, which is what
    # Android's getPixels hands the Kotlin side, one integer per pixel.
    raw = rgb.tobytes()
    px = [(raw[i], raw[i + 1], raw[i + 2]) for i in range(0, len(raw), 3)]
    # The device's luminance: the integer weights Android's own pixels get.
    luma = [float((r * 19595 + g * 38470 + b * 7471 + 0x8000) >> 16) for (r, g, b) in px]

    target_h = max(1, round(W * sh / sw))
    horizontal = resample_axis(luma, sw, sh, W, True)
    # Pillow resamples 8-bit images in 8 bits: the horizontal pass is rounded
    # and clipped before the vertical one reads it.
    horizontal = [float(min(255, max(0, round(v)))) for v in horizontal]
    vertical = resample_axis(horizontal, W, sh, target_h, False)
    plane = [min(255, max(0, round(v))) for v in vertical]

    height = target_h
    if height > MAX_H:
        top = (height - MAX_H) // 2
        plane = plane[top * W:(top + MAX_H) * W]
        height = MAX_H

    # autocontrast(cutoff=1)
    hist = [0] * 256
    for v in plane:
        hist[v] += 1
    cut = len(plane) * 1 // 100
    for rng in (range(256), range(255, -1, -1)):
        remaining = cut
        for b in rng:
            if remaining <= 0:
                break
            if remaining > hist[b]:
                remaining -= hist[b]
                hist[b] = 0
            else:
                hist[b] -= remaining
                remaining = 0
    lo = next((i for i, v in enumerate(hist) if v > 0), -1)
    hi = next((i for i in range(255, -1, -1) if hist[i] > 0), -1)
    if lo >= 0 and hi > lo:
        scale = 255.0 / (hi - lo)
        lut = [min(255, max(0, int(i * scale - lo * scale))) for i in range(256)]
        plane = [lut[v] for v in plane]

    lut = [min(255, int(((i / 255.0) ** GAMMA) * 255 + 0.5)) for i in range(256)]
    plane = [lut[v] for v in plane]

    mean = int(sum(plane) / len(plane) + 0.5)
    plane = [min(255, max(0, int(mean + CONTRAST * (v - mean)))) for v in plane]
    return plane, height


def bilinear_plane(data: bytes):
    """What the device used to do, kept so the numbers have a scale."""
    img = Image.open(io.BytesIO(data)).convert("L")
    height = max(1, round(W * img.height / img.width))
    img = img.resize((W, height), Image.BILINEAR)
    if height > MAX_H:
        top = (height - MAX_H) // 2
        img = img.crop((0, top, W, top + MAX_H))
    lut = [min(255, max(0, int((((i / 255.0) ** GAMMA) * 255 - 128) * CONTRAST + 128)))
           for i in range(256)]
    return list(img.point(lut).tobytes())


def sample(kind: str) -> bytes:
    """A stand-in for a Scryfall art crop: 626 x 457, the usual shape."""
    w, h = 626, 457
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            if kind == "detail":
                v = int(120 + 90 * math.sin(x / 3.0) * math.cos(y / 5.0))
            elif kind == "flat":
                v = int(110 + 18 * math.sin(x / 40.0) + 12 * math.cos(y / 30.0))
            elif kind == "colour":
                # The one that tells the two luminance weightings apart.
                px[x, y] = (
                    max(0, min(255, int(160 + 80 * math.sin(x / 7.0)))),
                    max(0, min(255, int(90 + 70 * math.cos(y / 9.0)))),
                    max(0, min(255, int(200 - 60 * math.sin((x + y) / 11.0)))),
                )
                continue
            else:  # dark
                v = int(40 + 30 * math.sin(x / 12.0) + 20 * (y / h))
            v = max(0, min(255, v))
            px[x, y] = (v, v, v)
    out = io.BytesIO()
    img.save(out, "JPEG", quality=92)
    return out.getvalue()


# Colour art goes through JPEG chroma subsampling on the way in, and the two
# implementations round its luminance in different places. Two levels out of
# 255 is that; anything more is a real divergence.
TOLERANCE = 2


def main():
    worst = 0
    for kind in ("detail", "flat", "dark", "colour"):
        data = sample(kind)
        want = list(pillow_plane(data).tobytes())
        got, height = kotlin_plane(data)
        old = bilinear_plane(data)

        assert len(want) == len(got) == len(old), (len(want), len(got), len(old))
        diff = [abs(a - b) for a, b in zip(want, got)]
        old_diff = [abs(a - b) for a, b in zip(want, old)]
        worst = max(worst, max(diff))
        print(f"{kind:7} {W}x{height}"
              f"  Dither.kt: max {max(diff):3d}  mean {sum(diff)/len(diff):5.2f}"
              f"  |  before the fix: max {max(old_diff):3d}"
              f"  mean {sum(old_diff)/len(old_diff):6.2f}")

    if worst > TOLERANCE:
        print(f"\nDRIFTED: {worst} levels apart, tolerance is {TOLERANCE}.")
        return 1
    print(f"\nIn step, within {TOLERANCE} levels.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
