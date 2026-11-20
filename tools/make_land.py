"""Convertit les terres émergées de Natural Earth (ne_50m_land.zip, domaine public) en un petit fichier binaire embarqué.

Usage (le zip est lu tel quel, sans extraction) :
    python -I tools/make_land.py ne_50m_land.zip                      # affiche les mesures pour quelques réglages
    python -I tools/make_land.py ne_50m_land.zip --res 1000 --tol 0.02 --out shared/src/commonMain/composeResources/files/land.bin

Format du fichier (les entiers sont des « varints » : 7 bits par octet, poids faible d'abord, bit 7 = suite) :
    nombre d'anneaux
    pour chaque anneau : nombre de points, puis pour chaque point deux entiers signés (« zigzag ») : écart de longitude
    et de latitude avec le point précédent (le premier est écart par rapport à 0,0), en 1/RES de degré.
Les anneaux ne répètent pas leur premier point à la fin : le lecteur les referme. Les trous (lacs, mers intérieures) sont
des anneaux comme les autres ; le dessin se fait en « pair-impair ».

Natural Earth : domaine public, https://www.naturalearthdata.com/about/terms-of-use/ (« Made with Natural Earth »).
"""
import argparse
import struct
import sys
import zipfile


def read_rings(zip_path):
    """Anneaux (listes de (lon, lat)) de tous les polygones du .shp contenu dans le zip."""
    with zipfile.ZipFile(zip_path) as z:
        names = [n for n in z.namelist() if n.lower().endswith(".shp")]
        if len(names) != 1:
            sys.exit("le zip doit contenir exactement un .shp")
        data = z.read(names[0])
    if struct.unpack(">i", data[0:4])[0] != 9994:
        sys.exit("ce n'est pas un shapefile")
    pos = 100
    rings = []
    while pos + 8 <= len(data):
        _, words = struct.unpack(">ii", data[pos:pos + 8])
        pos += 8
        body = data[pos:pos + words * 2]
        pos += words * 2
        shape = struct.unpack("<i", body[0:4])[0]
        if shape == 0:
            continue
        if shape != 5:
            sys.exit("type de forme %d non géré (polygones attendus)" % shape)
        n_parts, n_points = struct.unpack("<ii", body[36:44])
        parts = list(struct.unpack("<%di" % n_parts, body[44:44 + 4 * n_parts]))
        offset = 44 + 4 * n_parts
        flat = struct.unpack("<%dd" % (2 * n_points), body[offset:offset + 16 * n_points])
        parts.append(n_points)
        for i in range(n_parts):
            ring = [(flat[2 * k], flat[2 * k + 1]) for k in range(parts[i], parts[i + 1])]
            if len(ring) > 1 and ring[0] == ring[-1]:
                ring.pop()
            rings.append(ring)
    return rings


def simplify(points, tolerance):
    """Douglas-Peucker sur une ligne ouverte (tolérance en degrés)."""
    if tolerance <= 0 or len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = points[a], points[b]
        dx, dy = bx - ax, by - ay
        length2 = dx * dx + dy * dy
        far, far_d = -1, tolerance * tolerance
        for i in range(a + 1, b):
            px, py = points[i]
            if length2 == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length2))
                d = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if d > far_d:
                far, far_d = i, d
        if far >= 0:
            keep[far] = True
            stack.append((a, far))
            stack.append((far, b))
    return [p for p, k in zip(points, keep) if k]


def prepare(rings, res, tolerance):
    """Anneaux simplifiés puis arrondis à 1/res de degré ; les anneaux réduits à moins de 3 points disparaissent."""
    out = []
    for ring in rings:
        # Un anneau se simplifie fermé : on y ajoute son premier point pour que l'écart soit mesuré aussi au raccord.
        pts = simplify(ring + [ring[0]], tolerance)[:-1]
        q = []
        for lon, lat in pts:
            p = (round(lon * res), round(lat * res))
            if not q or q[-1] != p:
                q.append(p)
        if len(q) > 1 and q[0] == q[-1]:
            q.pop()
        if len(q) >= 3:
            out.append(q)
    return out


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zigzag(n):
    return (n << 1) if n >= 0 else ((-n << 1) - 1)


def encode(rings):
    out = bytearray(varint(len(rings)))
    for ring in rings:
        out += varint(len(ring))
        px = py = 0
        for x, y in ring:
            out += varint(zigzag(x - px)) + varint(zigzag(y - py))
            px, py = x, y
    return bytes(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip")
    ap.add_argument("--res", type=int, default=1000, help="points par degré (1000 : environ 110 m)")
    ap.add_argument("--tol", type=float, default=0.0, help="tolérance de simplification en degrés (0 : aucune)")
    ap.add_argument("--out")
    args = ap.parse_args()

    rings = read_rings(args.zip)
    print("anneaux lus : %d, points : %d" % (len(rings), sum(len(r) for r in rings)))
    if not args.out:
        print("%8s %8s %9s %9s %10s" % ("res", "tol", "anneaux", "points", "octets"))
        for res, tol in [(1000, 0), (1000, 0.01), (1000, 0.02), (1000, 0.05), (100, 0.02), (100, 0.05), (10000, 0)]:
            r = prepare(rings, res, tol)
            print("%8d %8.3f %9d %9d %10d" % (res, tol, len(r), sum(len(x) for x in r), len(encode(r))))
        return
    r = prepare(rings, args.res, args.tol)
    blob = encode(r)
    with open(args.out, "wb") as f:
        f.write(blob)
    print("écrit %s : %d anneaux, %d points, %d octets (res %d, tol %s)" % (args.out, len(r), sum(len(x) for x in r), len(blob), args.res, args.tol))


if __name__ == "__main__":
    main()
