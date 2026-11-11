# Changelog

All notable changes to this project. Format: [Keep a Changelog](https://keepachangelog.com/), versioning: SemVer.

## [0.6.0] — unreleased — flutter, multi-tile cloths, banners, soft contacts, detail setting

Decisions D86–D90. Unit-tested (103 tests) and patch-verified offline; not yet tested in-game.

### Changed
- **Renamed to Floppy Fabrics.** The mod now presents itself as general dynamic physics for all fabrics, not just flags
  (README, `mod.info` name and description, Options > Mods entry). Internal ids are unchanged: mod id `RealFlags`, jar,
  package `pzmod.realflags`, `media/realflags/`, `RealFlags_*` console commands. Planned: garments on clothing racks
  (docs/HANGING_CLOTHES_FUTURE.md).
- **No more Any Wall Flags 3D dependency** (D91): the pole flag canvas is now `media/realflags/pole_flag.png`, shipped
  with the mod (generated with Magnific, creation `YMjQSixWeC`, downscaled to 512×286); `mod.info` is
  `require=\ZombieBuddy` only. Pole physics is unchanged. The texture is full-bleed, so `canvas.uv` is `[0,0,1,1]`.
- **Floppier fabric** (D86): canopies keep six membrane modes (local ripples) with a Van der Pol flutter term, much
  softer stiffening (15 cm at a 15 m/s gale, cap 25 cm) and turbulence whose frequency rises with the wind: strong wind
  gives continuous, fast, large flapping instead of a static bulge. Valance flaps are coupled along the hem and driven
  by vortex shedding (Strouhal 0.14) as a wave; pennants flap the same way, with less damping. Strings gained
  long-range tethers so the flutter never stretches them.
- **Softer contacts** (D88): characters are soft bodies (30 % of the penetration resolved per step, friction likewise),
  slimmer (0.2 m), and a runner's air wash is 0.08 of its speed within 0.6 m (was 0.35 within 1.2 m). Vehicles unchanged.

### Added
- **Multi-tile cloths** (D87): `group` and `topCurve` in flags.json; the tiles of one cloth share a single simulation.
  The Fort Knox flags (`location_military_knox_01_8..13`) are now two 3-tile cloths with no seams.
- **Riverside street banner** `signs_one-off_09_312..327`: one 16 m cloth hung from a sagging cable, lashed at both ends
  (`top_sides`), free in the air (`free`).
- **Pole banners** `signs_one-off_09_336..337` on the `lighting_outdoor_01_4` light poles (D90), tied down at their
  lower corners (`top_and_bottom_corners`); drawn as their own objects or as attached sprites of the pole (an object
  with a cloth among its attached sprites is drawn every frame).
- **Simulation detail** (D89): 0.5×–4× of every cloth grid, string, valance and render mesh; Options > Mods > Real Flags
  (`media/lua/client/RealFlags/RealFlagsOptions.lua`, PZAPI.ModOptions) and `RealFlags_setDetail(x)`.
- One object may carry several cloths (instances chained per definition).
- 11 tests (`SoftContactTest`, `GroupsAndDetailTest`, new `CanopyTest` cases).

## [0.5.0] — unreleased — strings, canopies and drapes

Decisions D80–D85. Unit-tested (92 tests) and patch-verified offline; not yet tested in-game.

### Added
- **Strings** (`cloth-core` `StringLine`, D80): bunting (`walls_decoration_01_64..67, 72..75, 80..83`,
  `location_restaurant_bar_smutz_32..35`) and string lights (`walls_decoration_01_76..79, 88..91, 96..99`). A chain
  pinned at both ends, sagging into its catenary, with a pennant (swings only about the string, flat-plate drag) or a
  bulb (free pendulum, sphere drag) under every node. Each vanilla string spans two tiles; both share one simulation.
- **Lit string lights** bend their `_on_` overlay sprite with the same motion, so the glow follows the bulbs (D84).
- **Canopies** (`cloth-core` `Canopy`, D81): all 84 awnings and market canopies of `walls_decoration_01` and the
  restaurant atlases. The panel breathes (two membrane modes with large-deflection stiffening, driven by wind pressure,
  lift over the top and gust fluctuation); the valance is a row of hinged flaps along the hem, tapered to still at its
  corners; the frame's bars and legs stay still.
- **Drapes** (D82): the altar cloths `location_community_church_small_01_40..43` hang down the altar's face and move
  when bodies brush them; only the cloth's columns of the sprite move.
- **Shared, advected gust field** (`GustField`, D83) for strings and canopies; vehicles and runners move air around
  them; vehicles push strings (`BodyScan`).
- `tools/decotool.py` generates `media/realflags/decorations.json` (116 entries) from `Tiles2x.pack`.
- `flags.json`: kinds `line`, `canopy`, `drape`. Lua `RealFlags_decorations(bool)`; status and overlay counters.
- 23 tests (`StringLineTest`, `CanopyTest`, `DecorationsTest`).

### Changed
- `SpriteMesh`: a generic displacement-field mesh for decorations; the flag and curtain paths are unchanged.

## [0.4.0] — unreleased — fabric decorations

Decisions D77–D79. Unit-tested and patch-verified offline; not yet tested in-game.

### Added
- **31 more fabric tiles move** (data in `flags.json`, now 36 entries):
  - church banners `location_community_church_small_01_76..78, 84..86`;
  - Fort Knox flags `location_military_knox_01_8..13` (one flag over 3 tiles; each tile is its own cloth);
  - the white bar banner `location_restaurant_bar_smutz_23`;
  - hospital privacy curtains `location_community_medical_01_41..43, 45..47` (blue) and `_144..149` (white);
  - theatre stage curtains `location_entertainment_theatre_01_92..94, 101..103`.
  Indoors (most of them) there is no wind: they move when people, zombies or vehicles push them.
- **`inset`** for wall cloths (flags.json): a cloth hanging inside its square instead of on its edge. Hospital and theatre
  curtains hang from a rail across the middle of their square (`inset` 0.5): no wall plane clamp, touched from both
  sides, never blocked. Rails stay still (the cloth is measured below them).

### Changed
- The toward-viewer depth bias for wall cloths now also covers tiles attached to a wall (`attachedN`/`attachedW`) that are
  not `WallOverlay` tiles, e.g. the church banners (D78). Free-hanging cloths keep vanilla depth.

### Not animated (reasons in D79)
- Bunting (`walls_decoration_01_64..67, 72..75, 80..83`, `location_restaurant_bar_smutz_32..35`), awnings (`walls_decoration_01_68..71,
  84..87, 92..95, 100..103, 108..167`, `location_restaurant_pileocrepe_01_4..5`, `location_restaurant_pie_01_4..5`,
  `location_restaurant_bar_smutz_12..15`), the church altar cloth (`location_community_church_small_01_40..43`),
  and rails/rods without cloth (`location_community_medical_01_40, 44`, `location_entertainment_theatre_01_95, 100`).

## [0.3.0] — unreleased — window curtains

Decisions D63–D76. Verified in-game by user report (2026-11-09); per-step results and CPU figures of `docs/PHASE7_TEST.md` not recorded.

### Added
- **Curtains move while their window is open or broken.** Every curtain tile (vanilla `fixtures_windows_curtains_01/02`
  and modded ones, detected by their `curtainN/S/W/E` tile type) is simulated as a wall cloth hanging from its rod, with
  no data file: its shape is measured from the sprite. Wind from outside comes in through the window (×0.6 for an opened
  sash, ×1 smashed or an empty frame, reduced by barricades); people, zombies and vehicles push it like a flag. Behind a
  closed window a curtain stays in vanilla's cached layer at zero cost; if the window closes while it moves, it settles
  first. Rolled-up sheets and blinds stay vanilla.
- Patches on `IsoWindow.ToggleWindow`, `smashWindow(boolean, boolean)` and `syncIsoObjectReceive` re-classify a
  window's curtains when it opens, closes or breaks.
- **Sheets hung by the player move too** (D72; fixed in D76 — the first build never switched them off the 3D model,
  because curtain tiles are already in vanilla's per-frame layer). They are 3D models in Build 42, which nothing can bend; while their
  window is open or broken they are drawn as their 2D sheet sprite instead (new advice on `IsoObject.getSpriteModel`),
  and become the 3D model again once the window is closed and they have settled.
- **Daylight flickers in past a closed curtain** the wind lifts off an open window (D73): one dim, building-local light
  per curtain, following how far the cloth billows × outside daylight. Smoothed, slew-limited and updated at most
  4×/s, so it ramps rather than flickers; nothing at night or when the curtain hangs still.
- **Metal-sheet barricades seal the window** (D74): the curtain behaves as behind a closed window. Planks let less air
  through per plank (×0.2 at four), metal bars do not slow the air.
- Lua: `RealFlags_curtains(bool)`, `RealFlags_curtainExposure(x)`, `RealFlags_curtainLight(bool)`,
  `RealFlags_curtainLightGain(x)`; curtain, sheet and light counters in `RealFlags_status()`.
- `cloth-core`: `ClothProfiles.CURTAIN`, `AnchorFrame.flipNormal`, `StaticAnchor.of(…, flipNormal)`.
- Wall facings `N`/`W` (cloth on the square's south/east edge, facing into it). 9 tests (`CurtainsTest`,
  `CurtainLightTest`).

## [0.2.1] — unreleased — fixes from the first in-game test of 0.2.0

Decisions D61–D62. Verified in-game by user report (2026-11-09); per-step results and CPU figures not recorded.

### Fixed
- **Wall flags sank into the wall when a character pushed them.** The physics kept the cloth in front of the wall; the
  renderer gave moved vertices their rest-pose depth, so cloth pushed up or sideways along the wall ended up "behind"
  the wall pixels it was drawn over. Moved vertices now take the depth of the wall surface under them (D61).
- **Wall flags could stay folded for ever** after a body walked through them (4 of 12 directions in a probe). Pose
  memory now strengthens gradually while a calm cloth is not home, so folds let go (D62).

### Changed
- **Gentler return to rest after an interaction:** pose memory fades in over 3 s (from 50%), no sleep for 4 s after the
  last contact, and the final snap waits until the cloth is within 4 mm of its rest pose (D62).
- **Thick wall:** the flag's wall has a real thickness (from its tile geometry, + cloth thickness). Bodies never push
  cloth into the wall — it slides out sideways, over or under them — and cloth pushed along the wall stands slightly
  off it (D61).

## [0.2.0] — unreleased — flags meet the world

Decisions D55–D60. The first in-game test found three issues (flag sank into the wall when pushed, returned to rest
too quickly, wall too thin); fixed in 0.2.1.

### Added
- **Players and zombies** push flags they walk into and drag them along (cylinder bodies; low when crawling or sitting).
- **Vehicles** push flags they drive into (oriented box from the vehicle's footprint) and blow them about as they pass
  (air wash and bow wave added to the flag's wind; works indoors too, where there is no climate wind).
- **Running** characters stir nearby flags.
- **Scenery**: flags collide with the solid shapes of nearby walls, furniture and fences, read from Build 42's tile
  geometry (`media/tileGeometry.txt`, plus mods' own). A pole flag stops at the building beside it; a wall flag drapes
  over a counter. Shapes the flag's rest pose already touches are ignored, so calm flags still look exactly vanilla.
- **Blocked wall flags**: a wall flag with an object in front of it taller than 1.5 m (1.5 tiles) — a fridge, wardrobe,
  tall shelf — is not simulated and stays the vanilla picture. Re-checked about once a second.
- `cloth-core`: `Colliders` (static/dynamic bodies, projection + friction), `Cloth.setColliders`,
  `Cloth.resetToReference`, `Cloth.referenceBounds`; 7 tests.
- Adapter: `Interactions`, `Scenery`, `Projection.clothOrigin`; 5 tests (placement against the measured pixels, real
  geometry bounds, the blocking rule with the real fridge geometry).
- Lua: `RealFlags_interactions(bool)`, `RealFlags_blockHeight(m)`. Status and overlay show blocked flags, flags in
  contact, nearby bodies, scenery boxes and geometry cache counts.

### Changed
- `mod.info` description mentions the interactions; version 0.2.0.

## [0.1.0] — 2026-11-06 — MVP

Wind-reactive cloth for the vanilla American Flag pole (with a DGVFlags canvas) and four vanilla wall flags, on
Build 42.21.0 via ZombieBuddy 2.3.4. Acceptance status: DECISIONS.md "Acceptance status (MVP)".

### Added — Phase 5 (documentation)
- `README.md`: install, build, ZombieBuddy setup and pitfalls, how it works, adding flags via flags.json, tuning guide,
  measured performance, known limitations.
- `tools/flagtool.py`: extract sprites from `.pack` files and measure a wall flag into a ready flags.json entry.
- `docs/FLAGS_JSON.md` updated for the current schema (`lodGrid`, defaults, multi-file merge).

### Fixed — Phase 5
- Other mods can now add or override flags by shipping `media/realflags/*.json` (any file name, merged across all
  active mods in load order). Previously only one `flags.json` path was read, so another mod's file would have
  replaced ours (D52). New test `FlagMergeTest`.

### Added — Phase 4 (instance manager, overlay, failure path)
- `cloth-core`: `Cloth(Cloth prototype)` (shared settled pose) and `Cloth.resampleFrom(Cloth)` (LOD switch that keeps
  the motion); 3 new tests.
- Adapter: `Prototypes` (one settle per flag type and LOD), far LOD profiles (wall 4×6, pole 6×4, 4 iterations; halved
  wall render mesh), distance × zoom LOD with hysteresis, `Scheduler` (nearest-64 cap, per-frame stats, 60-frame
  CPU window, eviction), calm wall flags drawn by vanilla, `simDistance` gate.
- Debug overlay (`RealFlags_overlay`) via a new `UIManager.render` patch: flags drawn/awake/stepped/far/frozen,
  CPU ms last/avg/max, wind.
- Lua: `RealFlags_overlay`, `RealFlags_stress` (ghost copies for the 50-flag measurement), `RealFlags_setCap`,
  `RealFlags_lodDistance`, `RealFlags_testFailure`.
- 4 adapter tests (far profiles, shared prototype, LOD switch keeps motion, cap ranks the nearest).
- `docs/PHASE4_TEST.md` (measurement protocol).
- `cloth-demo --mode calm` (time-to-rest probe after storms from 12 directions).

### Changed — Phase 4 (after the first in-game measurement)
- Near LOD defaults: wall 5×8, pole 8×5, 4 iterations, wall render mesh 5×8. Measured in-game, 50 flags at the old
  near defaults cost ≈1.5 ms; offline the simulation now costs ≈11–12 µs/flag instead of ≈22–24 µs (D49).
- Pole pose memory 3 → 5 /s, so it always returns to its own limp fold (D50).
- Canvas and static-quad textures preload on flags.json load: no vanilla-flag flash on first sight (D51).

### Verified in-game — Phase 4 run 2
- User report: all Phase 4 checks pass with the cheaper near LOD, including 50 flags at full detail under the 1 ms
  target and no canvas flash on first sight (exact overlay figures not recorded).

### Verified in-game — Phase 4 run 1
- Far LOD: 50 flags ≈ 0.47 ms (from 30 flags at 0.279 ms). Cap, eviction, shared prototypes, calm→vanilla, and the
  fail-once → disable → vanilla path all behave as specified.

### Added — Phase 3 (pole + wall flag animated end-to-end)
- `flags.json` schema v2 (`FlagJson`, documented in `docs/FLAGS_JSON.md`): wall `facing` + measured `cloth`
  parallelogram + render `mesh`; pole `anchor`, `depthPoint`, `size`, `canvas` {texture, uv}, `staticQuads`; per-flag
  `exposure`, `timeScale`, `physics` overrides. Bad entries are reported and skipped.
- Starter data measured from the sprites: 4 wall flags (`walls_decoration_01_16..19`) and the American Flag pole
  (`street_decoration_01_16`) with the DGVFlags US canvas, pole rebuilt from vanilla art.
- `Projection` (world ↔ screen, wind convention, wall inverse mapping, grid sampling), `WindProvider`,
  `FlagInstance` (cloth + per-flag gusts, once-per-frame step, real-time, frozen while paused), `Instances` (weak,
  lazy), `Canvases` (cached textures, missing mod → vanilla + one WARN).
- `SpriteMesh` now renders wall flags as the vanilla image bent by the cloth (identical at zero wind) and pole flags
  as rebuilt pole + simulated canvas.
- Lua: `RealFlags_forceWind(kph, deg)`, `RealFlags_forceWindOff()`; status shows instances and wind.
- 11 adapter tests (projection vs IsoUtils, rain-sample wind direction, wall mapping round trip, schema, overrides).
- `docs/PHASE3_TEST.md`.

### Changed — Phase 3
- Debug overlay off by default. Wall-flag depth bias default 2e-4 confirmed in-game.

### Verified in-game — Phase 3 (2026-11-06)
- Pole and wall flags wave and react to wind strength (10/40/70 km/h) and direction (308° → screen-left, 135° →
  screen-right, matching the rain); wall flags respond only to wind blowing off their wall; zero wind returns to
  vanilla; pause freezes; indoor flags stay still; picking works. Wind convention (D32) confirmed for both directions.

### Added — Phase 2 (adapter hello-jar)
- `pz-adapter` module (package `pzmod.realflags`), compile-only against `projectzomboid.jar` 42.21.0 and
  ZombieBuddy 2.3.4; ships as one jar with cloth-core bundled.
- Patches: `FBORenderCell.calculateObjectRenderLayer` (flags → per-frame layer), `IsoSprite.performRenderFrame`
  (draw context), `Texture.render(float×8, Consumer)` (replace the flag's quad).
- `SpriteMesh`: captures vanilla's depth/seam modifier output and re-emits the flag as a 4×4 grid of sub-quads with
  bilinearly interpolated attributes (identity → looks like vanilla) plus a pulsing magenta debug overlay.
- Failure isolation: every hook catches everything; the first error logs once, disables the mod for the session and
  invalidates reclassified flags so they re-bake as vanilla. Fallback counters per reason.
- `flags.json` (`media/realflags/`) with the 5 starter sprites; strict `MiniJson` reader with line/column errors.
- Lua debug globals: `RealFlags_status`, `RealFlags_debug`, `RealFlags_setEnabled`, `RealFlags_wind`, plus A/B
  toggles `RealFlags_mesh` (sub-quads vs vanilla quad) and `RealFlags_depthBias`.
- In-game confirmed (2026-11-06): loads clean; pole flag redrawn every frame by our mesh with the debug overlay
  (2.0 s pulse measured from the user's video); pole halves aligned; Lua API works.
- Mod template `mod/42/mod.info` (`require=\ZombieBuddy,\DGVFlags`, `javaJarFile=media/java/client/RealFlags.jar`).
- Gradle tasks: `:pz-adapter:deployMod` (→ `mods/RealFlags`), `:pz-adapter:verifyPatches` (offline ZombieBuddy
  patch-application probe against the real game classes).
- 5 adapter unit tests (JSON parsing, shipped flags.json, bad-entry tolerance).
- `docs/PHASE2_TEST.md`: in-game test checklist.

### Fixed — Phase 2
- Wall flags z-fought with their wall in the per-frame pass (stipple, seen in-game). Cause: wall overlays use the
  wall's own depth preset, compared `GL_LEQUAL` against the cached chunk depth. New patch
  `IsoSprite.startTileDepthShader` applies a toward-viewer depth bias to wall-overlay flags (DECISIONS D30).
  Vanilla's 5e-5 left edge bands in-game; the default is now 2e-4, tunable via `RealFlags_depthBiasAmount` (D33).
- Wind direction convention adopted: blows toward `(sin θ, cos θ)`, matching the in-game rain direction (D32).
- Patch classes moved from `pzmod.realflags.patches` to `pzmod.realflags`: ZombieBuddy only collects patches from the
  exact `javaPkgName` package (found by the offline probe before any game launch).

### Added — Phase 1 (cloth-core, tests, demo)
- Gradle 9.5.0 multi-module build (`cloth-core`, `cloth-demo`), Java release 25, `-Werror`; `checkNoPzImports` guard.
- `cloth-core` (package `pzmod.realflags.cloth`, zero dependencies):
  - `Cloth`: Verlet + PBD particle grid; structural, shear and bend distance constraints; long-range tethers; pinned
    particles following a `ClothAnchor`; fixed 1/60 s step with accumulator, substep cap and backlog drop; damping;
    gravity-settled reference pose; sleep with exact snap to the reference; pose memory fading with wind; health
    check with reset counter; per-constraint and per-triangle `alive` flags (reserved for tearing); allocation-free stepping.
  - `WindForce`: spec drag formula per triangle plus skin friction, deterministic gusts and per-triangle turbulence,
    overshoot cap. `ForceSource` interface for future sources.
  - `ClothProfile` (validated, builder) and built-in `ClothProfiles.WALL` / `POLE`; `PinLayout`; `AnchorFrame`,
    `StaticAnchor`; `GustNoise`.
- 18 JUnit tests: pinned particles never move; zero-wind bit-identical rest; return-home and sleep (wall, and pole
  from 6 directions); no blow-up at huge/invalid dt; edge stretch bound in storms; monotonic strength (wall, pole,
  light breeze); direction response (wall half-plane, pole 8 compass directions); determinism with fixed seed; plane
  clamp; tearing flags; texture coordinates; profile validation; noise bounds.
- `cloth-demo` headless harness: `png` (scripted gust: calm → breeze → gusts → direction swing → storm → calm, PNG
  frames, contact sheet, metrics CSV, optional real texture), `ascii`, `sweep` (steady-wind response table), `bench`.
- Decisions D16–D22 in `DECISIONS.md` (incl. measured performance table).

### Phase 0
- Phase 0 recon: `RECON.md` (game build 42.21.0, ZombieBuddy 2.3.4, flag sprites and atlas coordinates, render call
  path and hooks, wind API, threading model) and `DECISIONS.md` (decisions D1–D10, open questions Q1–Q4).
- Recorded your answers to Q1–Q4 as D11–D15. Added RECON §3b: DGVFlags as the pole-flag canvas source, texture
  loading path, rod-strip UV, `require=` syntax, and pole/cloth pixel split of `street_decoration_01_16`.

### Changed
- Project renamed to **RealFlags**. The docs moved from `mods/ClothFlags` to `D:\Real-flags`, and the old folder was
  removed. The mod folder `mods/RealFlags` will be generated by the build.
- No code yet.
