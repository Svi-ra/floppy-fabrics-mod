#!/usr/bin/env python3
"""
flagtool.py — helpers for adding flags to Floppy Fabrics by data (flags.json). Dev tool, never shipped with the mod.

  extract  Pull named sprites out of a Project Zomboid texture pack (.pack, "PZPK" v1) as PNGs in the full tile frame
           (2x packs give 128x256 frames — the coordinate system flags.json uses), and print their atlas coordinates.

           python flagtool.py extract "<PZ>/media/texturepacks/Tiles2x.pack" out/ walls_decoration_01_16 street_decoration_01_16

  measure  Read an extracted *wall flag* PNG (2x frame) and print a suggested flags.json entry: the cloth
           parallelogram {x, y, w, h} and the facing (S if the top edge slopes down to the right, E if it slopes up).
           Always eyeball the result; draped art can need a few pixels of adjustment.

           python flagtool.py measure out/walls_decoration_01_16.png

Requires Pillow (pip install pillow). Pack format verified against Tiles2x.pack of build 42.21.0:
"PZPK", int version, int pageCount; per page: string name, int entryCount, int hasAlpha,
entries {string name, int x, y, w, h, offsetX, offsetY, frameW, frameH}, int pngLength, PNG bytes.
"""
import io
import json
import os
import struct
import sys

from PIL import Image


def _i(f):
    return struct.unpack('<i', f.read(4))[0]


def _s(f):
    n = _i(f)
    return f.read(n).decode('utf-8', 'replace')


def extract(pack, out, names):
    want = set(names)
    os.makedirs(out, exist_ok=True)
    with open(pack, 'rb') as f:
        if f.read(4) != b'PZPK':
            sys.exit('not a PZPK texture pack: ' + pack)
        _i(f)  # version
        pages = _i(f)
        for _ in range(pages):
            page = _s(f)
            n = _i(f)
            _i(f)  # hasAlpha
            entries = [(_s(f), struct.unpack('<8i', f.read(32))) for _ in range(n)]
            length = _i(f)
            hits = [e for e in entries if e[0] in want]
            if not hits:
                f.seek(length, 1)
                continue
            img = Image.open(io.BytesIO(f.read(length))).convert('RGBA')
            for name, (x, y, w, h, ox, oy, fw, fh) in hits:
                frame = Image.new('RGBA', (fw, fh))
                frame.paste(img.crop((x, y, x + w, y + h)), (ox, oy))
                frame.save(os.path.join(out, name + '.png'))
                print(f'{name}\tpage={page}\tatlas x,y,w,h={x},{y},{w},{h}\toffset={ox},{oy}\tframe={fw}x{fh}')
                want.discard(name)
            if not want:
                break
    for name in sorted(want):
        print('NOT FOUND', name)


def measure(png):
    im = Image.open(png).convert('RGBA')
    w, h = im.size
    if (w, h) != (128, 256):
        print(f'warning: frame is {w}x{h}; flags.json uses the 2x frame (128x256) — extract from Tiles2x.pack')
    px = im.load()
    opaque = lambda x, y: px[x, y][3] > 40
    cols = [x for x in range(w) if any(opaque(x, y) for y in range(h))]
    if len(cols) < 6:
        sys.exit('sprite too narrow to measure')
    x0, x1 = cols[0], cols[-1]
    top = lambda x: min(y for y in range(h) if opaque(x, y))
    bot = lambda x: max(y for y in range(h) if opaque(x, y))
    xl, xr = x0 + 2, x1 - 2  # skip anti-aliased edge columns
    slope = (top(xr) - top(xl)) / (xr - xl)
    facing = 'S' if slope > 0 else 'E'
    entry = {
        'sprite': os.path.splitext(os.path.basename(png))[0],
        'kind': 'wall',
        'facing': facing,
        'cloth': {'x': x0, 'y': top(xl), 'w': x1 - x0 + 1, 'h': bot(xl) - top(xl)},
        'exposure': 0.35,
    }
    print(f'top-edge slope {slope:+.3f} (expect about +0.5 for S, -0.5 for E)')
    if abs(abs(slope) - 0.5) > 0.12:
        print('warning: slope is not close to ±0.5 — is this really a wall-mounted flag? check the numbers by hand')
    print(json.dumps(entry, indent=2))


if __name__ == '__main__':
    if len(sys.argv) >= 4 and sys.argv[1] == 'extract':
        extract(sys.argv[2], sys.argv[3], sys.argv[4:])
    elif len(sys.argv) == 3 and sys.argv[1] == 'measure':
        measure(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
