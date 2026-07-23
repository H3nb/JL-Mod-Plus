#!/usr/bin/env python3
"""
Generate Android launcher icon assets from the JL-Mod Plus Play Store artwork.

The source artwork remains visually unchanged. The generated Android assets
separate the foreground from the background, keep the logo inside the adaptive
icon safe zone, provide a true monochrome layer, and generate legacy round and
rounded-square icons.

Requires Pillow. Run from anywhere with:

    python scripts/generate-launcher-icons.py
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageCms, ImageDraw


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = REPOSITORY_ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png"
RESOURCE_ROOT = REPOSITORY_ROOT / "app" / "src" / "main" / "res"

ADAPTIVE_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

LEGACY_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

ADAPTIVE_CANVAS_DP = 108
ADAPTIVE_SAFE_ZONE_DP = 66
LOGO_SIZE_DP = 62


def srgb_profile_bytes() -> bytes:
    profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB"))
    return profile.tobytes()


def save_png(image: Image.Image, path: Path, *, icc_profile: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True, icc_profile=icc_profile)


def extract_symbol(source: Image.Image) -> Image.Image:
    """
    Remove the dark-blue generated background.

    The logo is cyan, teal, and orange, while the background has very little
    red or green. A smooth red/green luminance threshold preserves anti-aliased
    edges without retaining the background glow.
    """

    rgb = source.convert("RGB")
    rgba_pixels: list[tuple[int, int, int, int]] = []

    for red, green, blue in rgb.get_flattened_data():
        signal = max(red, green)
        normalized = max(0.0, min(1.0, (signal - 55.0) / 65.0))
        smooth = normalized * normalized * (3.0 - 2.0 * normalized)
        alpha = round(smooth * 255)
        rgba_pixels.append((red, green, blue, alpha))

    symbol = Image.new("RGBA", rgb.size)
    symbol.putdata(rgba_pixels)

    alpha = symbol.getchannel("A")
    visible = alpha.point(lambda value: 255 if value >= 8 else 0)
    bounds = visible.getbbox()
    if bounds is None:
        raise ValueError("No foreground artwork was detected in the source icon.")

    return symbol.crop(bounds)


def fit_symbol(symbol: Image.Image, canvas_size: int) -> Image.Image:
    target_size = round(canvas_size * LOGO_SIZE_DP / ADAPTIVE_CANVAS_DP)
    scale = min(target_size / symbol.width, target_size / symbol.height)
    resized_size = (
        max(1, round(symbol.width * scale)),
        max(1, round(symbol.height * scale)),
    )
    resized = symbol.resize(resized_size, Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    position = (
        (canvas_size - resized.width) // 2,
        (canvas_size - resized.height) // 2,
    )
    canvas.alpha_composite(resized, position)

    safe_zone_size = round(canvas_size * ADAPTIVE_SAFE_ZONE_DP / ADAPTIVE_CANVAS_DP)
    safe_zone_margin = (canvas_size - safe_zone_size) // 2
    alpha_bounds = canvas.getchannel("A").getbbox()
    if alpha_bounds is None:
        raise ValueError("Generated adaptive foreground is empty.")

    left, top, right, bottom = alpha_bounds
    if (
        left < safe_zone_margin
        or top < safe_zone_margin
        or right > canvas_size - safe_zone_margin
        or bottom > canvas_size - safe_zone_margin
    ):
        raise ValueError(
            f"Generated foreground exceeds the {ADAPTIVE_SAFE_ZONE_DP} dp safe zone: "
            f"{alpha_bounds} on {canvas_size}px canvas"
        )

    return canvas


def monochrome_from_foreground(foreground: Image.Image) -> Image.Image:
    monochrome = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
    monochrome.putalpha(foreground.getchannel("A"))
    return monochrome


def legacy_icon(source: Image.Image, size: int, *, round_icon: bool) -> Image.Image:
    artwork = source.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)

    if round_icon:
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        radius = max(1, round(size * 0.22))
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)

    artwork.putalpha(ImageChops.multiply(artwork.getchannel("A"), mask))
    return artwork


def validate_outputs(source_path: Path) -> None:
    store_asset = Image.open(source_path)
    if store_asset.size != (512, 512) or store_asset.mode != "RGBA":
        raise ValueError("Play Store asset must be a 512x512 RGBA PNG.")
    if store_asset.getchannel("A").getextrema() != (255, 255):
        raise ValueError("Play Store asset must have a fully opaque alpha channel.")
    if "icc_profile" not in store_asset.info:
        raise ValueError("Play Store asset is missing its sRGB color profile.")

    for density, canvas_size in ADAPTIVE_SIZES.items():
        directory = RESOURCE_ROOT / f"mipmap-{density}"
        safe_zone_size = round(canvas_size * ADAPTIVE_SAFE_ZONE_DP / ADAPTIVE_CANVAS_DP)
        safe_zone_margin = (canvas_size - safe_zone_size) // 2

        for filename in ("ic_launcher_foreground.png", "ic_launcher_monochrome.png"):
            image = Image.open(directory / filename)
            if image.size != (canvas_size, canvas_size) or image.mode != "RGBA":
                raise ValueError(f"Invalid adaptive asset: {directory / filename}")
            if image.getchannel("A").getextrema() != (0, 255):
                raise ValueError(f"Adaptive asset lacks transparency: {directory / filename}")

            left, top, right, bottom = image.getchannel("A").getbbox() or (0, 0, 0, 0)
            if (
                left < safe_zone_margin
                or top < safe_zone_margin
                or right > canvas_size - safe_zone_margin
                or bottom > canvas_size - safe_zone_margin
            ):
                raise ValueError(f"Adaptive asset exceeds safe zone: {directory / filename}")

    for density, icon_size in LEGACY_SIZES.items():
        directory = RESOURCE_ROOT / f"mipmap-{density}"
        for filename in ("ic_launcher.png", "ic_launcher_round.png"):
            image = Image.open(directory / filename)
            if image.size != (icon_size, icon_size) or image.mode != "RGBA":
                raise ValueError(f"Invalid legacy asset: {directory / filename}")
            if image.getchannel("A").getextrema() != (0, 255):
                raise ValueError(f"Legacy asset lacks shaped transparency: {directory / filename}")


def generate(source_path: Path) -> None:
    source = Image.open(source_path).convert("RGBA")
    if source.size != (512, 512):
        raise ValueError(f"Play Store source must be 512x512, found {source.size}.")

    profile = srgb_profile_bytes()
    symbol = extract_symbol(source)

    # Normalize the store asset to an explicit 32-bit RGBA, sRGB PNG.
    opaque_store_asset = source.copy()
    opaque_store_asset.putalpha(255)
    save_png(opaque_store_asset, source_path, icc_profile=profile)

    for density, canvas_size in ADAPTIVE_SIZES.items():
        foreground = fit_symbol(symbol, canvas_size)
        monochrome = monochrome_from_foreground(foreground)
        directory = RESOURCE_ROOT / f"mipmap-{density}"
        save_png(
            foreground,
            directory / "ic_launcher_foreground.png",
            icc_profile=profile,
        )
        save_png(
            monochrome,
            directory / "ic_launcher_monochrome.png",
            icc_profile=profile,
        )

    for density, icon_size in LEGACY_SIZES.items():
        directory = RESOURCE_ROOT / f"mipmap-{density}"
        save_png(
            legacy_icon(source, icon_size, round_icon=False),
            directory / "ic_launcher.png",
            icc_profile=profile,
        )
        save_png(
            legacy_icon(source, icon_size, round_icon=True),
            directory / "ic_launcher_round.png",
            icc_profile=profile,
        )

    validate_outputs(source_path)
    print(f"Source: {source_path}")
    print(f"Detected symbol: {symbol.width}x{symbol.height}px")
    print("Generated and validated adaptive, monochrome, legacy, and round icons.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        type=Path,
        default=DEFAULT_SOURCE,
        help="512x512 Play Store artwork (default: app/src/main/ic_launcher-playstore.png)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    arguments = parse_args()
    generate(arguments.source.resolve())
