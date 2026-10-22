"""Sofadrop icon generator ("falling frames"): one geometry -> adaptive icon vectors + TV banners (Pillow only).

Three tilted panels (new backdrops) drop onto a white sofa, on a near-black tile.
  python tools/gen_icon.py app/src/main/res build/icon-preview      # write resources + previews
  python tools/gen_icon.py - build/icon-preview                       # previews only

The cards were fitted and the sofa traced on the chosen concept (its tile = the 72 dp a launcher
shows of the 108 dp adaptive icon).
"""
import math
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

K = 0.5523  # circle bezier constant
BG_A, BG_B = "#0F0F1F", "#0A0A16"                       # tile (diagonal), the concept's navy black
PANEL_A, PANEL_M, PANEL_B = "#FB8B2F", "#F23C4C", "#A8186E"  # panels: orange -> red-pink -> magenta (sampled from the concept)
SOFA = "#FFFFFF"


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


# --- Geometry (108 viewport; launchers show the central 72) ---
# Fitted to the concept: 3 rotated rounded cards (each front one cutting an L-shaped gap into the
# ones behind, all cut flat above the sofa) and a symmetric sofa.
ANGLE = 19.50
PANELS = [  # back to front: top-left corner, width (rotation is around that corner)
    ((44.719, 24.333), 31.951),
    ((37.599, 35.139), 30.654),
    ((35.669, 46.476), 24.960),
]
PANEL_H, PANEL_R, GAP = 41.000, 1.500, 2.333
CLIP_CHAMFER = 7                # the front card's bottom-left corner is cut at 45 degrees
CLIP = 57.827       # cards are cut flat just above the sofa
GRAD = (44.667, 24.667, 54.667, 57.333)  # cards gradient: orange top-left -> magenta bottom

SOFA_FIT = {"cx": 53.667, "back_cy": 68.333, "back_w": 31.667, "back_h": 13.0, "back_r": 3.333, "arm_dx": 17.375, "arm_cy": 73.583, "arm_w": 6.083, "arm_h": 10.833, "arm_r": 3.333, "arm_ang": 13.0, "base_cy": 76.833, "base_w": 37.167, "base_h": 6.083, "base_r": 2.917, "leg_dx": 14.333, "leg_wt": 2.333, "leg_wb": 1.833, "leg_y0": 80.5, "leg_y1": 83.5, "gap": 1.667}  # symmetric sofa fitted to the concept


def centred_rrect(cx, cy, w, h, r, angle=0.0):
    r = min(r, w / 2, h / 2)
    return rotate(rrect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, r), angle, cx, cy)


def sofa_base(grow=0.0):
    f = SOFA_FIT
    return [centred_rrect(f["cx"] - f["arm_dx"], f["arm_cy"], f["arm_w"] + 2 * grow, f["arm_h"] + 2 * grow, f["arm_r"] + grow, -f["arm_ang"]),
            centred_rrect(f["cx"] + f["arm_dx"], f["arm_cy"], f["arm_w"] + 2 * grow, f["arm_h"] + 2 * grow, f["arm_r"] + grow, f["arm_ang"]),
            # base: fully rounded ends, like the concept's curved underside
            centred_rrect(f["cx"], f["base_cy"], f["base_w"] + 2 * grow, f["base_h"] + 2 * grow, f["base_h"] / 2 + grow)]


def sofa_legs():
    """Short legs with a rounded foot, starting inside the base."""
    f = SOFA_FIT
    w, y0, y1 = f["leg_wt"], f["base_cy"] + f["base_h"] / 2 - 2, f["leg_y1"]
    return [rrect(f["cx"] + side * f["leg_dx"] - w / 2, y0, f["cx"] + side * f["leg_dx"] + w / 2, y1, w / 2) for side in (-1, 1)]


SOFA_BACK_PATH = centred_rrect(SOFA_FIT["cx"], SOFA_FIT["back_cy"], SOFA_FIT["back_w"], SOFA_FIT["back_h"], SOFA_FIT["back_r"])
SOFA_GAP_PATHS = sofa_base(SOFA_FIT["gap"])   # dark margin between the backrest and the arms/base
SOFA_BASE_PATHS = sofa_base() + sofa_legs()
CLIP = SOFA_FIT["back_cy"] - SOFA_FIT["back_h"] / 2 - SOFA_FIT["gap"]  # same dark margin as inside the sofa
FADE_TOP = CLIP - 11                  # cards darken into purple towards the cut, as in the concept
FADE_COLOR, FADE_ALPHA = "#2E0B3C", 0.9


