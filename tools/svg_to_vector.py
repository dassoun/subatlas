"""Convertit le logo Sensitive Mobility (SVG) en dessins vectoriels Android.

Produit deux fichiers :
  - logo.xml            : logo complet (anneau, courbe, points, texte), fond transparent, pour l'écran de démarrage Compose.
  - ic_splash_mark.xml  : symbole seul (sans texte), cadré pour l'icône de l'écran de démarrage système d'Android.
Le SVG est un dessin simple (arcs, courbes, cercles, chemins de texte) : ses données de tracé sont reprises telles quelles.
"""
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
SRC = PROJECT / "design" / "sensitive-mobility-logo.svg"
NS = {"s": "http://www.w3.org/2000/svg"}

root = ET.parse(SRC).getroot()
paths = root.findall("s:path", NS)
circles = root.findall("s:circle", NS)
assert len(paths) == 4 and len(circles) == 2, (len(paths), len(circles))
ring, curve, text_white, text_blue = paths


def num(v):
    return f"{v:.3f}".rstrip("0").rstrip(".")


def shorten(d):
    """Arrondit les nombres à 3 décimales : les données d'origine en ont 15."""
    return re.sub(r"-?\d+\.\d+", lambda m: num(float(m.group(0))), d)


# --- Boîtes englobantes, pour convertir les dégradés « objectBoundingBox » en coordonnées absolues -----------------

def arc_bbox(d):
    m = re.match(r"M\s*([\d.]+)\s+([\d.]+)\s*A\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(\d)\s+(\d)\s+([\d.]+)\s+([\d.]+)", d)
    x1, y1, r, _, _, large, sweep, x2, y2 = (float(g) for g in m.groups())
    # Conversion « extrémités -> centre » d'un arc SVG circulaire (rayon r, sans rotation).
    mx, my = (x1 - x2) / 2, (y1 - y2) / 2
    coef = math.sqrt(max(r * r / (mx * mx + my * my) - 1, 0))
    if large == sweep:
        coef = -coef
    cx = coef * my + (x1 + x2) / 2
    cy = -coef * mx + (y1 + y2) / 2
    a1 = math.atan2(y1 - cy, x1 - cx)
    a2 = math.atan2(y2 - cy, x2 - cx)
    if sweep == 1 and a2 <= a1:
        a2 += 2 * math.pi
    if sweep == 0 and a2 >= a1:
        a2 -= 2 * math.pi
    pts = [(cx + r * math.cos(a1 + (a2 - a1) * i / 400), cy + r * math.sin(a1 + (a2 - a1) * i / 400)) for i in range(401)]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return (min(xs), min(ys), max(xs), max(ys)), (cx, cy, r)


def points_bbox(d):
    """Boîte englobante (points de contrôle inclus) d'un tracé fait de M, H, V, L, Q, Z en absolu."""
    tokens = re.findall(r"[MHVLQZ]|-?\d+\.?\d*", d)
    x = y = 0.0
    xs, ys, i, cmd = [], [], 0, None
    while i < len(tokens):
        t = tokens[i]
        if t in "MHVLQZ":
            cmd = t
            i += 1
            if cmd == "Z":
                continue
        if cmd in ("M", "L"):
            x, y = float(tokens[i]), float(tokens[i + 1]); i += 2
        elif cmd == "H":
            x = float(tokens[i]); i += 1
        elif cmd == "V":
            y = float(tokens[i]); i += 1
        elif cmd == "Q":
            xs += [float(tokens[i]), float(tokens[i + 2])]; ys += [float(tokens[i + 1]), float(tokens[i + 3])]
            x, y = float(tokens[i + 2]), float(tokens[i + 3]); i += 4
        xs.append(x); ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


(ring_bbox, (ring_cx, ring_cy, ring_r)) = arc_bbox(ring.get("d"))
blue_bbox = points_bbox(text_blue.get("d"))

# --- Blocs XML ----------------------------------------------------------------------------------------------

def gradient(attr, stops, x1, y1, x2, y2, indent):
    pad = " " * indent
    items = "\n".join(f'{pad}        <item android:color="{c}" android:offset="{o}" />' for o, c in stops)
    return (
        f'{pad}<aapt:attr name="{attr}">\n'
        f'{pad}    <gradient\n'
        f'{pad}        android:endX="{num(x2)}"\n'
        f'{pad}        android:endY="{num(y2)}"\n'
        f'{pad}        android:startX="{num(x1)}"\n'
        f'{pad}        android:startY="{num(y1)}"\n'
        f'{pad}        android:type="linear">\n'
        f'{items}\n'
        f'{pad}    </gradient>\n'
        f'{pad}</aapt:attr>'
    )


def circle_path(c):
    cx, cy, r = float(c.get("cx")), float(c.get("cy")), float(c.get("r"))
    return f"M{num(cx - r)},{num(cy)}a{num(r)},{num(r)} 0,1 1,{num(2 * r)},0a{num(r)},{num(r)} 0,1 1,{num(-2 * r)},0z"


