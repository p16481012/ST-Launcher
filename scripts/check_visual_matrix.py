"""Check rendered Compose PNGs, not just a palette object or swatch preview."""
from itertools import combinations, product
from pathlib import Path
import hashlib
import sys

from PIL import Image, ImageChops, ImageDraw

def verify_management_captures(root):
    # A screenshot of a blank window can still have a status bar and a valid PNG header.
    # Check for contrasting content inside several separate strips of the app body.
    for name in ("tool-installation", "management-update", "management-backup"):
        with Image.open(root / f"{name}.png") as raw:
            image = raw.convert("L")
        w, h = image.size
        bands_with_content = 0
        for top in (0.12, 0.28, 0.44, 0.60, 0.76):
            band = image.crop((round(w * .04), round(h * top), round(w * .96), round(h * (top + .12))))
            hist = band.histogram()
            if sum(hist[:150]) >= 60 and sum(hist[195:]) >= 250:
                bands_with_content += 1
        if bands_with_content < 2:
            raise ValueError(f"{name}: no readable body content ({bands_with_content} nonblank bands)")
        print(f"{name}: management body visible in {bands_with_content} bands")

def main(root):
    verify_management_captures(root)
    accents = ("amber", "green", "wine", "slate", "blue", "violet")
    modes = tuple(product(("light", "dark"), ("normal", "high")))
    for screen in ("home", "settings", "backups"):
        images = {}
        for (mode, contrast), accent in product(modes, accents):
            path = root / f"palette-{screen}-{mode}-{contrast}-{accent}.png"
            with Image.open(path) as raw:
                images[mode, contrast, accent] = raw.convert("RGB")
        assert len({im.size for im in images.values()}) == 1, screen
        assert len({hashlib.sha256(im.tobytes()).digest() for im in images.values()}) == len(modes) * len(accents), screen
        for (mode, contrast), (a, b) in product(modes, combinations(accents, 2)):
            diff = ImageChops.difference(images[mode, contrast, a], images[mode, contrast, b]).convert("L")
            assert sum(diff.histogram()[2:]) >= 24, (screen, mode, contrast, a, b, "accent pixels unchanged")
        for contrast, accent in product(("normal", "high"), accents):
            light = images["light", contrast, accent].convert("L")
            dark = images["dark", contrast, accent].convert("L")
            assert light.resize((1, 1)).getpixel((0, 0)) - dark.resize((1, 1)).getpixel((0, 0)) > 70, (screen, accent, contrast)
        for mode, accent in product(("light", "dark"), accents):
            diff = ImageChops.difference(images[mode, "normal", accent], images[mode, "high", accent]).convert("L")
            assert sum(diff.histogram()[2:]) >= 24, (screen, mode, accent, "high contrast unchanged")
        w, h = next(iter(images.values())).size
        tw = 240
        th = round(tw * h / w)
        sheet = Image.new("RGB", (tw * len(accents), (th + 22) * len(modes)), "#ddd")
        draw = ImageDraw.Draw(sheet)
        for row, (mode, contrast) in enumerate(modes):
            for col, accent in enumerate(accents):
                x, y = col * tw, row * (th + 22)
                draw.text((x + 4, y + 4), f"{mode} / {contrast} / {accent}", fill="#111")
                sheet.paste(images[mode, contrast, accent].resize((tw, th), Image.Resampling.LANCZOS), (x, y + 22))
        sheet.save(root / f"matrix-{screen}.jpg", quality=90)
        print(f"{screen}: verified {len(images)} distinct rendered combinations")

if __name__ == "__main__":
    main(Path(sys.argv[1]))
