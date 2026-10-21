"""Sofadrop icon generator ("falling frames"): one geometry -> adaptive icon vectors + TV banners (Pillow only).

Three tilted panels (new backdrops) drop onto a white sofa, on a near-black tile.
  python tools/gen_icon.py app/src/main/res build/icon-preview      # write resources + previews
  python tools/gen_icon.py - build/icon-preview                       # previews only

Shapes are measured on the chosen concept in a 642 px tile (= the 72 dp a launcher shows of the
108 dp adaptive icon), then mapped to the 108 viewport.
"""
import math
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

K = 0.5523  # circle bezier constant
BG_A, BG_B = "#16151E", "#0B0B10"                       # tile (diagonal)
PANEL_A, PANEL_M, PANEL_B = "#FF9A3C", "#F0306A", "#6E1A72"  # panels: orange -> magenta -> dark purple (fades at the bottom)
SOFA = "#FFFFFF"


TILE_X0, TILE_W = 4, 632  # the concept's tile inside the measured crop


def v(px):
    """Concept pixel -> viewport unit (the tile is the 72 dp a launcher shows)."""
    return 18 + (px - TILE_X0) * 72 / TILE_W


def u(px):
    return px * 72 / TILE_W


def rrect(x0, y0, x1, y1, r):
    k = r * (1 - K)
    return (f"M{x0+r:.3f},{y0:.3f} L{x1-r:.3f},{y0:.3f} C{x1-k:.3f},{y0:.3f} {x1:.3f},{y0+k:.3f} {x1:.3f},{y0+r:.3f} "
            f"L{x1:.3f},{y1-r:.3f} C{x1:.3f},{y1-k:.3f} {x1-k:.3f},{y1:.3f} {x1-r:.3f},{y1:.3f} L{x0+r:.3f},{y1:.3f} "
            f"C{x0+k:.3f},{y1:.3f} {x0:.3f},{y1-k:.3f} {x0:.3f},{y1-r:.3f} L{x0:.3f},{y0+r:.3f} C{x0:.3f},{y0+k:.3f} {x0+k:.3f},{y0:.3f} {x0+r:.3f},{y0:.3f} Z")


def rotate(d, angle, cx, cy):
    """Rotate every coordinate pair of an M/L/C path around (cx, cy)."""
    a = math.radians(angle)
    ca, sa = math.cos(a), math.sin(a)

    def turn(m):
        x, y = float(m.group(1)) - cx, float(m.group(2)) - cy
        return f"{cx + x * ca - y * sa:.3f},{cy + x * sa + y * ca:.3f}"
    return re.sub(r"(-?\d+\.?\d*),(-?\d+\.?\d*)", turn, d)


# --- Geometry, in concept pixels ---
ANGLE = 20                      # panels slope down to the right
PANELS = [                      # back to front: top-left corner, width, height (cut at CLIP_Y)
    ((240, 68), 279, 360),
    ((170, 172), 272, 330),
    ((158, 262), 218, 260),
]
PANEL_R, GAP = 14, 11
CLIP_Y = 373                    # panels are cut flat just above the sofa
SOFA_BACK = (178, 393, 455, 500, 22)
SOFA_BASE = [
    (133, 452, 197, 530, 30),   # left armrest (rolled)
    (437, 452, 501, 530, 30),   # right armrest
    (136, 492, 498, 560, 33),   # seat and base, rounded underneath
    (182, 552, 202, 588, 7),    # legs
    (432, 552, 452, 588, 7),
]
FADE = (340,)  # panels darken from here down to CLIP_Y
SOFA_GAP = 13


def panel_path(i, grow=0.0):
    (x, y), w, h = PANELS[i]
    return rotate(rrect(v(x) - u(grow), v(y) - u(grow), v(x + w) + u(grow), v(y + h) + u(grow), u(PANEL_R + grow)), ANGLE, v(x), v(y))


def box(b, grow=0.0):
    x0, y0, x1, y1, r = b
    return rrect(v(x0) - u(grow), v(y0) - u(grow), v(x1) + u(grow), v(y1) + u(grow), u(r + grow))


CLIP = v(CLIP_Y)
SOFA_BACK_PATH = box(SOFA_BACK)
SOFA_GAP_PATHS = [box(b, SOFA_GAP) for b in SOFA_BASE[:3]]   # separates arms and seat from the backrest
SOFA_BASE_PATHS = [box(b) for b in SOFA_BASE]
GRAD = (v(200), v(95), v(480), v(330))                  # panels gradient: orange left, magenta right, purple low
FADE_COLOR = "#121119"


