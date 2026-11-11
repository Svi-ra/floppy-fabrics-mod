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
| `kind` | `"wall"` / `"pole"` / `"drape"` / `"line"` / `"canopy"` | `"wall"` | Wall-mounted cloth, flag on a pole, cloth over furniture, string with pennants or bulbs, fabric over a frame |
| `profile` | `"wall"` / `"pole"` | = kind | Built-in physics profile (`ClothProfiles`) |
| `physics` | object | — | Overrides: `grid` [cols, rows] (defaults wall 5×8, pole 8×5), `mass` kg, `drag` (Cd), `friction` (Cf), `damping`, `stiffness` [stretch, shear, bend], `iterations` (default 4), `pin` (`top`, `left`, `right`, `top_corners`, `left_corners`, `top_sides`, `top_and_bottom_corners`), `restore` (pose memory 1/s; pole default 5), `lodGrid` [cols, rows] for the far LOD (defaults wall 4×6, pole 6×4) |
| `exposure` | number 0–4 | wall 0.35, pole 1 | Fraction of the climate wind the flag feels |
| `timeScale` | number (0–4] | 1 | Simulation seconds per real second |

## Wall flags (`"kind": "wall"`)
The vanilla sprite stays the image; the cloth bends it. At zero wind it is pixel-identical to vanilla.
| Key | Type | Meaning |
|---|---|---|
| `facing` | `"S"` / `"E"` / `"N"` / `"W"` | Which way the flag faces: S lies along the north wall (top edge slopes **down** to the right), E along the west wall (top edge slopes **up** to the right). N/W lie on the square's south/east edge facing into the square (used for curtains) |
| `cloth` | `{x, y, w, h}` px | Top-left corner of the pinned top edge, horizontal span `w`, vertical height `h` (measured at the left edge) |
| `mesh` | [cols, rows] | Render mesh over the sprite rectangle; default [5, 8] (halved at far LOD) |
| `free` | bool | Default false. A cloth hanging in free air on its line (a street or pole banner): no wall plane, touched from both sides, never blocked — as an inset cloth, without moving the line |
| `group` | `{"id": string, "offset": [dx, dy]}` | One tile of a **multi-tile cloth**: every tile names the same id and its offset in squares from the anchor tile; `cloth` (and `topCurve`) are given in the **anchor tile's** frame and must be identical in every tile. All tiles draw one shared simulation, so the cloth cannot come apart at the seams |
| `topCurve` | [[x, y], ...] px | Optional top edge as a curve (x ascending, anchor frame): a banner hung from a sagging cable. Pixels hang below the curve instead of the straight iso top edge |
| `inset` | metres, 0 ≤ inset < 1 | Default 0 (on the wall). Distance of the cloth's line from the facing's edge into the square: S at y = inset, E at x = inset, N at y = 1 − inset, W at x = 1 − inset. Above 0 the cloth hangs **free** (e.g. a hospital curtain on a rail across the middle of its square, 0.5): it is not clamped to a wall plane, bodies on both sides touch it, and it is never blocked by furniture |

Wall flags only feel the wind on an **outside** square (vanilla's `exterior` flag); indoors they still move when people,
zombies or vehicles push them.

Window curtains need no entry: any sprite with a `curtainN/S/W/E` tile type is measured automatically (DECISIONS D64).
A `flags.json` entry for a curtain sprite turns it into an ordinary wall flag instead.

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

For a cloth under a rail or rod drawn in the same sprite, measure the cloth below the rail: pixels above the pinned top
edge are never moved, so the rail stays still.

## Drapes (`"kind": "drape"`)
A wall cloth lying over a piece of furniture and hanging down one of its faces (the altar cloths). Same fields as a wall
flag; `inset` places the face inside the square. The face is the plane the cloth cannot pass (unlike a free-hanging
cloth), it is never blocked, and only the sprite's columns between the cloth's left and right edges move — the furniture
drawn beside it stays still.

## Strings (`"kind": "line"`)
| Key | Type | Meaning |
|---|---|---|
| `line.a`, `line.b` | [x, y, z] m | End points relative to the object's square (x east, y south from its north corner, z up from its floor). Every tile drawing part of one string lists the same string with its own offset; equal absolute end points (to the centimetre) share one simulation |
| `line.sag` | m | How far the middle hangs below the straight line a–b |
| `line.type` | `"bunting"` / `"bulbs"` / `"fairy"` | Pennants (swing about the string, plate drag), festoon bulbs on sockets, tiny bulbs on the wire |
| `line.hang` | m | Pendant hang length (pennant height; socket + bulb) |
| `exposure` | 0–4 | Default 0.6 (outside squares only) |
| `mesh` | [cols, rows] | Default [16, 10] |

## Canopies (`"kind": "canopy"`)
| Key | Type | Meaning |
|---|---|---|
| `canopy.columns` | [[x, top, pivot, bottom], ...] | Fabric outline per column, x ascending (2x px). Panel = top..pivot (held by the frame on every edge), valance = pivot..bottom (pivot = bottom: no valance there) |
| `canopy.normal` | [x, y, z] | Panel outward normal (world) |
| `canopy.out` | [x, y] | The hem's outward horizontal direction at its middle |
| `canopy.spread` | degrees | How far that direction turns from one end of the hem to the other (round canopies) |
| `canopy.size` | [w, d] m | Panel width along the hem and depth |
| `canopy.flaps` | int | Valance flap nodes (0 = no valance) |
| `canopy.valance` | m | Valance height |
| `canopy.type` | `"awning"` | Physical profile (`Canopy.AWNING`) |
| `exposure` | 0–4 | Default 0.7 |
| `mesh` | [cols, rows] | Default [12, 22] |

## Example
```json
{ "version": 2, "flags": [
  { "sprite": "walls_decoration_01_16", "kind": "wall", "facing": "S",
    "cloth": { "x": 66, "y": 35, "w": 52, "h": 90 }, "exposure": 0.35 },
  { "sprite": "location_community_medical_01_41", "kind": "wall", "facing": "S", "profile": "curtain", "inset": 0.5,
    "cloth": { "x": 32, "y": 34, "w": 64, "h": 174 } }
]}
```