def clip_points():
    """Cards are cut flat above the sofa, with the front card's bottom-left corner chamfered."""
    (fx, _), _ = PANELS[-1]
    x = fx - 9  # just left of where the front card meets the cut
    return [(0, 0), (108, 0), (108, CLIP), (x + CLIP_CHAMFER, CLIP), (x, CLIP - CLIP_CHAMFER), (0, CLIP - CLIP_CHAMFER)]


def panel_path(i, grow=0.0):
    (x, y), w = PANELS[i]
    return rotate(rrect(x - grow, y - grow, x + w + grow, y + PANEL_H + grow, PANEL_R + grow), ANGLE, x, y)


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
    clip = Image.new("L", (S, S), 0); ImageDraw.Draw(clip).polygon([(x * sc, y * sc) for x, y in clip_points()], fill=255)
    for i in range(len(PANELS)):
        if i > 0:  # the margin around a front panel shows the tile over the panel behind
            img.paste(bg, (0, 0), Image.composite(mask([panel_path(i, GAP)], sc, (S, S)), Image.new("L", (S, S), 0), clip))
        m = Image.composite(mask([panel_path(i)], sc, (S, S)), Image.new("L", (S, S), 0), clip)
        layer = panels.copy(); layer.putalpha(m); img.alpha_composite(layer)
        shade = Image.new("RGBA", (S, S), hex2rgb(FADE_COLOR) + (0,))
        ramp = Image.new("L", (S, S), 0); rd = ImageDraw.Draw(ramp)
        for yy in range(int(FADE_TOP * sc), int(CLIP * sc) + 1):
            rd.line((0, yy, S, yy), fill=int(255 * FADE_ALPHA * min(max((yy / sc - FADE_TOP) / (CLIP - FADE_TOP), 0), 1)))
        shade.putalpha(Image.composite(ramp, Image.new("L", (S, S), 0), m)); img.alpha_composite(shade)
    white = Image.new("RGBA", (S, S), hex2rgb(SOFA) + (255,))
    back = white.copy(); back.putalpha(mask([SOFA_BACK_PATH], sc, (S, S))); img.alpha_composite(back)
    if SOFA_GAP_PATHS:
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
    body += '    <group>\n        <clip-path android:pathData="M' + ' L'.join(f"{x:.3f},{y:.3f}" for x, y in clip_points()) + ' Z" />\n'
    for i in range(len(PANELS)):
        if i > 0:  # a margin filled with the tile's own gradient separates it from the panel behind
            body += f'        <path android:pathData="{panel_path(i, GAP)}">\n' + gradient(tile, 0, 0, 108, 108, "            ") + '        </path>\n'
        body += f'        <path android:pathData="{panel_path(i)}">\n' + gradient([PANEL_A, PANEL_M, PANEL_B], *GRAD, "            ") + '        </path>\n'
        body += (f'        <path android:pathData="{panel_path(i)}">\n'
                 '            <aapt:attr name="android:fillColor">\n'
                 f'                <gradient android:type="linear" android:startX="54" android:startY="{FADE_TOP:.3f}" android:endX="54" android:endY="{CLIP:.3f}"\n'
                 f'                    android:startColor="#00{FADE_COLOR[1:]}" android:endColor="#{round(FADE_ALPHA * 255):02X}{FADE_COLOR[1:]}" />\n'
                 '            </aapt:attr>\n        </path>\n')
    body += '    </group>\n'
    body += f'    <path android:fillColor="{SOFA}" android:pathData="{SOFA_BACK_PATH}" />\n'
    if SOFA_GAP_PATHS:
        body += '    <path android:pathData="' + ' '.join(SOFA_GAP_PATHS) + '">\n' + gradient(tile, 0, 0, 108, 108) + '    </path>\n'
    body += f'    <path android:fillColor="{SOFA}" android:pathData="' + ' '.join(SOFA_BASE_PATHS) + '" />\n'
    return head + body + '</vector>\n'


if __name__ == "__main__":
    res, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    pts = [(x, min(y, CLIP)) for d in [panel_path(i) for i in range(len(PANELS))] + SOFA_BASE_PATHS for poly in flatten(d) for x, y in poly]
    print(f"artwork reaches {max(math.hypot(x - 54, y - 54) for x, y in pts):.1f} from the centre (safe zone 33, visible 36)")
    font = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "font", "nunito_extrabold.ttf")
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