bx0, by0, bx1, by1 = ring_bbox
ring_grad = gradient(
    "android:strokeColor",
    [("0", "#2EE6D0"), ("0.5", "#3BB8F0"), ("1", "#2F86F5")],
    bx0 + 0.7 * (bx1 - bx0), by0, bx0 + 0.2 * (bx1 - bx0), by1, 8,
)
curve_grad = gradient("android:strokeColor", [("0", "#FFFFFF"), ("1", "#3FE0D0")], 353, 501, 696, 282, 8)
mid_y = (blue_bbox[1] + blue_bbox[3]) / 2
blue_grad = gradient("android:fillColor", [("0", "#1E90FF"), ("1", "#3BA4FF")], blue_bbox[0], mid_y, blue_bbox[2], mid_y, 8)


def symbol_paths(indent):
    pad = " " * indent
    out = (
        f'{pad}<path\n{pad}    android:pathData="{shorten(ring.get("d"))}"\n{pad}    android:strokeLineCap="round"\n'
        f'{pad}    android:strokeWidth="{ring.get("stroke-width")}">\n{ring_grad}\n{pad}</path>\n'
        f'{pad}<path\n{pad}    android:pathData="{shorten(curve.get("d"))}"\n{pad}    android:strokeLineCap="round"\n'
        f'{pad}    android:strokeWidth="{curve.get("stroke-width")}">\n{curve_grad}\n{pad}</path>\n'
    )
    for c in circles:
        out += f'{pad}<path\n{pad}    android:fillColor="{c.get("fill")}"\n{pad}    android:pathData="{circle_path(c)}" />\n'
    return out


HEADER = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:aapt="http://schemas.android.com/aapt"\n'
    '    android:width="{w}dp"\n    android:height="{w}dp"\n'
    '    android:viewportWidth="{v}"\n    android:viewportHeight="{v}">\n'
)

# Logo en deux calques de même cadre (le rectangle #08111C du SVG est retiré : l'écran de démarrage fournit le fond).
# Séparés pour que l'écran de démarrage de l'appli affiche le symbole tout de suite, comme l'écran système, et ne fasse
# apparaître le nom qu'en fondu.
logo_symbol = HEADER.format(w=1024, v=1024) + "    <!-- Sensitive Mobility : symbole. -->\n" + symbol_paths(4) + "</vector>\n"
logo_wordmark = (
    HEADER.format(w=1024, v=1024)
    + "    <!-- Sensitive Mobility : nom. -->\n"
    + f'    <path\n        android:fillColor="{text_white.get("fill")}"\n        android:pathData="{shorten(text_white.get("d"))}" />\n'
    + f'    <path\n        android:pathData="{shorten(text_blue.get("d"))}">\n{blue_grad}\n    </path>\n'
    + "</vector>\n"
)

# Symbole seul, centré sur l'anneau, dans le cadre de 288 dp de l'icône d'écran de démarrage Android.
# Android n'affiche que le disque central de 2/3 (192 dp) : l'anneau (diamètre visé : 150 dp) y tient largement.
CANVAS = 288.0
target_diameter = 150.0
scale = target_diameter / (2 * ring_r)
tx, ty = CANVAS / 2 - ring_cx * scale, CANVAS / 2 - ring_cy * scale
mark = HEADER.format(w=288, v=288) + "    <!-- Symbole seul pour l'écran de démarrage système : Android le découpe dans un disque. -->\n"
mark += f'    <group\n        android:scaleX="{num(scale)}"\n        android:scaleY="{num(scale)}"\n        android:translateX="{num(tx)}"\n        android:translateY="{num(ty)}">\n'
mark += symbol_paths(8) + "    </group>\n</vector>\n"

if __name__ == "__main__":
    drawables = PROJECT / "shared/src/commonMain/composeResources/drawable"
    out_mark = PROJECT / "androidApp/src/main/res/drawable/ic_splash_mark.xml"
    for p in (drawables / "logo_symbol.xml", out_mark):
        p.parent.mkdir(parents=True, exist_ok=True)
    (drawables / "logo_symbol.xml").write_text(logo_symbol, encoding="utf-8")
    (drawables / "logo_wordmark.xml").write_text(logo_wordmark, encoding="utf-8")
    (drawables / "logo.xml").unlink(missing_ok=True)  # ancienne version en un seul calque
    out_mark.write_text(mark, encoding="utf-8")
    # Valeurs à reprendre dans SplashScreen.kt pour que le symbole coïncide avec celui de l'écran système.
    print(f"taille du cadre du logo (dp) = {1024 * scale:.1f} ; décalage vertical (dp) = {(512 - ring_cy) * scale:.1f}")
    design = PROJECT / "design/sensitive-mobility-logo.svg"
    design.write_text(SRC.read_text(encoding="utf-8"), encoding="utf-8")
    print(f"anneau: centre=({ring_cx:.1f},{ring_cy:.1f}) r={ring_r} bbox={tuple(round(v,1) for v in ring_bbox)}")
    print(f"MOBILITY bbox={tuple(round(v,1) for v in blue_bbox)}")
    print(f"scale symbole={scale:.3f} translate=({tx:.1f},{ty:.1f})")
    print(f"logo_symbol {len(logo_symbol)//1024} Ko, logo_wordmark {len(logo_wordmark)//1024} Ko, ic_splash_mark {len(mark)//1024} Ko")
