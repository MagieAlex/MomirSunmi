#!/usr/bin/env python3
"""
Generates the launcher icon into app/src/main/res/mipmap-*.

The icon is the print button: a struck seal, brass rim, the five colours of
Magic set into it as gems, and the planeswalker symbol lit from inside the
stone. Same object in the launcher as under your thumb.

Nothing here is drawn twice. The symbol's outline and the five mana colours are
read out of the Kotlin sources at generation time, so the icon cannot drift away
from the button - change PrintButton.kt and run this again.

Committed as PNGs rather than a vector because the target device runs Android
7.1, where adaptive icons do not exist and vector launcher icons are a lottery
depending on the launcher.

    python tools/make_icon.py
"""

import math
import os
import re
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
UI = os.path.join(HERE, "..", "app", "src", "main", "java", "software", "zeasy", "momir", "ui")

BACKGROUND = (8, 9, 12)
FACE_LIT = (46, 51, 60)
FACE_DEEP = (10, 12, 16)
GROOVE = (5, 6, 10)
GOLD = (201, 168, 92)
GOLD_BRIGHT = (243, 228, 180)
GOLD_DEEP = (110, 92, 49)
LIGHT_CORE = (255, 246, 220)
LIGHT_MID = (240, 199, 102)
LIGHT_EDGE = (192, 138, 42)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# Enough that the rim, the gems and the symbol's prongs all survive the way down
# to a 48 px mdpi icon.
SUPERSAMPLE = 8


def read_sigil() -> list:
    """The symbol's cubic segments, straight out of PrintButton.kt."""
    source = open(os.path.join(UI, "PrintButton.kt"), encoding="utf-8").read()
    body = source.split("private val SIGIL", 1)[1].split(")\n", 1)[0]
    rows = re.findall(r"floatArrayOf\(([^)]*)\)", body)
    if not rows:
        raise SystemExit("could not find the SIGIL outline in PrintButton.kt")
    return [[float(n) for n in re.findall(r"-?\d+\.?\d*", row)] for row in rows]


def read_pentagon() -> list:
    """WUBRG, clockwise from the top, as ManaColors.kt defines them."""
    source = open(os.path.join(UI, "ManaColors.kt"), encoding="utf-8").read()
    out = []
    for name in ("WHITE", "BLUE", "BLACK", "RED", "GREEN"):
        hit = re.search(rf'val {name} = Color\.parseColor\("#([0-9A-Fa-f]{{6}})"\)', source)
        if not hit:
            raise SystemExit(f"could not find {name} in ManaColors.kt")
        out.append(tuple(int(hit.group(1)[i:i + 2], 16) for i in (0, 2, 4)))
    return out


def flatten(segments, left, top, scale, steps=24):
    pts = []
    for s in segments:
        for k in range(steps):
            t = k / steps
            u = 1 - t
            x = u**3*s[0] + 3*u*u*t*s[2] + 3*u*t*t*s[4] + t**3*s[6]
            y = u**3*s[1] + 3*u*u*t*s[3] + 3*u*t*t*s[5] + t**3*s[7]
            pts.append((left + x * scale, top + y * scale))
    return pts


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def radial(size, centre, radius, stops):
    """A radial gradient as a flat image, to be pasted through a mask."""
    img = Image.new("RGB", (size, size), stops[-1][1])
    draw = ImageDraw.Draw(img)
    steps = 120
    for i in range(steps, 0, -1):
        t = i / steps
        colour = stops[-1][1]
        for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
            if t0 <= t <= t1:
                colour = lerp(c0, c1, (t - t0) / (t1 - t0) if t1 > t0 else 0)
                break
        r = radius * t
        draw.ellipse([centre[0] - r, centre[1] - r, centre[0] + r, centre[1] + r], fill=colour)
    return img


def render(size: int, sigil, pentagon) -> Image.Image:
    big = size * SUPERSAMPLE
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    draw.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=BACKGROUND)

    cx = cy = big / 2
    # The icon is square and the seal fills it; the button's own proportions are
    # kept, only measured against the icon's edge rather than a 200 dp view.
    radius = big * 0.44
    face = radius * 0.81
    rim = radius * 0.94

    # Stone face, lit from above.
    stone = radial(big, (cx, cy - face * 0.34), face * 1.35,
                   [(0.0, FACE_LIT), (0.5, (25, 29, 36)), (1.0, FACE_DEEP)])
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse([cx - face, cy - face, cx + face, cy + face], fill=255)
    img.paste(stone, (0, 0), mask)

    groove = max(1, int(big * 0.014))
    draw.ellipse([cx - face, cy - face, cx + face, cy + face], outline=GROOVE, width=groove)

    # Brass rim: flat gold, then a lit arc top-left and a shadowed one bottom-right.
    ring = max(1, int(big * 0.020))
    box = [cx - rim, cy - rim, cx + rim, cy + rim]
    draw.ellipse(box, outline=GOLD, width=ring)
    draw.arc(box, 170, 300, fill=GOLD_BRIGHT, width=ring)
    draw.arc(box, 10, 130, fill=GOLD_DEEP, width=ring)

    # The symbol, lit from inside the stone.
    height = radius * 1.16
    scale = height / 100.0
    pts = flatten(sigil, cx - 50 * scale, cy - height / 2 - radius * 0.02, scale)

    for width, fade in [(0.055, 0.22), (0.038, 0.34), (0.024, 0.5), (0.013, 0.75)]:
        draw.line(pts + [pts[0]], width=max(1, int(big * width)),
                  fill=lerp(FACE_DEEP, LIGHT_MID, fade), joint="curve")

    glow = radial(big, (cx, cy), height * 0.62,
                  [(0.0, LIGHT_CORE), (0.45, LIGHT_MID), (1.0, LIGHT_EDGE)])
    smask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(smask).polygon(pts, fill=255)
    img.paste(glow, (0, 0), smask)

    # The five gems, WUBRG clockwise from the top.
    pip = radius * 0.058
    for index, colour in enumerate(pentagon):
        angle = math.radians(-90 + index * 72)
        px, py = cx + math.cos(angle) * rim, cy + math.sin(angle) * rim
        socket = pip * 1.7
        draw.ellipse([px - socket, py - socket, px + socket, py + socket], fill=GROOVE)
        draw.ellipse([px - pip, py - pip, px + pip, py + pip], fill=colour)

    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    sigil, pentagon = read_sigil(), read_pentagon()
    print(f"symbol: {len(sigil)} segments read from PrintButton.kt")

    res = os.path.join(HERE, "..", "app", "src", "main", "res")
    for density, size in DENSITIES.items():
        folder = os.path.join(res, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "ic_launcher.png")
        render(size, sigil, pentagon).save(path)
        print(f"{density:>8}  {size:>3}px  {path}")


if __name__ == "__main__":
    main()
