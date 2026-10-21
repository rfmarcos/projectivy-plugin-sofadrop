"""Sofadrop icon generator: one geometry -> adaptive icon vectors + TV banners (Pillow only).
Regenerate from the repo root with the palette in use:
  BG_TOP="#FF8A3D" BG_BOT="#E0245E" SOFA="#B81E4E" python tools/gen_icon.py app/src/main/res build/icon-preview
"""
import sys, os, re
from PIL import Image, ImageDraw, ImageFont, ImageFilter

K = 0.5523  # circle bezier constant

def rrect(x0, y0, x1, y1, r):
    k = r * (1 - K)
    return (f"M{x0+r},{y0} L{x1-r},{y0} C{x1-k},{y0} {x1},{y0+k} {x1},{y0+r} "
            f"L{x1},{y1-r} C{x1},{y1-k} {x1-k},{y1} {x1-r},{y1} L{x0+r},{y1} "
            f"C{x0+k},{y1} {x0},{y1-k} {x0},{y1-r} L{x0},{y0+r} C{x0},{y0+k} {x0+k},{y0} {x0+r},{y0} Z")

def circle(cx, cy, r):
    k = r * K
    return (f"M{cx},{cy-r} C{cx+k},{cy-r} {cx+r},{cy-k} {cx+r},{cy} C{cx+r},{cy+k} {cx+k},{cy+r} {cx},{cy+r} "
            f"C{cx-k},{cy+r} {cx-r},{cy+k} {cx-r},{cy} C{cx-r},{cy-k} {cx-k},{cy-r} {cx},{cy-r} Z")

# --- Geometry (108x108 adaptive icon viewport, safe zone = circle r33 at 54,54) ---
CX, CY, R = 54, 60, 23          # round part of the drop
TIP = 20                        # tip of the drop
k = R * K
DROP = (f"M{CX},{TIP} C{CX+4},{TIP+10} {CX+R},{CY-15} {CX+R},{CY} "
        f"C{CX+R},{CY+k} {CX+k},{CY+R} {CX},{CY+R} C{CX-k},{CY+R} {CX-R},{CY+k} {CX-R},{CY} "
        f"C{CX-R},{CY-15} {CX-4},{TIP+10} {CX},{TIP} Z")
HIGHLIGHT = "M44.5,47 C46,41 49,36 52,32 C50.5,37 49.5,42 49.5,47 C49.5,49 44.5,49 44.5,47 Z"
SOFA = [
    rrect(40.5, 50.5, 67.5, 63, 3.8),   # backrest
    rrect(36, 57, 43.5, 72, 3.4),       # left armrest
    rrect(64.5, 57, 72, 72, 3.4),       # right armrest
    rrect(40, 62.5, 68, 70.5, 2),       # seat
    rrect(38.5, 70.5, 41.8, 75, 0.9),   # legs
    rrect(66.2, 70.5, 69.5, 75, 0.9),
]
SEAM = rrect(53.35, 53, 54.65, 62.5, 0.65)   # cushion split, drop-coloured

BG_TOP, BG_BOT = os.environ.get("BG_TOP", "#FF8A3D"), os.environ.get("BG_BOT", "#E0245E")
DROP_TOP, DROP_BOT = "#FFFFFF", "#FFEDE8"
SOFA_COLOR = os.environ.get("SOFA", "#B81E4E")

# --- Path flattening (M L C Z only) ---
def flatten(d, steps=24):
    toks = re.findall(r"[MLCZ]|-?\d+\.?\d*", d)
    polys, cur, i, pos = [], [], 0, (0, 0)
    while i < len(toks):
        t = toks[i]; i += 1
        if t == "M":
            if cur: polys.append(cur)
            pos = (float(toks[i]), float(toks[i+1])); i += 2; cur = [pos]
        elif t == "L":
            pos = (float(toks[i]), float(toks[i+1])); i += 2; cur.append(pos)
        elif t == "C":
            p = [pos] + [(float(toks[i+j]), float(toks[i+j+1])) for j in (0, 2, 4)]; i += 6
            for s in range(1, steps + 1):
                u = s / steps; a = (1-u)**3; b = 3*u*(1-u)**2; c = 3*u*u*(1-u); e = u**3
                cur.append((a*p[0][0]+b*p[1][0]+c*p[2][0]+e*p[3][0], a*p[0][1]+b*p[1][1]+c*p[2][1]+e*p[3][1]))
            pos = p[3]
        elif t == "Z":
            if cur: polys.append(cur); cur = []
    if cur: polys.append(cur)
    return polys

def hex2rgb(h): return tuple(int(h[i:i+2], 16) for i in (1, 3, 5))

def vgrad(size, top, bot, y0=0, y1=None):
    w, h = size; y1 = h if y1 is None else y1
    g = Image.new("RGB", (1, h))
    t0, t1 = hex2rgb(top), hex2rgb(bot)
    for y in range(h):
        u = min(max((y - y0) / max(y1 - y0, 1), 0), 1)
        g.putpixel((0, y), tuple(round(t0[c] + (t1[c]-t0[c])*u) for c in range(3)))
    return g.resize((w, h))

