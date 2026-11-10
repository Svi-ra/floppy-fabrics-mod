#!/usr/bin/env python3
"""decotool.py - generates mod/42/media/realflags/decorations.json (strings, canopies, drapes) from the game's sprites.
Dev tool, never shipped with the mod. Requires Pillow and numpy.

    python tools/decotool.py "<PZ>/media/texturepacks/Tiles2x.pack" mod/42/media/realflags/decorations.json [--png out/]

Everything is measured in the 2x tile frame (128x256), the coordinate system of flags.json (docs/FLAGS_JSON.md):

* Strings (bunting, string lights; DECISIONS D80): every vanilla string spans two tiles along a wall edge (SpriteGridPos
  (0,0)+(1,0) along the north edge, (0,0)+(0,1) along the west edge). The top of the opaque pixels of both tiles, mapped
  onto the edge line, is fitted with a parabola z(u); its ends give the string's end heights and its middle the sag.
  Each tile lists the whole string relative to its own square, so both find one shared simulation.
* Canopies (D81): the fabric outline per 4-px column (top, bottom) from the alpha mask after a 5x5 opening, which
  removes the frame's thin bars and legs. The valance is the band of `h` px above the bottom, only along the hem: for
  straight canopies the run of the outline that follows the hem's iso slope (+0.5 along x, -0.5 along y), for round
  ones the low part of the outline (within 28 px of its lowest point, |slope| < 0.9; 1.6 for quarter rounds).
  The valance's top is a smooth line h px above the scallop tips. Back views of plain canopies show no valance.
* Drapes (D82): the altar cloths, measured by hand (see DECISIONS D82) and written as they are.
"""
import io
import json
import os
import struct
import sys

import numpy as np
from PIL import Image

LEVEL_PX = 32 * 6 ** 0.5  # 2x frame px per metre of height (78.38)


def read_pack(pack, names):
    want = set(names)
    out = {}
    with open(pack, 'rb') as f:
        i = lambda: struct.unpack('<i', f.read(4))[0]
        s = lambda: f.read(i()).decode('utf-8', 'replace')
        if f.read(4) != b'PZPK':
            sys.exit('not a PZPK texture pack: ' + pack)
        i()
        for _ in range(i()):
            s()
            n = i()
            i()
            entries = [(s(), struct.unpack('<8i', f.read(32))) for _ in range(n)]
            length = i()
            hits = [e for e in entries if e[0] in want]
            if not hits:
                f.seek(length, 1)
                continue
            img = Image.open(io.BytesIO(f.read(length))).convert('RGBA')
            for name, (x, y, w, h, ox, oy, fw, fh) in hits:
                frame = Image.new('RGBA', (fw, fh))
                frame.paste(img.crop((x, y, x + w, y + h)), (ox, oy))
                out[name] = frame
    missing = want - set(out)
    if missing:
        sys.exit('not found in pack: ' + ', '.join(sorted(missing)))
    return out


# ---------------------------------------------------------------- strings

W = 'walls_decoration_01_'
B = 'location_restaurant_bar_smutz_'
# (tile at grid 0 along x, tile at grid 1 along x, tile at grid 0 along y, tile at grid 1 along y, type, hang m)
STRINGS = [
    (W + '64', W + '65', W + '67', W + '66', 'bunting', 0.22),
    (W + '72', W + '73', W + '75', W + '74', 'bunting', 0.22),
    (W + '80', W + '81', W + '83', W + '82', 'bunting', 0.22),
    (B + '32', B + '33', B + '35', B + '34', 'bunting', 0.22),
    (W + '76', W + '77', W + '79', W + '78', 'bulbs', 0.10),
    (W + '96', W + '97', W + '99', W + '98', 'bulbs', 0.10),
    (W + '88', W + '89', W + '91', W + '90', 'fairy', 0.03),
]


def fit_string(imgs, axis):
    pts = []
    for off, img in enumerate(imgs):
        a = np.array(img)[:, :, 3] > 60
        for x in range(a.shape[1]):
            ys = np.nonzero(a[:, x])[0]
            if len(ys) == 0:
                continue
            d = (x - 64) / 64 if axis == 'x' else (64 - x) / 64
            floor = 192 + 32 * d
            pts.append((off + d, (floor - ys.min()) / LEVEL_PX))
    p = np.array(pts)
    c = np.polyfit(p[:, 0], p[:, 1], 2)
    za, zb = float(np.polyval(c, 0.0)), float(np.polyval(c, 2.0))
    sag = (za + zb) / 2 - float(np.polyval(c, 1.0))
    return round(za, 3), round(zb, 3), round(max(0.02, sag), 3)