# --- Path flattening (M L C Z only) for the PNG previews ---
def flatten(d, steps=24):
    toks = re.findall(r"[MLCZ]|-?\d+\.?\d*", d)
    polys, cur, i, pos = [], [], 0, (0, 0)
    while i < len(toks):
        t = toks[i]; i += 1
        if t == "M":
            if cur: polys.append(cur)
            pos = (float(toks[i]), float(toks[i + 1])); i += 2; cur = [pos]
        elif t == "L":
            pos = (float(toks[i]), float(toks[i + 1])); i += 2; cur.append(pos)
        elif t == "C":
            p = [pos] + [(float(toks[i + j]), float(toks[i + j + 1])) for j in (0, 2, 4)]; i += 6
            for s in range(1, steps + 1):
                t1 = s / steps; a = (1 - t1) ** 3; b = 3 * t1 * (1 - t1) ** 2; c = 3 * t1 * t1 * (1 - t1); e = t1 ** 3
                cur.append((a * p[0][0] + b * p[1][0] + c * p[2][0] + e * p[3][0], a * p[0][1] + b * p[1][1] + c * p[2][1] + e * p[3][1]))
            pos = p[3]
        elif t == "Z":
            if cur: polys.append(cur); cur = []
    if cur: polys.append(cur)
    return polys


