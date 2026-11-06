#!/usr/bin/env python3
"""Builds launcher icons + loading screen from branding/icon.jpg and branding/splash.jpg.
Missing files are skipped (existing placeholder resources stay in place)."""
import os, sys
from PIL import Image, ImageDraw
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
ICON = os.path.join(ROOT, "branding/icon.jpg")
SPLASH = os.path.join(ROOT, "branding/splash.jpg")

def avg_edge(img):
    im = img.convert("RGB").resize((64, 64))
    px = [im.getpixel((i, j)) for i in range(64) for j in (0, 63)] + \
         [im.getpixel((j, i)) for i in range(64) for j in (0, 63)]
    return tuple(sum(c[k] for c in px) // len(px) for k in range(3))

def square(img):
    w, h = img.size; s = min(w, h)
    return img.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))

def write(path, img, fmt="PNG", **kw):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, fmt, **kw)

def color_xml(name, rgb):
    return '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="%s">#%02X%02X%02X</color>\n</resources>\n' % ((name,) + tuple(rgb))

if os.path.isfile(ICON) and os.path.getsize(ICON) > 0:
    src = Image.open(ICON).convert("RGB")
    sq = square(src)
    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    adaptive = {k: v * 108 // 48 for k, v in legacy.items()}
    for d, px in legacy.items():
        im = sq.resize((px, px), Image.LANCZOS)
        write(f"{RES}/mipmap-{d}/ic_launcher.png", im)
        mask = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
        mask = mask.resize((px, px), Image.LANCZOS)
        rnd = Image.new("RGBA", (px, px), (0, 0, 0, 0)); rnd.paste(im, (0, 0), mask)
        write(f"{RES}/mipmap-{d}/ic_launcher_round.png", rnd)
    for d, px in adaptive.items():
        inner = int(px * 0.70)
        fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        fg.paste(sq.resize((inner, inner), Image.LANCZOS), ((px - inner) // 2, (px - inner) // 2))
        write(f"{RES}/mipmap-{d}/ic_launcher_foreground.png", fg)
    open(f"{RES}/values/ic_launcher_background.xml", "w").write(color_xml("ic_launcher_background", avg_edge(sq)))
    print("icon generated")
else:
    print("no branding/icon.jpg - keeping existing icons")

if os.path.isfile(SPLASH) and os.path.getsize(SPLASH) > 0:
    s = Image.open(SPLASH).convert("RGB")
    s.thumbnail((1600, 1600), Image.LANCZOS)
    write(f"{RES}/drawable-nodpi/splash.jpg", s, "JPEG", quality=88)
    open(f"{RES}/values/splash_color.xml", "w").write(color_xml("splash_bg", avg_edge(s)))
    print("splash generated")
else:
    print("no branding/splash.jpg - keeping existing splash")