def string_entries(img):
    out = []
    for x0, x1, y0, y1, kind, hang in STRINGS:
        za, zb, sag = fit_string([img[x0], img[x1]], 'x')
        out.append(line(x0, [0, 0, za], [2, 0, zb], sag, kind, hang, 'S string over two tiles: this tile and the next east'))
        out.append(line(x1, [-1, 0, za], [1, 0, zb], sag, kind, hang, None))
        za, zb, sag = fit_string([img[y0], img[y1]], 'y')
        out.append(line(y0, [0, 0, za], [0, 2, zb], sag, kind, hang, 'E string over two tiles: this tile and the next south'))
        out.append(line(y1, [0, -1, za], [0, 1, zb], sag, kind, hang, None))
    return out


def line(sprite, a, b, sag, kind, hang, comment):
    e = {'sprite': sprite, 'kind': 'line'}
    if comment:
        e['comment'] = comment
    e['line'] = {'a': a, 'b': b, 'sag': sag, 'type': kind, 'hang': hang}
    return e


# ---------------------------------------------------------------- canopies

F = lambda *ids: [W + str(i) for i in ids]
# family: wall (awning on a wall), flat (sloped panel on a frame), back (flat panel seen from behind: no valance shown),
# cone (half-round, hem all along the front), quarter (quarter-round), curvedback (round, seen from behind: thin),
# arch (barrel arch, straight hem), archback (arch from behind, its far hem shows), domeback (dome from behind, hem shows)
CANOPIES = [
    ('wall', 'S', F(68, 70, 112, 114, 120, 122) + [B + '12', 'location_restaurant_pie_01_4', 'location_restaurant_pileocrepe_01_4']),
    ('wall', 'E', F(69, 71, 113, 115, 121, 123) + [B + '13', B + '14', B + '15', 'location_restaurant_pie_01_5', 'location_restaurant_pileocrepe_01_5']),
    ('flat', 'S', F(84, 100, 116, 128, 132)),
    ('flat', 'E', F(85, 101, 117, 129, 133)),
    ('back', 'N', F(92, 108, 124, 136, 140)),
    ('back', 'W', F(93, 109, 125, 137, 141)),
    ('cone', 'S', F(86, 102, 118, 130, 134)),
    ('quarter', 'W', F(87, 103, 119, 131, 135)),
    ('quarter', 'E', F(94, 110, 126, 138, 142)),
    ('curvedback', 'N', F(95, 111, 127, 139, 143)),
    ('arch', 'S', F(144, 152, 160)),
    ('arch', 'E', F(145, 153, 161)),
    ('archback', 'W', F(148, 156, 164)),
    ('archback', 'N', F(149, 157, 165)),
    ('cone', 'S', F(146, 154, 162)),
    ('quarter', 'E', F(147, 155, 163)),
    ('quarter', 'W', F(150, 158, 166)),
    ('domeback', 'N', F(151, 159, 167)),
]
OUT = {'S': (0, 1), 'E': (1, 0), 'N': (0, -1), 'W': (-1, 0)}
VALANCE_PX = {'wall': 16, 'flat': 16, 'cone': 14, 'quarter': 14, 'arch': 18, 'archback': 18, 'domeback': 16}
SPREAD = {'cone': 180, 'domeback': 180, 'quarter': 90}
TILT = {'wall': 30, 'flat': 35, 'back': 35, 'arch': 30, 'archback': 30}


def erode(m, r):
    o = m.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            o &= np.roll(np.roll(m, dy, 0), dx, 1)
    return o


def dilate(m, r):
    o = m.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            o |= np.roll(np.roll(m, dy, 0), dx, 1)
    return o


def outline(img, step=4):
    a = np.array(img)[:, :, 3] > 60
    o = dilate(erode(a, 2), 2) & a
    xs = np.nonzero(o.sum(0) >= 3)[0]
    x0, x1 = int(xs.min()), int(xs.max())
    sample = list(range(x0, x1 + 1, step))
    if sample[-1] != x1:
        sample.append(x1)
    cols = []
    for x in sample:
        ys = np.nonzero(o[:, x])[0]
        if len(ys) >= 3:
            cols.append([x, int(ys.min()), int(ys.max())])
    return cols


def hem_run(cols, family, facing):
    """Indices of the columns that carry the valance (largest contiguous run satisfying the family's rule)."""
    if family in ('back', 'curvedback') or not cols:
        return set()
    xs = np.array([c[0] for c in cols], float)
    bot = np.array([c[2] for c in cols], float)
    # slope of a straight-line fit over +-3 columns (+-12 px): scallops and 4-px steps average out
    slope = np.zeros(len(bot))
    for i in range(len(bot)):
        lo, hi = max(0, i - 3), min(len(bot), i + 4)
        slope[i] = np.polyfit(xs[lo:hi], bot[lo:hi], 1)[0] if hi - lo >= 3 else 0.0
    if family in ('cone', 'domeback', 'quarter'):
        # the hem of a round canopy is the low part of its outline (the straight panel sides rise steeply from it);
        # a quarter round's hem turns steeply down toward its far end, hence the wider slope limit
        limit = 1.6 if family == 'quarter' else 0.9
        ok = (np.abs(slope) < limit) & (bot >= bot.max() - 28)
    else:
        want = 0.5 if facing in ('S', 'N') else -0.5
        ok = np.abs(slope - want) < 0.33
    best, cur = [], []
    for i, flag in enumerate(ok):
        if flag:
            cur.append(i)
        else:
            if len(cur) > len(best):
                best = cur
            cur = []
    if len(cur) > len(best):
        best = cur
    return set(best) if len(best) >= 3 else set()


