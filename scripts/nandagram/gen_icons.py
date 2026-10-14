#!/usr/bin/env python3
"""Генератор иконок Nandagram: золотая вайшнавская тилака (урдхва-пундра, U) с золотым листом туласи, без центральной полосы, на тёмно-синем фоне.
Использование: gen_icons.py <корень репозитория>"""
import sys, os, re, io, glob
import cairosvg
from PIL import Image
BG1, BG2 = "#22306b", "#0f1638"
GOLD1, GOLD2 = "#ffe08a", "#d99a1e"
RED = "#d6402a"
# 108x108 (adaptive safe zone 21..87)
TILAK = '''
<path d="M40,26 L40,50 A14,14 0 0 0 68,50 L68,26" fill="none" stroke="{g}" stroke-width="8" stroke-linecap="round"/>
<path d="M54,70 C61,74 61,82 54,88 C47,82 47,74 54,70 Z" fill="{g}"/>
<path d="M54,73 L54,85" stroke="{v}" stroke-width="1.2" stroke-linecap="round"/>
'''
def defs():
    return f'''<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{GOLD1}"/><stop offset="1" stop-color="{GOLD2}"/></linearGradient>
<radialGradient id="b" cx="0.5" cy="0.4" r="0.75"><stop offset="0" stop-color="{BG1}"/><stop offset="1" stop-color="{BG2}"/></radialGradient></defs>'''
def fg_svg(color=None):
    g = color or "url(#g)"; r = color or RED; v = color or GOLD2
    return TILAK.format(g=g, r=r, v=v)
def svg(kind):
    head = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">' + defs()
    if kind == "fg": body = fg_svg()
    elif kind == "bg": body = '<rect width="108" height="108" fill="url(#b)"/>'
    elif kind == "mono": body = fg_svg("#ffffff")
    elif kind == "legacy": body = '<rect x="6" y="6" width="96" height="96" rx="22" fill="url(#b)"/>' + fg_svg()
    elif kind == "round": body = '<circle cx="54" cy="54" r="48" fill="url(#b)"/>' + fg_svg()
    elif kind == "notif": body = '<g transform="translate(54 54) scale(1.35) translate(-54 -57)">' + fg_svg("#ffffff") + '</g>'
    return head + body + '</svg>'
def render(kind, w, h, path):
    png = cairosvg.svg2png(bytestring=svg(kind).encode(), output_width=w, output_height=h)
    im = Image.open(io.BytesIO(png)).convert("RGBA")
    im.save(path, "WEBP" if path.endswith(".webp") else "PNG")
def kind_of(name):
    n = os.path.basename(name).lower()
    if "notification" in n: return "notif"
    if "foreground" in n: return "fg"
    if "background" in n: return "bg"
    if "round" in n: return "round"
    if "launcher" in n: return "legacy"
    return None
VEC_FG = '''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M40,26 L40,50 A14,14 0 0 0 68,50 L68,26" android:strokeColor="{g}" android:strokeWidth="8" android:strokeLineCap="round"/>
    <path android:pathData="M54,70 C61,74 61,82 54,88 C47,82 47,74 54,70 Z" android:fillColor="{g}"/>
</vector>
'''
VEC_BG = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z" android:fillColor="{BG2}"/>
    <path android:pathData="M54,54m-60,0a60,60 0,1 1,120 0a60,60 0,1 1,-120 0" android:fillColor="{BG1}" android:fillAlpha="0.85"/>
</vector>
'''
def main(root):
    done = 0
    res_dirs = glob.glob(os.path.join(root, "TMessagesProj*/src/main/res"))
    for res in res_dirs:
        for f in glob.glob(os.path.join(res, "mipmap-*dpi/*")) + glob.glob(os.path.join(res, "drawable*/icon_background_clip*.webp")) + glob.glob(os.path.join(res, "drawable-*dpi/notification.png")):
            k = kind_of(f)
            if not k or not f.endswith((".png", ".webp")): continue
            w, h = Image.open(f).size
            render(k, w, h, f); done += 1
        for f in glob.glob(os.path.join(res, "drawable/icon_*background*.xml")):
            open(f, "w").write(VEC_BG); done += 1
        p = os.path.join(res, "drawable/icon_plane.xml")
        if os.path.exists(p):
            open(p, "w").write(VEC_FG.format(g="#FFFFFFFF", r="#FFFFFFFF")); done += 1
    print("files updated:", done)
if __name__ == "__main__":
    if sys.argv[1] == "--preview":
        out = sys.argv[2]
        tiles = [("legacy", 256), ("round", 256), ("fg", 256), ("notif", 96)]
        canvas = Image.new("RGBA", (256*3 + 96 + 100, 300), "#f0f0f0")
        x = 20
        for k, s in tiles:
            tmp = "/tmp/_p.png"
            if k == "fg":
                render("bg", s, s, "/tmp/_b.png"); render("fg", s, s, tmp)
                im = Image.alpha_composite(Image.open("/tmp/_b.png"), Image.open(tmp))
                m = Image.new("L", (s, s), 0); from PIL import ImageDraw; ImageDraw.Draw(m).rounded_rectangle((0,0,s,s), 60, fill=255)
                im.putalpha(m)
            else:
                render(k, s, s, tmp); im = Image.open(tmp)
                if k == "notif":
                    bg = Image.new("RGBA", (s, s), "#555555"); im = Image.alpha_composite(bg, im)
            canvas.alpha_composite(im, (x, 22)); x += s + 20
        canvas.save(out)
    else:
        main(sys.argv[1])
