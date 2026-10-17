#!/usr/bin/env python3
"""Generate all Android icon assets from a single source image.

Usage:
    pip install pillow
    python tools/make_icons.py [source_image] [res_dir]

Defaults: tools/icon_source.webp -> androidApp/src/main/res

Produces:
  mipmap-*/ic_launcher.png, ic_launcher_round.png      legacy icons (48dp)
  mipmap-*/ic_launcher_foreground.png                  adaptive foreground (108dp)
  mipmap-*/ic_launcher_monochrome.png                  themed icon, Android 13+
  mipmap-anydpi-v26/ic_launcher(.|_round).xml          adaptive icon definitions
  values/ic_launcher_background.xml                    background colour
  tools/out/play_store_icon_512.png                    Google Play listing icon

(The splash screens belong to the publisher, not the app: see tools/svg_to_vector.py.)
"""
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "tools" / "icon_source.webp"
RES = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "androidApp" / "src" / "main" / "res"
PLAY_OUT = ROOT / "tools" / "out" / "play_store_icon_512.png"

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_DP = 48
ADAPTIVE_DP = 108
# Fraction of the adaptive canvas the logo's largest dimension may occupy.
# Android's guaranteed-visible zone is 66/108 (~61%) so we stay inside it.
LOGO_FRACTION = 66 / 108
# Fraction of the legacy icon the logo's largest dimension occupies.
LEGACY_FRACTION = 0.86


def load_source():
    img = Image.open(SRC).convert("RGB")
    w, h = img.size
    side = max(w, h)
    bg = img.getpixel((4, 4))  # background colour sampled from the corner
    if (w, h) != (side, side):
        sq = Image.new("RGB", (side, side), bg)
        sq.paste(img, ((side - w) // 2, (side - h) // 2))
        img = sq
    return img, bg


def logo_bbox(img, bg, threshold=24):
    diff = ImageChops.difference(img, Image.new("RGB", img.size, bg)).convert("L")
    return diff.point(lambda v: 255 if v > threshold else 0).getbbox() or (0, 0, *img.size)


def centered_logo(img, bg, bbox, canvas_px, fraction):
    """Crop the logo, scale it to `fraction` of the canvas, centre it on a bg canvas."""
    logo = img.crop(bbox)
    scale = canvas_px * fraction / max(logo.size)
    logo = logo.resize((max(1, round(logo.width * scale)), max(1, round(logo.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGB", (canvas_px, canvas_px), bg)
    # Feather the edges: the source background is a slight gradient, so a hard
    # crop would leave a faintly visible rectangle against the flat colour.
    margin = max(2, round(min(logo.size) * 0.12))
    mask = Image.new("L", logo.size, 0)
    ImageDraw.Draw(mask).rectangle((margin, margin, logo.width - margin, logo.height - margin), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(margin / 2))
    canvas.paste(logo, ((canvas_px - logo.width) // 2, (canvas_px - logo.height) // 2), mask)
    return canvas


def circle_mask(size):
    scale = 4  # supersample for smooth edges
    m = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size * scale - 1, size * scale - 1), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def rounded_mask(size, radius_ratio=0.18):
    scale = 4
    m = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        (0, 0, size * scale - 1, size * scale - 1), radius=int(size * scale * radius_ratio), fill=255
    )
    return m.resize((size, size), Image.LANCZOS)


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", path.relative_to(ROOT) if path.is_relative_to(ROOT) else path)


def main():
    img, bg = load_source()
    bbox = logo_bbox(img, bg)
    bg_hex = "#{:02X}{:02X}{:02X}".format(*bg)
    print(f"source {SRC.name}, background {bg_hex}, logo bbox {bbox}")

    for name, d in DENSITIES.items():
        folder = RES / f"mipmap-{name}"

        # Legacy launcher icons (square with rounded corners + circle)
        px = round(LEGACY_DP * d)
        legacy = centered_logo(img, bg, bbox, px, LEGACY_FRACTION).convert("RGBA")
        sq = legacy.copy()
        sq.putalpha(rounded_mask(px))
        save(sq, folder / "ic_launcher.png")
        rd = legacy.copy()
        rd.putalpha(circle_mask(px))
        save(rd, folder / "ic_launcher_round.png")

        # Adaptive foreground (opaque, same colour as the background layer)
        apx = round(ADAPTIVE_DP * d)
        fg = centered_logo(img, bg, bbox, apx, LOGO_FRACTION)
        save(fg, folder / "ic_launcher_foreground.png")

        # Monochrome (themed icon): brightness above background becomes alpha
        gray = ImageChops.difference(fg, Image.new("RGB", fg.size, bg)).convert("L")
        gray = gray.point(lambda v: min(255, v * 3))
        mono = Image.new("RGBA", fg.size, (0, 0, 0, 255))
        mono.putalpha(gray)
        save(mono, folder / "ic_launcher_monochrome.png")

    # Adaptive icon XML
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        p = RES / "mipmap-anydpi-v26" / n
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(adaptive, encoding="utf-8")
        print("wrote", p.relative_to(ROOT))

    colors = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="ic_launcher_background">{bg_hex}</color>\n'
        "</resources>\n"
    )
    p = RES / "values" / "ic_launcher_background.xml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(colors, encoding="utf-8")
    print("wrote", p.relative_to(ROOT))

    # Play Store icon: full-bleed square, Google applies the mask
    play = centered_logo(img, bg, bbox, 512, 0.84)
    save(play, PLAY_OUT)

    print("\nDone. Manifest needs: android:icon=\"@mipmap/ic_launcher\" "
          "android:roundIcon=\"@mipmap/ic_launcher_round\"")


if __name__ == "__main__":
    main()
