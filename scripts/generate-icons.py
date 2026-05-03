#!/usr/bin/env python3
import argparse
from pathlib import Path

from PIL import Image

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
BACKGROUND = "#F5EFE2"
FOREGROUND_SIZE = 432

SAFE_FRACTION = 0.72


def parse_color(hex_color: str) -> tuple[int, int, int, int]:
    value = hex_color.lstrip("#")
    if len(value) != 6:
        raise ValueError(f"Expected RRGGBB color, got {hex_color}")
    red = int(value[0:2], 16)
    green = int(value[2:4], 16)
    blue = int(value[4:6], 16)
    return (red, green, blue, 255)


def square_logo(source: Path) -> Image.Image:
    image = Image.open(source).convert("RGBA")
    side = min(image.size)
    left = (image.width - side) // 2
    top = (image.height - side) // 2
    return image.crop((left, top, left + side, top + side))


def padded_icon(logo: Image.Image, size: int, background: tuple[int, int, int, int]) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), background)
    logo_size = max(1, int(size * SAFE_FRACTION))
    scaled = logo.resize((logo_size, logo_size), Image.Resampling.LANCZOS)
    offset = ((size - logo_size) // 2, (size - logo_size) // 2)
    canvas.alpha_composite(scaled, offset)
    return canvas


def write_adaptive_xml(path: Path, foreground: str, background: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/{background}" />
    <foreground android:drawable="@drawable/{foreground}" />
</adaptive-icon>
""",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate DroidLM launcher icon resources.")
    parser.add_argument("--source", default="droidlm-logo.png", help="Source square PNG logo")
    parser.add_argument("--res", default="app/src/main/res", help="Android res directory")
    parser.add_argument("--background", default=BACKGROUND, help="Launcher background color")
    args = parser.parse_args()

    source = Path(args.source)
    res = Path(args.res)
    background = parse_color(args.background)
    logo = square_logo(source)

    for density, size in DENSITIES.items():
        directory = res / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        icon = padded_icon(logo, size, background)
        icon.save(directory / "ic_launcher.png")
        icon.save(directory / "ic_launcher_round.png")

    drawable = res / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    foreground = padded_icon(logo, FOREGROUND_SIZE, (0, 0, 0, 0))
    foreground.save(drawable / "ic_launcher_foreground.png")
    (drawable / "ic_launcher_background.xml").write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<color xmlns:android="http://schemas.android.com/apk/res/android" android:color="{args.background}" />
""",
        encoding="utf-8",
    )

    anydpi = res / "mipmap-anydpi-v26"
    write_adaptive_xml(anydpi / "ic_launcher.xml", "ic_launcher_foreground", "ic_launcher_background")
    write_adaptive_xml(anydpi / "ic_launcher_round.xml", "ic_launcher_foreground", "ic_launcher_background")


if __name__ == "__main__":
    main()
