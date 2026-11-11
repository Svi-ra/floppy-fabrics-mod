# Real Flags — wind-reactive cloth for Project Zomboid flags (Build 42)

Vanilla flags react to the wind with a real-time cloth simulation: wall flags billow off their walls, and the American
Flag pole flies a real flag that turns with the wind and droops when it's calm. Flags also meet the world: players,
zombies and vehicles push them, passing cars and running people blow them about, they stop at walls and drape over
furniture, and a wall flag with something tall (over 1.5 m) standing in front of it stays still. **Window curtains** move
too, but only while their window is open or broken: the wind blows them into the room and people brushing past push
them; behind a closed window they stay exactly vanilla. **Fabric decorations** (0.4.0) move as well: church banners, the
Fort Knox flags, the white bar banner, hospital privacy curtains and theatre stage curtains. Indoors there is no wind, so
they only sway when someone walks into them; hospital and theatre curtains hang free across the middle of their square
and can be pushed from either side. **Strings, awnings and drapes** (0.5.0) have simulations of their own: bunting
sways and its pennants swing about the string, string lights swing on their sockets (the glow of lit bulbs follows
them), awnings and market canopies breathe in the wind while their valance flutters, and the altar cloths move when
someone brushes past. **0.6.0:** awnings and pennants flap continuously in strong wind instead of leaning into the
wind; multi-tile flags (the Fort Knox flags, the 16-tile Riverside street banner) are one cloth across their tiles;
the banners on Riverside's light poles move; walking past fabric barely stirs it and touching it only dents it locally;
and the simulation detail is a setting (Options > Mods > Real Flags, 0.5× to 4×). It is visual only and
client-side: no new objects, no mod data, no save changes. With no wind, wall flags are pixel-identical to vanilla. Removing the mod
restores vanilla.

- **Game:** Build **42.21.0** (built and tested against revision `4a0e9546ec`).
- **Java mod** loaded by [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) ≥ 2.3.4.
- **Pole canvas textures** come from **Any Wall Flags 3D [B42] byDGV** (`DGVFlags`, Workshop 3818605688), used as a
  dependency and never copied.

---

## 1. Install (players)

1. **ZombieBuddy.** Subscribe on the Workshop (item 3619862853) and install its launcher hook with its Windows
   installer. On this machine it patched `ProjectZomboid64.json` and the Steam launch options. Details:
   ZombieBuddy's `doc/Installation.md`.
2. **Mods.** In the mod manager enable **ZombieBuddy**, **Any Wall Flags 3D [B42] byDGV** and **Real Flags**
   (`mod.info` declares `require=\ZombieBuddy,\DGVFlags`).
3. **First launch.** ZombieBuddy asks before loading any new or changed Java jar. Check that the SHA-256 it shows
   matches the release, then approve. Every rebuild changes the hash.
4. **Launch options for debugging** (Steam → Project Zomboid → Properties → Launch Options):
   ```
   -agentlib:zbNative -- -debug
   ```
   Note the spaces around `--`: it separates Java options from game options. `-agentlib:zbNative --debug` (no
   space) passes an unknown option and debug mode stays off (`console.txt`: `unknown option "--debug"`).

**Uninstall:** disable Real Flags (or remove `mods/RealFlags`). Nothing is stored in saves or mod data.

**Multiplayer / dedicated server:** the jar lives in `42/media/java/client/`, which ZombieBuddy never loads on a
dedicated server. Wind visuals are client-only in vanilla too. Nothing is synced or needed server-side. This is by
design (RECON §7) and has not yet been run on a server.

---

## 2. Build from source

Requirements: a **JDK 25** (the game runs Java 25), and the game and ZombieBuddy installed locally (compile-only
dependencies). Gradle comes with the wrapper (9.5.0).

Copy `gradle.properties.example` to `gradle.properties` (git ignores it) and set the machine-specific paths there,
or pass them as `-P<name>=<value>`:

