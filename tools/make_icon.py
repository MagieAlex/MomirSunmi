#!/usr/bin/env python3
"""
Generates the launcher icon into app/src/main/res/mipmap-*.

A ring with an X in it: Momir Vig's ability is "{X}, Discard a card", and the
ring is the same shape the app prints as the mana value badge on every slip.

Committed as PNGs rather than a vector because the target device runs Android
7.1, where adaptive icons do not exist and vector launcher icons are a lottery
depending on the launcher.

    python tools/make_icon.py
"""

import os
from PIL import Image, ImageDraw, ImageFont

BACKGROUND = (14, 17, 22)
RING = (61, 123, 255)
GLYPH = (242, 245, 249)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

SUPERSAMPLE = 8


def find_font(size: int):
    for candidate in (
        r"C:\Windows\Fonts\segoeuib.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ):
        if os.path.exists(candidate):
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def render(size: int) -> Image.Image:
    big = size * SUPERSAMPLE
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    radius = int(big * 0.22)
    draw.rounded_rectangle([0, 0, big - 1, big - 1], radius=radius, fill=BACKGROUND)

    inset = big * 0.20
    width = max(1, int(big * 0.075))
    draw.ellipse([inset, inset, big - inset, big - inset], outline=RING, width=width)

    font = find_font(int(big * 0.36))
    bbox = draw.textbbox((0, 0), "X", font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    draw.text(((big - tw) / 2 - bbox[0], (big - th) / 2 - bbox[1]), "X", font=font, fill=GLYPH)

    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    res = os.path.join(here, "..", "app", "src", "main", "res")

    for density, size in DENSITIES.items():
        folder = os.path.join(res, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "ic_launcher.png")
        render(size).save(path)
        print(f"{density:>8}  {size:>3}px  {path}")


if __name__ == "__main__":
    main()
