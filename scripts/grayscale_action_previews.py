"""Publish real Android screens in grayscale for human affordance review, without altering originals."""
from pathlib import Path
import sys
from PIL import Image, ImageOps

def main(folder):
    images = sorted(folder.glob("*.png"))
    if not images:
        raise ValueError("Missing Android screen captures")
    output = folder / "grayscale"
    output.mkdir(exist_ok=True)
    for image in images:
        with Image.open(image) as original:
            # Composite transparent dialog margins on white, not black.
            rgba = original.convert("RGBA")
            bg = Image.new("RGBA", rgba.size, "white")
            bg.alpha_composite(rgba)
            gray = ImageOps.grayscale(bg.convert("RGB"))
            if gray.getextrema()[0] == gray.getextrema()[1]:
                raise ValueError(f"No rendered content: {image.name}")
            gray.save(output / image.name)
    print(f"{len(images)} grayscale screenshots: {output}")
if __name__ == "__main__":
    main(Path(sys.argv[1]))
