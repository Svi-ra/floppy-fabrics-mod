# flags.json reference (schema v2)

Location: `media/realflags/*.json` in **any active mod** (its `common/` or version folder). Real Flags ships
`42/media/realflags/flags.json`; other mods add or override flags by shipping their own files. All files are merged
in mod load order, files sorted by name; for the same sprite a later file wins. Each file is one object with a
`"flags"` array, each entry describing one **sprite** (tile). Unknown keys are ignored; a bad entry is logged
(`[RealFlags] WARN …`) and skipped, and the rest load.

## Units
- **Pixels**: the **2× tile frame** — 128 × 256 px, origin top-left, exactly as Tiles2x sprites are laid out — whatever
  texture resolution the player uses (1× frames are scaled).
- **Metres**: 1 tile = 1 m; one floor level = √6 ≈ 2.449 m. On screen at 2× scale: 64 px per metre along a wall, 78.4 px
  per metre of height (`Projection.java`).

## Common fields
| Key | Type | Default | Meaning |
|---|---|---|---|
| `sprite` | string | — (required) | Sprite name, e.g. `walls_decoration_01_16` |
| `kind` | `"wall"` / `"pole"` | `"wall"` | Wall-mounted drape, or flag on a pole |
| `profile` | `"wall"` / `"pole"` | = kind | Built-in physics profile (`ClothProfiles`) |
| `physics` | object | — | Overrides: `grid` [cols, rows] (defaults wall 5×8, pole 8×5), `mass` kg, `drag` (Cd), `friction` (Cf), `damping`, `stiffness` [stretch, shear, bend], `iterations` (default 4), `pin` (`top`, `left`, `right`, `top_corners`, `left_corners`), `restore` (pose memory 1/s; pole default 5), `lodGrid` [cols, rows] for the far LOD (defaults wall 4×6, pole 6×4) |
| `exposure` | number 0–4 | wall 0.35, pole 1 | Fraction of the climate wind the flag feels |
| `timeScale` | number (0–4] | 1 | Simulation seconds per real second |

## Wall flags (`"kind": "wall"`)
The vanilla sprite stays the image; the cloth bends it. At zero wind it is pixel-identical to vanilla.
| Key | Type | Meaning |
|---|---|---|
| `facing` | `"S"` / `"E"` | Which way the flag faces: S lies along the north wall (top edge slopes **down** to the right), E along the west wall (top edge slopes **up** to the right) |
| `cloth` | `{x, y, w, h}` px | Top-left corner of the pinned top edge, horizontal span `w`, vertical height `h` (measured at the left edge) |
| `mesh` | [cols, rows] | Render mesh over the sprite rectangle; default [5, 8] (halved at far LOD) |

Wall flags only move on an **outside** square (vanilla's `exterior` flag).

How to measure `cloth`: `python tools/flagtool.py extract <Tiles2x.pack> out <sprite>` then
`python tools/flagtool.py measure out/<sprite>.png` prints a ready entry. By hand: in the 2× frame, the left-most
opaque column's top pixel → `x, y`; `w` = opaque column span; `h` = left column's opaque height. The top edge must
slope ±0.5 (iso wall): down to the right = S, up to the right = E.

## Pole flags (`"kind": "pole"`)
The pole is rebuilt from static quads; the cloth is a separate canvas texture (e.g. from DGVFlags) simulated hanging
from the pole.
| Key | Type | Meaning |
|---|---|---|
| `anchor` | `{x, y}` px | Top of the hoist on the pole axis |
| `depthPoint` | `{x, y}` px | A point on the shaft whose depth the canvas uses (default = anchor) |
| `size` | [fly, hoist] m | Canvas size: fly length × hoist height |
| `canvas.texture` | string | Path of a PNG in any active mod, e.g. `media/dgv_flags/UnitedStates_0_0_256x143.png` (no `..`) |
| `canvas.uv` | [u0, v0, u1, v1] | Part of the texture that is cloth (e.g. exclude a rod strip) |
| `staticQuads` | array | Unanimated art: `{ "sprite"?: name, "src": [x0,y0,x1,y1], "dst": [x0,y0,x1,y1] }` — `src` of `sprite`'s texture (default: this flag's own sprite) drawn at `dst` of this frame. Keep `src` inside the sprite's opaque bounds. |

If the canvas texture is missing (mod not enabled) the flag draws as vanilla and one WARN is logged.

## Example
```json
{ "version": 2, "flags": [
  { "sprite": "walls_decoration_01_16", "kind": "wall", "facing": "S",
    "cloth": { "x": 66, "y": 35, "w": 52, "h": 90 }, "exposure": 0.35 }
]}
```