def canopy_entry(sprite, img, family, facing):
    cols = outline(img)
    hem = hem_run(cols, family, facing)
    h = VALANCE_PX.get(family, 0)
    # the valance's top is a smooth line h px above the scallop tips (the lower envelope), not a copy of the scallops
    bots = [c[2] for c in cols]
    env = [max(bots[max(0, i - 3):i + 4]) for i in range(len(bots))]
    columns = []
    for i, (x, top, bot) in enumerate(cols):
        piv = min(bot, max(top, env[i] - h)) if i in hem else bot
        columns.append([x, top, piv, bot])
    ox, oy = OUT[facing]
    if family in TILT:
        t = np.radians(TILT[family])
        normal = [round(ox * np.sin(t), 3), round(oy * np.sin(t), 3), round(float(np.cos(t)), 3)]
    else:
        normal = [round(ox * 0.42, 3), round(oy * 0.42, 3), 0.907]
    spread = SPREAD.get(family, 0)
    if facing in ('S', 'E'):
        spread = -spread
    size = [1.0, 0.6] if family == 'wall' else [1.0, 1.0]
    c = {'columns': columns, 'normal': normal, 'out': [ox, oy], 'spread': spread, 'size': size,
         'flaps': 8 if hem else 0, 'valance': round(h / LEVEL_PX, 3), 'type': 'awning'}
    return {'sprite': sprite, 'kind': 'canopy', 'facing': facing, 'family': family, 'canopy': c}


# ---------------------------------------------------------------- drapes (measured by hand, DECISIONS D82)

DRAPES = [
    {'sprite': 'location_community_church_small_01_40', 'kind': 'drape', 'facing': 'E', 'inset': 0.52,
     'comment': 'Altar cloth hanging down the altar face; only the cloth columns move (D82).',
     'cloth': {'x': 42, 'y': 168, 'w': 26, 'h': 60}, 'mesh': [6, 10], 'exposure': 0.35},
    {'sprite': 'location_community_church_small_01_41', 'kind': 'drape', 'facing': 'E', 'inset': 0.0,
     'cloth': {'x': 6, 'y': 149, 'w': 26, 'h': 62}, 'mesh': [6, 10], 'exposure': 0.35},
    {'sprite': 'location_community_church_small_01_42', 'kind': 'drape', 'facing': 'S', 'inset': 0.0,
     'cloth': {'x': 93, 'y': 134, 'w': 28, 'h': 64}, 'mesh': [6, 10], 'exposure': 0.35},
    {'sprite': 'location_community_church_small_01_43', 'kind': 'drape', 'facing': 'S', 'inset': 0.54,
     'cloth': {'x': 57, 'y': 155, 'w': 25, 'h': 64}, 'mesh': [6, 10], 'exposure': 0.35},
]


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    pack, dst = sys.argv[1], sys.argv[2]
    png = sys.argv[4] if len(sys.argv) > 4 and sys.argv[3] == '--png' else None
    names = {n for s in STRINGS for n in s[:4]} | {n for _, _, ns in CANOPIES for n in ns}
    img = read_pack(pack, names)
    if png:
        os.makedirs(png, exist_ok=True)
        for n, im in img.items():
            im.save(os.path.join(png, n + '.png'))
    entries = string_entries(img)
    for family, facing, sprites in CANOPIES:
        for s in sprites:
            entries.append(canopy_entry(s, img[s], family, facing))
    entries += DRAPES
    doc = {'version': 2,
           'comment': 'Generated by tools/decotool.py from Tiles2x.pack (build 42.21.0): strings (D80), canopies (D81), '
                      'drapes (D82). Pixel coordinates in the 2x tile frame; metres relative to the object\'s square.',
           'flags': entries}
    with open(dst, 'w', encoding='utf-8', newline='\n') as f:
        f.write('{\n  "version": 2,\n  "comment": ' + json.dumps(doc['comment']) + ',\n  "flags": [\n')
        f.write(',\n'.join('    ' + json.dumps(e, separators=(', ', ': ')) for e in entries))
        f.write('\n  ]\n}\n')
    kinds = {}
    for e in entries:
        kinds[e['kind']] = kinds.get(e['kind'], 0) + 1
    print('wrote', dst, len(entries), 'entries', kinds)
    for e in entries:
        if e['kind'] == 'canopy':
            print('  %-42s %-10s %s flaps=%d' % (e['sprite'], e['family'], e['facing'], e['canopy']['flaps']))


if __name__ == '__main__':
    main()