| Property | Meaning |
|---|---|
| `org.gradle.java.home` | JDK 25 used by Gradle (here: IntelliJ's bundled JBR 25.0.3) |
| `pzInstallDir` | Project Zomboid install (for `projectzomboid.jar`) |
| `zombieBuddyJar` | `ZombieBuddy.jar` (Workshop `…/3619862853/mods/ZombieBuddy/libs/ZombieBuddy.jar`) |
| `modsDir` | Where `deployMod` writes `RealFlags/` (normally `~/Zomboid/mods`) |

```bash
./gradlew check                      # all unit tests + "cloth-core has no PZ imports" guard
```
```bash
./gradlew :pz-adapter:verifyPatches  # offline: apply our ZombieBuddy patches to the real game classes
```
```bash
./gradlew :pz-adapter:deployMod      # build the mod folder and copy it to <modsDir>/RealFlags
```
On Windows use `gradlew.bat`. Run `verifyPatches` after every game update: it fails if a hooked method no
longer matches.

### Project layout
| Path | What |
|---|---|
| `cloth-core/` | Pure-Java cloth physics (Verlet + PBD) and colliders, no game dependency, 28 JUnit tests |
| `cloth-demo/` | Dev harness: PNG/ASCII renders of a scripted gust, wind sweep, time-to-rest probe, benchmark |
| `pz-adapter/` | ZombieBuddy patches, renderer, wind, world interactions, instance manager, Lua debug API; 21 tests |
| `mod/42/` | Mod template: `mod.info`, `media/realflags/flags.json` |
| `tools/flagtool.py` | Extract sprites from `.pack` files; measure a wall flag for `flags.json` |
| `docs/` | `FLAGS_JSON.md` (data reference) and the in-game test sheets for phases 2–4, 6 and 7 |
| `RECON.md`, `DECISIONS.md`, `CHANGELOG.md` | Engine reconnaissance with evidence, decision log, changelog |

---

## 3. ZombieBuddy setup: what this mod relies on (verified)

- `mod.info`: `javaJarFile=media/java/client/RealFlags.jar`, `javaPkgName=pzmod.realflags`, `ZBVersionMin=2.3.4`.
  The vanilla parser ignores the ZombieBuddy keys (expected warnings in mod checkers).
- **Patch classes must be in exactly `javaPkgName`.** ZombieBuddy skips subpackages (`PatchEngine.java:706`),
  although its guide shows a `.patches` subpackage. The same applies to global `@LuaMethod` classes.
- Every advice parameter is annotated (`@Patch.This`, `@Patch.Argument(i)`). ByteBuddy binds an unannotated
  parameter to the argument at the *same index*.
- Hooks, all wrapped in try/catch (first error → log once, disable for the session, flags re-baked as vanilla):

| Hooked method | Why |
|---|---|
| `FBORenderCell.calculateObjectRenderLayer` (exit) | Flags go from the cached chunk layer to the per-frame layer (the layer vanilla uses for wind-swayed trees) |
| `IsoSprite.performRenderFrame` (enter/exit) | One-shot "this draw is a flag" context; never skipped (it feeds the object picker) |
| `Texture.render(float×8, Consumer)` (enter, skip) | Replace the flag's single quad with cloth geometry |
| `IsoSprite.startTileDepthShader` (enter) | Small toward-viewer depth bias for wall flags (they share the wall's depth preset) |
| `UIManager.render` (exit) | Debug overlay |

---

## 4. How it works (short)

- **Physics (`cloth-core`):** a particle grid with structural/shear/bend distance constraints and long-range tethers,
  1/60 s fixed step with an accumulator (≤ 4 substeps, backlog dropped), pinned edge, wind per triangle
  `½ρ·Cd·|v|²·A·(n·v̂)·n` plus skin friction, gusts and turbulence, deterministic per seed. The reference pose is
  settled once per flag type; the cloth sleeps exactly on it when calm. Allocation-free stepping. Details: `DECISIONS.md`
  D16–D22, D41–D42.
- **Wall flags:** the vanilla sprite stays the image. A mesh over it is displaced by the cloth's projected motion, so
  zero wind is pixel-identical to vanilla. Depth follows the rest pose.
- **Pole flags:** the pole is rebuilt from vanilla art; a DGVFlags canvas is simulated hanging from it.
- **Wind:** `ClimateManager` speed and angle → world vector blowing toward `(sin θ, cos θ)` (verified in-game against
  rain, both directions); ×0.35 for wall flags; 0 indoors. Real-time, frozen while paused.
- **World (DECISIONS D55–D60):** each flag knows where its cloth is in the world (from the sprite's pixel frame).
  Players and zombies are upright cylinders, vehicles oriented boxes from their footprint; both push the cloth out and
  drag it by friction. Moving vehicles and running characters add an air wash to the flag's wind. Walls and furniture
  nearby are boxes from Build 42's tile geometry (`media/tileGeometry.txt`); shapes the calm flag already touches are
  ignored, so no wind still means vanilla. A wall flag with an object in front of it whose top is above 1.5 m (fridge,
  wardrobe, tall shelf) is *blocked*: not simulated, drawn as vanilla.
- **Curtains (DECISIONS D63–D70):** every sprite with a `curtainN/S/W/E` tile type (vanilla and modded) is a wall cloth,
  measured at runtime from its texture's trimmed rectangle — no data file. Two more facings cover curtains on the far
  (south/east) edge of their square, with the cloth's free side turned toward the room. A curtain is moved to the
  per-frame layer only while its window is open (×0.6), smashed or an empty frame (×1); planks reduce that (×0.2 at
  four), a metal sheet closes it; window open/close/smash (and the MP sync) re-classify it. Player-hung sheets are 3D
  models in B42: while their window is open they are drawn as their 2D sheet sprite so the cloth can bend it. A closed
  curtain the wind lifts lets daylight into the room through one dim, smoothed, building-local light (≤ 4 updates/s). Wind = outside wind × 0.5 × openness, only if outdoors is beyond the
  window: wind blowing in billows it, a steady draft keeps it breathing, wind along the wall sways it. Rolled-up sheets
  and blinds (less than ~0.46 m of cloth) stay vanilla.
- **Strings (DECISIONS D80):** a dedicated simulation (`StringLine`), not a cloth sheet: a chain pinned at both ends
  that sags into its catenary, with a pendant particle under every node — a pennant held in the plane across the
  string (it can only swing *about* the string) with flat-plate drag, or a bulb on a socket with sphere drag. A string
  spans two tiles; both tiles name the same end points and share one simulation. Lit lights draw an `_on_` overlay
  sprite, bent with the same motion (D84).
- **Canopies (D81):** a dedicated simulation (`Canopy`): the panel is held by its frame on every side, so it does not
  hang — it breathes, as the two lowest membrane modes with large-deflection stiffening, driven by wind pressure, lift
  over the top and gust fluctuation; the valance is a row of hinged flaps along the hem. The picture is described per
  column (panel and valance band), measured from the sprite by `tools/decotool.py`.
- **Gusts (D83):** strings and canopies share one gust field that travels downwind with the wind, so neighbouring
  decorations (and both tiles of one string) are hit by the same gust a moment apart. Vehicles and runners move air
  around them as for flags; vehicles can push strings.
- **Flutter (D86):** slack fabric in a strong wind does not settle into one bulge; the air pumps energy into it. The
  canopy panel keeps six membrane modes with a Van der Pol term (above a flutter speed it flaps continuously at an
  amplitude that grows with the dynamic pressure, faster as it stiffens); the valance's flaps are coupled along the hem
  and driven by vortex shedding at the Strouhal frequency, so waves run along it; pennants are shed off the same way.
- **Multi-tile cloths (D87):** the tiles of one cloth share one simulation (keyed by its anchor square); each tile draws
  its part of it, so there are no seams. A street banner hangs from a sagging cable (`topCurve`) and is lashed to a pole
  at each end.
- **Soft contacts (D88):** characters push fabric only 30 % of the way out per step (vehicles fully), so a body
  dents cloth locally and slips past; a runner stirs only the air right next to it.
- **Drapes (D82):** the altar cloths are wall cloths hanging down the altar's face (clamped to it); only the cloth's own
  columns of the sprite move.
- **Instances:** created lazily from a shared settled prototype; far LOD by distance × zoom (continuous motion across
  switches); the nearest 64 simulate; calm wall flags hand back to vanilla's single quad; evicted after ~10 s unseen.

---

## 5. Adding a flag via flags.json

Any mod (yours or a third party's) can add or override flags **by data only**. Ship one or more
`media/realflags/*.json` files in the mod's `common/` or version folder. Real Flags merges every active mod's files in
mod load order (files sorted by name); for the same sprite a later file wins. Full field reference:
[`docs/FLAGS_JSON.md`](docs/FLAGS_JSON.md).

### A wall flag, step by step
1. Extract the sprite from the 2× pack (128×256 frame — the coordinate system of flags.json):
   ```bash
   python tools/flagtool.py extract "<PZ>/media/texturepacks/Tiles2x.pack" out walls_decoration_01_16
   ```
   (Sprites from other mods: use that mod's `.pack`.)
2. Measure it. This prints a ready-to-paste entry; the facing comes from the top-edge slope:
   ```bash
   python tools/flagtool.py measure out/walls_decoration_01_16.png
   ```
3. Put the entry in your mod's `media/realflags/myflags.json`:
   ```json
   { "version": 2, "flags": [
     { "sprite": "walls_decoration_01_16", "kind": "wall", "facing": "S",
       "cloth": { "x": 66, "y": 35, "w": 52, "h": 90 }, "exposure": 0.35 }
   ]}
   ```
4. In-game: `RealFlags_status()` should count it under `flagsDefined`; `RealFlags_forceWind(40, 135)` and
   `RealFlags_forceWind(40, 308)` should each make it billow on one of the two directions (it only moves when the wind
   blows off its wall, and only on an outside square).

### A cloth hanging inside its square
A curtain on a ceiling rail (hospital privacy curtain, stage curtain) has no wall behind it. Measure it like a wall flag
(the cloth starts under the rail, so the rail stays still) and add `"inset"`: how far its line is from the facing's edge,
in metres (0.5 = across the middle). It is then not clamped to a wall, can be pushed from both sides and is never
"blocked". Use `"profile": "curtain"` for a softer, folding cloth.

### A string, a canopy, a drape
These are generated from the sprites by `tools/decotool.py` (see its header and `docs/FLAGS_JSON.md`): a `line` entry
names the string's two end points relative to its tile, its sag and its type (`bunting`, `bulbs`, `fairy`); a `canopy`
entry gives the fabric outline per column (panel top, valance top, hem), the panel normal, the hem's outward direction
and how far it turns along a round hem; a `drape` is a wall cloth over furniture.

### A pole flag
See the `street_decoration_01_16` entry in `mod/42/media/realflags/flags.json`: `anchor` (top of the hoist on the pole
axis), `depthPoint` (a point on the shaft), `size` [fly, hoist] in metres, a `canvas` texture + `uv` (exclude rods or
hems), and `staticQuads` that rebuild the pole without its painted cloth. Measure in the 2× frame with the extracted PNG.

A bad entry is logged as `[RealFlags] WARN … entry skipped`; the rest still load. A missing canvas texture (mod not
enabled) makes that flag draw as vanilla.

---

## 6. Tuning guide

### In-game (Lua console, debug mode; settings last for the session)
| Command | Effect |
|---|---|
| `RealFlags_status()` | One line: state, flags, wind, last-frame counters, CPU ms, fallbacks |
| `RealFlags_overlay(true)` | On-screen stats: flags drawn/awake/stepped/far-LOD/frozen, calm→vanilla, CPU last/avg/max ms, wind |
| `RealFlags_forceWind(kph, deg)` / `RealFlags_forceWindOff()` | Override the climate wind (deg = the game's own angle, as `RealFlags_wind()` prints) |
| `RealFlags_wind()` | Current climate wind values |
| `RealFlags_setCap(n)` | Max flags simulated per frame (default 64; nearest win) |
| `RealFlags_lodDistance(tiles)` | Coarse LOD beyond this distance × zoom-out (default 12; 0 = all coarse, 100 = all full) |
| `RealFlags_stress(n)` | Draw every flag n× (ghost copies with their own cloth) to measure cost; 1 = off |
| `RealFlags_depthBiasAmount(x)` | Wall-flag depth bias (default 2e-4); raise if wall flags show stippled edges |
| `RealFlags_debug(bool)` / `RealFlags_mesh(bool)` / `RealFlags_depthBias(bool)` | Diagnostics: tint overlay; vanilla quad vs our mesh; depth bias on/off |
| `RealFlags_setEnabled(bool)` | Turn the mod off/on for the session (off = flags re-baked as vanilla) |
| `RealFlags_testFailure()` | Make the next flag draw throw, to see the fail-once → vanilla path (restart afterwards) |
| `RealFlags_interactions(bool)` | Players, zombies, vehicles and scenery act on flags (default on; off = wind only) |
| `RealFlags_blockHeight(m)` | Wall flags with an object taller than this in front stay vanilla (default 1.5 m; 0 = never) |
| `RealFlags_curtains(bool)` | Window curtains move while their window is open or broken (default on; off = all vanilla) |
| `RealFlags_curtainExposure(x)` | Fraction of the outside wind reaching a curtain through a fully open window (default 0.5) |
| `RealFlags_curtainLight(bool)` | Daylight flickers into the room past a closed curtain the wind lifts (default on) |
| `RealFlags_curtainLightGain(x)` | Strength of that light at a fully lifted curtain in full daylight (default 0.12; max 0.5) |
| `RealFlags_decorations(bool)` | Strings (bunting, string lights) and canopies move (default on); off draws them as vanilla |
| `RealFlags_setDetail(x)` | Simulation detail 0.5–4× (also Options > Mods > Real Flags); every simulation restarts from rest at the new resolution |

Lua needs the parentheses: `RealFlags_status()`, not `RealFlags_status`.

### Per flag (flags.json)
| Want | Change |
|---|---|
| Wall flag moves too little or too much | `exposure` (default 0.35; walls saturate fully out at ≈10 m/s of *felt* wind) |
| Heavier, slower cloth | `physics.mass` up, `physics.drag` down |
| Floppier or stiffer | `physics.stiffness` [stretch, shear, bend]: lower bend = more folds |
| Calmer / livelier | `physics.damping` (0.01–0.05) |
| Slow-motion / faster | `timeScale` |
| Returns to rest too slowly (limp flags) | `physics.restore` (pose memory 1/s; pole default 5) |
| Cheaper / finer | `physics.grid`, `physics.iterations`, far-LOD `physics.lodGrid`, wall render `mesh` |

### Offline (no game needed)
```bash
./gradlew :cloth-demo:run --args="--mode sweep --flag pole"
```
Steady wind 0.1–33 m/s: mean displacement, lift and stretch per speed. Shows where a flag saturates.
```bash
./gradlew :cloth-demo:run --args="--mode calm --flag pole --restore 5"
```
Storm from 12 directions, then calm: seconds until asleep exactly on the reference pose.
```bash
./gradlew :cloth-demo:run --args="--mode png --flag pole --texture <png> --uv 0,0,1,0.944"
```
Scripted gust → `demo-out/pole/` frames, `sheet.png`, `metrics.csv`.
```bash
./gradlew :cloth-demo:run --args="--mode bench --flag wall --count 50"
```
Physics cost of 50 awake cloths per frame. All modes accept `--grid CxR --iterations N --mass X --cd X --cf X`.

---

## 7. Performance (measured)

User's PC, CPU time of the mod per frame (simulation + geometry + submission), in-game overlay, 60-frame average:

| Scenario | Measured | 50 flags |
|---|---|---|
| Far LOD, 30 awake flags | 0.279 ms (max 0.449) | ≈0.47 ms |
| Calm, 90 drawn (wall flags handed back to vanilla) | 0.339 ms | — |
| Full detail, original defaults (6×10/10×6, 5 iterations), 30 awake | 0.90 ms (max 2.36) | ≈1.5 ms ✗ |
| Full detail, **current defaults** (5×8/8×5, 4 iterations), 50 awake | passed < 1 ms (user report; figures not recorded) | expected ≈0.95 ms |

Offline physics alone (`--mode bench`, 50 awake): wall 0.55 ms, pole 0.60 ms per frame. Occasional single frames
reach ~2 ms (seen in the first run); the average is the acceptance metric.

---

## 8. Known limitations

- The pole canvas uses the pole's depth everywhere, so it always draws in front of its own pole, even when it swings
  behind it.
- Depth follows the rest pose: a wall flag billowing far off its wall still sorts as if flat on the wall. (Since 0.2.1,
  cloth pushed *along* its wall takes the depth of the wall it is drawn over, so it is never hidden by its own wall.)
- Curtains (0.3.0) are verified in-game by user report only; per-step results and CPU cost were not recorded (`docs/PHASE7_TEST.md`). Curtains on player-built (`IsoThumpable`)
  windows never move. An *open* curtain (two side panels) is simulated as one sheet spanning both panels. A player-hung
  sheet switches between its 3D model (window closed) and its 2D sprite (window open), so it changes look when the
  window opens. The curtain light's strength and update rate are untuned. A curtain
  depth bias (same 2e-4 as wall flags) is applied as a precaution and has not been tuned.
- Wall flags stored as an *attached sprite* of a wall object (rather than a separate object) are not animated. None were
  found in testing.
- Multi-tile cloths (0.6.0) are one simulation, but each tile still draws its own picture: where the vanilla art of two
  tiles does not line up exactly (the Knox flag's tiles differ by ≈5 px), that step stays visible.
- 0.6.0 (flutter, multi-tile cloths, street and pole banners, soft contacts, detail setting) is verified by unit tests
  only, not yet in-game. The pole banners are assumed to be drawn on (or attached to) Riverside's light poles; if a map
  places them differently, they still move as their own objects.
- Strings, canopies and drapes (0.5.0) are verified by unit tests only, not yet in-game; their physical constants
  (fabric densities, drag, valance stiffness) are estimates and untuned. Canopy geometry is approximate: the panel
  normal, the hem's outward direction and the 2.0 m hem height used to place flaps in the gust field come from the
  canopy's family and facing, not from a 3D model. On quarter-round canopies only the low end of the hem flutters; the
  back views of plain canopies show no valance and only breathe. Bodies push strings (in practice only tall vehicles
  reach them); canopies only feel the air that vehicles and runners move. The string lights' light source does not
  move with the bulbs.
- Fabric decorations (0.4.0) are verified by unit tests only, not yet in-game. The depth bias for wall cloths that are
  attached to a wall without being `WallOverlay` tiles (church banners) is a precaution and untuned.
- Wall flags' painted top edges are draped curves; under strong wind the art's top edge can look slightly tilted.
- Settings changed from Lua last for the session only.
- No tearing yet (the per-constraint/per-triangle `alive` flags exist for it).
- Interactions are verified in-game by user report only (0.2.1); per-step results and CPU cost were not recorded (`docs/PHASE6_TEST.md`). Bodies are simple shapes: characters are
  0.25 m cylinders, vehicle height is the script's body height plus 0.4 m, rotated scenery uses its bounding box.
  Animals and loose items on the floor do not interact. Furniture from mods without a `tileGeometry.txt` blocks a wall
  flag only by a picture-based height estimate and is not a collider.

---

## 9. License

MIT, see [LICENSE](LICENSE). This repository contains no game code, no decompiled sources and no third-party art.
Vanilla sprites and DGVFlags textures are referenced by name or path at runtime only.