def hex2rgb(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def linear(size, colors, x0, y0, x1, y1):
    """Linear gradient over a size x size image between viewport points (x0,y0)->(x1,y1)."""
    w, h = size
    n = 96; small = Image.new("RGB", (n, n)); stops = [hex2rgb(c) for c in colors]
    dx, dy = x1 - x0, y1 - y0; length = dx * dx + dy * dy
    for py in range(n):
        for px in range(n):
            x, y = px / (n - 1) * 108, py / (n - 1) * 108
            t = min(max(((x - x0) * dx + (y - y0) * dy) / length, 0), 1) * (len(stops) - 1)
            k = min(int(t), len(stops) - 2); f = t - k
            small.putpixel((px, py), tuple(round(stops[k][c] + (stops[k + 1][c] - stops[k][c]) * f) for c in range(3)))
    return small.resize((w, h), Image.BILINEAR)


def mask(paths, sc, size):
    m = Image.new("L", size, 0); d = ImageDraw.Draw(m)
    for p in paths:
        for poly in flatten(p):
            d.polygon([(x * sc, y * sc) for x, y in poly], fill=255)
    return m


def artwork(S):
    """Background + foreground of the full 108 viewport at S px."""
    sc = S / 108
    bg = linear((S, S), [BG_A, BG_B], 0, 0, 108, 108).convert("RGBA")
    img = bg.copy()
    panels = linear((S, S), [PANEL_A, PANEL_M, PANEL_B], *GRAD).convert("RGBA")
    clip = Image.new("L", (S, S), 0); ImageDraw.Draw(clip).rectangle((0, 0, S, CLIP * sc), fill=255)
    for i in range(len(PANELS)):
        if i > 0:  # the margin around a front panel shows the tile over the panel behind
            img.paste(bg, (0, 0), Image.composite(mask([panel_path(i, GAP)], sc, (S, S)), Image.new("L", (S, S), 0), clip))
        m = Image.composite(mask([panel_path(i)], sc, (S, S)), Image.new("L", (S, S), 0), clip)
        layer = panels.copy(); layer.putalpha(m); img.alpha_composite(layer)
    fade = Image.new("L", (S, S), 0); fd = ImageDraw.Draw(fade)
    y0, y1 = v(FADE[0]) * sc, CLIP * sc
    for yy in range(int(y0), int(y1) + 1):
        fd.line((0, yy, S, yy), fill=int(255 * min(max((yy - y0) / (y1 - y0), 0), 1)))
    img.paste(Image.new("RGBA", (S, S), hex2rgb(FADE_COLOR) + (255,)), (0, 0), Image.composite(fade, Image.new("L", (S, S), 0), clip))
    white = Image.new("RGBA", (S, S), hex2rgb(SOFA) + (255,))
    back = white.copy(); back.putalpha(mask([SOFA_BACK_PATH], sc, (S, S))); img.alpha_composite(back)
    img.paste(bg, (0, 0), mask(SOFA_GAP_PATHS, sc, (S, S)))
    base = white.copy(); base.putalpha(mask(SOFA_BASE_PATHS, sc, (S, S))); img.alpha_composite(base)
    return img


def render_icon(px, shape="square"):
    """What a launcher shows: the central 72x72 of the viewport, masked to a shape."""
    ss = 4; S = px * ss * 3 // 2
    img = artwork(S).crop((S // 6, S // 6, S - S // 6, S - S // 6)).resize((px * ss, px * ss), Image.LANCZOS)
    if shape != "square":
        T = px * ss
        m = Image.new("L", (T, T), 0); d = ImageDraw.Draw(m)
        if shape == "circle": d.ellipse((0, 0, T, T), fill=255)
        else: d.rounded_rectangle((0, 0, T, T), radius=T * 0.22, fill=255)
        img.putalpha(m)
    return img.resize((px, px), Image.LANCZOS)


def render_banner(w, h, font_path):
    ss = 4; W, H = w * ss, h * ss
    img = Image.new("RGBA", (W, H), hex2rgb("#0E0E14") + (255,))
    # Icon tile on the left, wordmark on the right
    tile = int(H * 0.56); tx = int(H * 0.12); ty = (H - tile) // 2
    icon = render_icon(tile // ss, "rounded").resize((tile, tile), Image.LANCZOS)
    img.alpha_composite(icon, (tx, ty))
    font = ImageFont.truetype(font_path, int(H * 0.215))
    d = ImageDraw.Draw(img)
    tb = d.textbbox((0, 0), "Sofadrop", font=font)
    d.text((tx + tile + int(H * 0.09), (H - (tb[3] - tb[1])) // 2 - tb[1]), "Sofadrop", font=font, fill=(255, 255, 255, 255))
    return img.resize((w, h), Image.LANCZOS).convert("RGB")


def vector_xml(foreground):
    head = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')

    def gradient(colors, x0, y0, x1, y1, indent="        "):
        center = f' android:centerColor="{colors[1]}"' if len(colors) == 3 else ""
        return (f'{indent}<aapt:attr name="android:fillColor">\n'
                f'{indent}    <gradient android:type="linear" android:startX="{x0:.3f}" android:startY="{y0:.3f}" android:endX="{x1:.3f}" android:endY="{y1:.3f}"\n'
                f'{indent}        android:startColor="{colors[0]}"{center} android:endColor="{colors[-1]}" />\n'
                f'{indent}</aapt:attr>\n')
    tile = [BG_A, BG_B]
    if not foreground:
        return head + '    <path android:pathData="M0,0h108v108h-108z">\n' + gradient(tile, 0, 0, 108, 108) + '    </path>\n</vector>\n'
    body = '    <!-- Three panels (new backdrops) dropping onto a sofa; panels are cut flat above it -->\n'
    body += f'    <group>\n        <clip-path android:pathData="M0,0H108V{CLIP:.3f}H0Z" />\n'
    for i in range(len(PANELS)):
        if i > 0:  # a margin filled with the tile's own gradient separates it from the panel behind
            body += f'        <path android:pathData="{panel_path(i, GAP)}">\n' + gradient(tile, 0, 0, 108, 108, "            ") + '        </path>\n'
        body += f'        <path android:pathData="{panel_path(i)}">\n' + gradient([PANEL_A, PANEL_M, PANEL_B], *GRAD, "            ") + '        </path>\n'
    # Panels darken into the tile just above the sofa
    body += (f'        <path android:pathData="M0,{v(FADE[0]):.3f}H108V{CLIP:.3f}H0Z">\n'
             '            <aapt:attr name="android:fillColor">\n'
             f'                <gradient android:type="linear" android:startX="54" android:startY="{v(FADE[0]):.3f}" android:endX="54" android:endY="{CLIP:.3f}"\n'
             f'                    android:startColor="#00{FADE_COLOR[1:]}" android:endColor="#FF{FADE_COLOR[1:]}" />\n'
             '            </aapt:attr>\n        </path>\n')
    body += '    </group>\n'
    body += f'    <path android:fillColor="{SOFA}" android:pathData="{SOFA_BACK_PATH}" />\n'
    body += '    <path android:pathData="' + ' '.join(SOFA_GAP_PATHS) + '">\n' + gradient(tile, 0, 0, 108, 108) + '    </path>\n'
    body += f'    <path android:fillColor="{SOFA}" android:pathData="' + ' '.join(SOFA_BASE_PATHS) + '" />\n'
    return head + body + '</vector>\n'


if __name__ == "__main__":
    res, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    pts = [(x, min(y, CLIP)) for d in [panel_path(i) for i in range(len(PANELS))] + SOFA_BASE_PATHS for poly in flatten(d) for x, y in poly]
    print(f"artwork reaches {max(math.hypot(x - 54, y - 54) for x, y in pts):.1f} from the centre (safe zone 33, visible 36)")
    font = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "font", "roboto_bold.ttf")
    for shape in ("square", "circle", "rounded"):
        render_icon(512, shape).save(os.path.join(out, f"icon_{shape}.png"))
    for px in (48, 96):
        render_icon(px, "rounded").save(os.path.join(out, f"icon_{px}.png"))
    render_banner(640, 360, font).save(os.path.join(out, "banner_preview.png"))
    if res != "-":
        open(os.path.join(res, "drawable", "ic_launcher_foreground.xml"), "w").write(vector_xml(True))
        open(os.path.join(res, "drawable", "ic_launcher_background.xml"), "w").write(vector_xml(False))
        render_banner(320, 180, font).save(os.path.join(res, "drawable-xhdpi", "ic_banner.png"))
        render_banner(480, 270, font).save(os.path.join(res, "drawable-xxhdpi", "ic_banner.png"))
