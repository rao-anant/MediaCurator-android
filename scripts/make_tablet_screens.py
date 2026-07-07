#!/usr/bin/env python3
"""
Turn phone screenshots into Play Store 7" and 10" tablet screenshots — no emulator.

It does NOT fake a tablet UI (impossible from a phone capture). Instead it composites
your real phone screenshot onto a tablet-sized canvas with a softly blurred backdrop
(the screenshot itself, scaled up + blurred), which looks clean and satisfies Play's
size/aspect requirements. This is a standard, allowed marketing technique.

Output canvases (portrait, 1.6:1 — well within Play's limits):
    7-inch  -> 1200 x 1920
    10-inch -> 1600 x 2560

Usage:
    pip install pillow
    python make_tablet_screens.py                # uses ./ (current dir)
    python make_tablet_screens.py "C:/path/to/phone/screenshots"

Reads .png/.jpg/.jpeg in that folder (skips its own output) and writes:
    <dir>/tablet_7/<name>.png
    <dir>/tablet_10/<name>.png
"""

import os
import sys
from PIL import Image, ImageFilter

SIZES = {
    "tablet_7":  (1200, 1920),
    "tablet_10": (1600, 2560),
}
IMG_EXTS = {".png", ".jpg", ".jpeg"}
SCREENSHOT_HEIGHT_FRAC = 0.90   # how tall the sharp screenshot sits on the canvas
BLUR_RADIUS = 40
BACKDROP_DARKEN = 0.55          # 0=black, 1=untouched; dims the blurred backdrop


def cover(img, size):
    """Scale to fully cover `size`, cropping the overflow (like CSS background-size: cover)."""
    tw, th = size
    iw, ih = img.size
    scale = max(tw / iw, th / ih)
    nw, nh = int(iw * scale), int(ih * scale)
    img = img.resize((nw, nh), Image.LANCZOS)
    left, top = (nw - tw) // 2, (nh - th) // 2
    return img.crop((left, top, left + tw, top + th))


def make_canvas(shot, size):
    tw, th = size
    # Blurred, darkened backdrop from the screenshot itself.
    backdrop = cover(shot, size).filter(ImageFilter.GaussianBlur(BLUR_RADIUS))
    backdrop = Image.eval(backdrop, lambda p: int(p * BACKDROP_DARKEN))

    # Sharp screenshot, scaled to fit the target height, centered.
    target_h = int(th * SCREENSHOT_HEIGHT_FRAC)
    scale = target_h / shot.height
    fg = shot.resize((int(shot.width * scale), target_h), Image.LANCZOS)
    # If it's wider than the canvas (rare), scale down to fit width instead.
    if fg.width > tw * 0.95:
        scale = int(tw * 0.95) / shot.width
        fg = shot.resize((int(shot.width * scale), int(shot.height * scale)), Image.LANCZOS)

    canvas = backdrop.convert("RGB")
    x, y = (tw - fg.width) // 2, (th - fg.height) // 2
    canvas.paste(fg, (x, y))
    return canvas


def main():
    target = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.getcwd())
    for sub in SIZES:
        os.makedirs(os.path.join(target, sub), exist_ok=True)

    files = [
        f for f in sorted(os.listdir(target))
        if os.path.isfile(os.path.join(target, f))
        and os.path.splitext(f)[1].lower() in IMG_EXTS
    ]
    if not files:
        print(f"No screenshots found in: {target}")
        return

    for name in files:
        try:
            shot = Image.open(os.path.join(target, name)).convert("RGB")
        except Exception as e:
            print(f"  skip {name}: {e}")
            continue
        base = os.path.splitext(name)[0]
        for sub, size in SIZES.items():
            out = os.path.join(target, sub, base + ".png")
            make_canvas(shot, size).save(out, "PNG")
        print(f"  {name} -> 7\" + 10\"")

    print(f"\nDone. Tablet screenshots in:\n  {os.path.join(target, 'tablet_7')}\n  {os.path.join(target, 'tablet_10')}")


if __name__ == "__main__":
    main()