def dgrad(size, a, b):
    w, h = size; small = Image.new("RGB", (64, 64)); t0, t1 = hex2rgb(a), hex2rgb(b)
    for y in range(64):
        for x in range(64):
            u = (x + y) / 126
            small.putpixel((x, y), tuple(round(t0[c] + (t1[c]-t0[c])*u) for c in range(3)))
    return small.resize((w, h), Image.BILINEAR)

def mask(paths, scale, size, ox=0, oy=0):
    m = Image.new("L", size, 0); d = ImageDraw.Draw(m)
    for p in paths:
        for poly in flatten(p):
            d.polygon([(ox + x*scale, oy + y*scale) for x, y in poly], fill=255)
    return m

def render_icon(px, background=True, round_mask=False):
    """Full adaptive icon (108 viewport) rendered at px, supersampled."""
    ss = 4; S = px * ss; sc = S / 108
    img = dgrad((S, S), BG_TOP, BG_BOT) if background else Image.new("RGB", (S, S))
    img = img.convert("RGBA")
    if not background: img.putalpha(0)
    def layer(color_img, m):
        l = color_img.convert("RGBA"); l.putalpha(m); return l
    sh = mask([DROP], sc, (S, S), 0, 2.5*sc).filter(ImageFilter.GaussianBlur(3*sc)).point(lambda v: v * 0.35)
    img.alpha_composite(layer(Image.new("RGB", (S, S), (20, 10, 40)), sh))
    img.alpha_composite(layer(vgrad((S, S), DROP_TOP, DROP_BOT, int(TIP*sc), int((CY+R)*sc)), mask([DROP], sc, (S, S))))
    img.alpha_composite(layer(Image.new("RGB", (S, S), hex2rgb(SOFA_COLOR)), mask(SOFA, sc, (S, S))))
    img.alpha_composite(layer(Image.new("RGB", (S, S), hex2rgb(DROP_BOT)), mask([SEAM], sc, (S, S))))
    if round_mask:
        m = Image.new("L", (S, S), 0); ImageDraw.Draw(m).ellipse((0, 0, S, S), fill=255)
        img.putalpha(m)
    return img.resize((px, px), Image.LANCZOS)

def render_banner(w, h, font_path):
    ss = 4; W, H = w*ss, h*ss
    img = dgrad((W, H), BG_TOP, BG_BOT).convert("RGBA")
    # icon artwork (no background) scaled so the drop is ~78% of the banner height
    icon_px = int(H * 0.95)
    icon = render_icon(icon_px // ss * ss, background=False).resize((icon_px, icon_px), Image.LANCZOS)
    ix = int(W * 0.03); iy = (H - icon_px) // 2 + int(H*0.02)
    img.alpha_composite(icon, (ix, iy))
    font = ImageFont.truetype(font_path, int(H * 0.22))
    d = ImageDraw.Draw(img)
    text = "Sofadrop"
    tb = d.textbbox((0, 0), text, font=font)
    tx = ix + int(icon_px * 0.78); ty = (H - (tb[3]-tb[1])) // 2 - tb[1]
    d.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))
    return img.resize((w, h), Image.LANCZOS).convert("RGB")

def vector_xml(foreground=True):
    head = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')
    def grad(top, bot, y0, y1):
        return ('        <aapt:attr name="android:fillColor">\n'
                f'            <gradient android:type="linear" android:startX="54" android:startY="{y0}" android:endX="54" android:endY="{y1}"\n'
                f'                android:startColor="{top}" android:endColor="{bot}" />\n'
                '        </aapt:attr>\n')
    if not foreground:
        g = grad(BG_TOP, BG_BOT, 0, 108).replace('startX="54" android:startY="0" android:endX="54"', 'startX="0" android:startY="0" android:endX="108"')
        return head + '    <path android:pathData="M0,0h108v108h-108z">\n' + g + '    </path>\n</vector>\n'
    body = '    <!-- A drop with a sofa cut out of it: Sofa + drop -->\n'
    body += f'    <path android:pathData="{DROP}">\n' + grad(DROP_TOP, DROP_BOT, TIP, CY + R) + '    </path>\n'
    body += f'    <path android:fillColor="{SOFA_COLOR}" android:pathData="' + ' '.join(SOFA) + '" />\n'
    body += f'    <path android:fillColor="{DROP_BOT}" android:pathData="{SEAM}" />\n'
    return head + body + '</vector>\n'

if __name__ == "__main__":
    res, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    font = os.path.join(res if res != "-" else sys.argv[3], "font", "roboto_bold.ttf") if res != "-" else sys.argv[3]
    render_icon(512).save(os.path.join(out, "icon_square.png"))
    render_icon(512, round_mask=True).save(os.path.join(out, "icon_round.png"))
    for px in (48, 96):
        render_icon(px, round_mask=True).save(os.path.join(out, f"icon_{px}.png"))
    render_banner(640, 360, font).save(os.path.join(out, "banner_preview.png"))
    if res != "-":
        open(os.path.join(res, "drawable", "ic_launcher_foreground.xml"), "w").write(vector_xml(True))
        open(os.path.join(res, "drawable", "ic_launcher_background.xml"), "w").write(vector_xml(False))
        render_banner(320, 180, font).save(os.path.join(res, "drawable-xhdpi", "ic_banner.png"))
        render_banner(480, 270, font).save(os.path.join(res, "drawable-xxhdpi", "ic_banner.png"))
