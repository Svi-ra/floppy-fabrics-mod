# Decision log

Format: ID · decision · why · evidence. D1–D10 were decided from evidence; D11–D15 are your answers to Q1–Q4.

## Decided (from evidence, Phase 0)

**D1 · 2026-11-05 · decided — Client-only jar path.**
`42/media/java/client/RealFlags.jar`. ZombieBuddy skips `media/java/client/` jars on a dedicated server, so the
mod is a guaranteed server no-op. *Evidence:* ZombieBuddy `doc/ModdingGuide.md` "Client/server JAR paths".

**D2 · 2026-11-05 · decided — Flags are identified by sprite name.**
There is no flag class; flags are plain `IsoObject`s. Lookup key = `IsoSprite.getName()`. *Evidence:* RECON §3.

**D3 · 2026-11-05 · decided — Intercept at `Texture.render`, not `performRenderFrame`.**
`performRenderFrame` must run to completion because it writes `ObjectRenderInfo.renderX/Y/Width/Height`, which
`FBORenderObjectPicker` uses for hover/click. We set a context in its enter/exit advice and replace only the final
`Texture.render(x,y,w,h,r,g,b,a,mod)` quad. *Evidence:* `IsoSprite.java:1430-1458`, `FBORenderObjectPicker.java:180-192`.

**D4 · 2026-11-05 · decided — Own per-corner depth modifier.**
`TileDepthModifier` maps depth UVs from the whole sprite rect; sub-quads need per-vertex depth UVs from the rest
pose. *Evidence:* `tileDepth/TileDepthModifier.java`.

**D5 · 2026-11-05 · decided — Re-set `TextureDraw.nextZ` / `nextChunkDepth` before every sub-quad.**
`TextureDraw.Create` consumes and zeroes them. *Evidence:* `TextureDraw.java:914-917`.

**D6 · 2026-11-05 · decided — Wind source = `getWindIntensity()` + `getWindAngleDegrees()` + own gust noise.**
Not `getWindTickFinal()`, because WindSway (enabled in your mod list) patches it. *Evidence:* windsway.jar class
`Patch_ClimateManager$Patch_getWindTickFinal`.

**D7 · 2026-11-05 · decided — Time base = real seconds, gated by pause.**
`getRealworldSecondsSinceLastUpdate()` with `GameTime.isGamePaused()`. `getMultiplier()` includes fast-forward and a
×200 sleep multiplier that would blow up the solver regardless of substep caps. *Evidence:* `GameTime.java:195-212, 976-998`.

**D8 · 2026-11-05 · decided — Overload pinning.**
All advice methods declare the full un-annotated parameter list so ZombieBuddy matches exactly one overload.
*Evidence:* `PatchEngine.java:332-548`.

**D9 · 2026-11-05 · decided — Normalised cloth rects in `flags.json`.**
Rects are fractions of the sprite frame (`widthOrig × heightOrig`) so one entry covers Tiles1x and Tiles2x.

**D10 · 2026-11-05 · decided — "No console errors" is measured by our tag.**
console.txt already contains ~6.4k ERROR lines from other mods; the criterion becomes "0 lines tagged `[RealFlags]`
at ERROR/exception level".

## Decided by you (answers to Q1–Q4, 2026-11-05)

**D11 · Q1 — Pole flag = vanilla pole tile + DGVFlags canvas.**
Draw only the pole of `street_decoration_01_16` (finial and shaft, with a re-sampled strip filling where vanilla painted
cloth), and simulate a cloth textured from DGVFlags (`media/dgv_flags/UnitedStates_0_0_256x143.png` for the vanilla
"American Flag" pole, UV excluding the 8 px rod strip). `require=\ZombieBuddy,\DGVFlags`.
*Consequences:* the pole flag is not pixel-identical to vanilla at zero wind (it hangs limp with the DGVFlags art). If
the DGVFlags texture can't be loaded, the vanilla sprite is drawn unchanged. Wall flags keep the "zero wind = vanilla"
guarantee. The canvas texture is referenced by path at runtime, never copied into our mod.
*Evidence:* RECON §3b.
*Rejected:* limp strip from the vanilla texture; swapping to a vanilla flat-flag texture; stretching the furled art.

**D12 · Q2 — Flags render in the per-frame `Translucent` layer while the mod is active.**
Classification change only, via `FBORenderCell.calculateObjectRenderLayer` exit advice. On disable or failure the
affected chunk levels are invalidated so the flags re-bake into the cached layer → exact vanilla.

**D13 · Q3 — Project layout.**
Gradle multi-module source at `D:\Real-flags` (`cloth-core`, `cloth-demo`, `pz-adapter`, plus a `mod/` template). A
build task assembles a clean mod folder and copies it to `C:\Users\user\Zomboid\mods\RealFlags`
(`42/mod.info`, `42/media/java/client/RealFlags.jar`, `42/media/realflags/flags.json`). Mod id `RealFlags`, package
`pzmod.realflags`, log tag `[RealFlags]`. Decompiled game code stays outside the repo.
*Replaces* the earlier proposal of `mods/ClothFlags`; the three docs were moved here and that folder was removed.

**D14 · Q4 — MVP starter set.**
Pole `street_decoration_01_16` (with `_24` left vanilla), wall flags `walls_decoration_01_16`, `_17`, `_18`, `_19`.
Indoor wall flags (square not outside) get zero wind and stay static = vanilla. Knox 3-tile flags and the 2x-only
banner `location_restaurant_bar_smutz_23` are deferred.

**D15 · Third-party flags by data only.**
Besides our own `flags.json`, each active mod may ship `media/realflags/*.json` with the same schema (sprite name →
cloth rect, pin edge, rest size, profile, optional `staticQuads` and `canvas` texture). Entries are merged at load.

## Phase 1 — cloth-core (2026-11-05)

**D16 · Build toolchain.** Gradle **9.5.0** wrapper (generated offline from the cached distribution), JDK 25 =
IntelliJ's JBR 25.0.3 via `org.gradle.java.home` in `gradle.properties` (machine-specific, commented). `--release 25`
(the game runs Zulu 25.0.1), `-Xlint:all -Werror`, JUnit **6.0.0** (already cached). `cloth-core` has a `checkNoPzImports`
task wired into `check`, which fails on any `zombie.*`, `se.krka.*` or `me.zed_0xff.*` import. It was verified by
planting a violating file.

**D17 · Simulation space.** Metres, z up. A cloth is a `width × height` rectangle parametrised by texture axes:
particle (u, v) rests at `origin + u·axisU + v·axisV` (`AnchorFrame`). Converting PZ world/screen units to this space
belongs to the adapter (Phase 2/3), not the core.

**D18 · Reference pose = gravity-settled pose of the same solver; render rest + (position − reference).**
PBD always sags a little under gravity, so "flat rectangle at rest" cannot be an equilibrium. Each cloth settles once
at construction. That settled pose is the reference: the cloth starts asleep on it, pose memory returns it there when
calm, and sleep snaps to it exactly. For wall flags the adapter draws *vanilla rest quad + projected (pos − ref)*, so zero
wind is bit-identical to vanilla (test `restPoseStableAtZeroWind`). The settle uses extra damping (0.08) but the **same
iteration count** as live stepping. *Rejected:* settling with 3× iterations to reduce the pole's limp-fold stretch.
The result is not an equilibrium of the live solver, so the cloth sagged 2 cm away from it and never slept (measured).

**D19 · "Monotonic response" stated precisely.** Below saturation, mean displacement strictly increases with wind
speed (tested 0.5–6 m/s). Once a flag is fully extended (≈10 m/s for the default wall flag: drag ≈ 24 N vs 2.5 N weight)
its extension is bounded by geometry, and storm flutter shortens the *mean* extension slightly: −2.5% at 33 m/s,
measured with the demo sweep. Above saturation the test requires staying within 3% of the peak. Pole: lift strictly
increases 1–15 m/s. Direction: wall flags move along every wind direction in the front half-plane (0°–180° in 30° steps)
and are mirror-symmetric; pole flags stream within 30° of every compass direction.

**D20 · Additions to the spec's model, each fixing a measured failure.**
| Addition | Failure it fixes (measured before the fix) |
|---|---|
| Bend (skip-one) constraints, profile `bendStiffness` | limp cloth folds through itself freely |
| Long-range attachment tethers (`tethers`) | 2.2 cm gravity sag on the wall reference; 67% edge stretch at 33 m/s |
| Drag cap `f ≤ m·v_rel/(2h)` in `WindForce` | explicit stiff-drag overshoot → 189 emergency resets in the large-dt test |
| Particle speed cap (`maxParticleSpeed`, 40 m/s) | same; last-resort guard |
| Skin friction `½ρ·Cf·|v|·A·v_t` (`frictionCoefficient` 0.05) | wind parallel to the cloth produced exactly zero force (spec formula is normal-only) |
| Wall plane clamp (`clampToAnchorPlane`) | wall flags passed through the wall (collision is out of scope; this is an anchor-frame half-space only) |
| Pose memory fading by `restoreFadeSpeed` (2 m/s) | limp pole flag drifted 5–9 cm *against* a 0.06–0.5 m/s breeze (many near-equal folded states) |
| Settle damping, `settleSeconds` 8 for pole | pole reference not converged (swinging ±6 cm) |
| `initialWrinkle` (pole only, 1 cm) | perfectly flat in-plane sheet is a degenerate state |
| Pole `calmRestoreRate` 3/s | with the 10×6 grid the pole stayed 30 cm from home 12 s after calm |
| Alternating sweep direction in the solver | 17% local edge stretch in storms with one-direction Gauss–Seidel |

The wind formula is still exactly the spec's `0.5·ρ·|v_rel|²·A·(n·v̂)·n` (× profile `dragCoefficient`), with
`v_rel = wind − particle velocity` plus gust noise. The table lists additions, not replacements.

**D21 · Sqrt-free distance projection (Jakobsen).** `(d−r)/d ≈ (d²−r²)/(d²+r²)`: exact at rest, first-order
around it; large stretches are bounded by tethers. Per-constraint `k·w/(Σw)` precomputed; pinned–pinned constraints inert.
Measured −20% frame time. All tests unchanged.

**D22 · Default resolution and the performance budget.** Defaults: wall 6×10, pole 10×6, 5 iterations, 1/60 s step,
≤ 4 substeps/frame. `cloth-demo --mode bench`, 50 cloths **all awake** in gusty 9 m/s wind, one substep per frame:

| Config | mean | p99 |
|---|---|---|
| wall 7×12 / 6 it (initial) | 2.20 ms | 3.26 ms |
| wall 7×12 / 6 it + sqrt-free | 1.75 ms | 2.45 ms |
| **wall 6×10 / 5 it (default)** | **1.00 ms** | 1.54 ms |
| **pole 10×6 / 5 it (default)** | **1.02 ms** | 1.51 ms |
| LOD 4×6 / 4 it | 0.30 ms | 0.47 ms |

That is **core physics only, at the 1 ms budget, before rendering cost**. Phase 4 must therefore use: distance LOD
(4×6 at range), sleep (≈0 cost: calm flags do no work), indoor flags never woken, culling, and the 64-flag cap. If that
is not enough: stepping distant flags at 30 Hz with interpolation. The criterion will be measured in-game in Phase 4.
Machine: the user's PC, JBR 25.0.3. Bench numbers vary ±10% run to run.

## Phase 2 — adapter hello-jar (2026-11-05)

**D23 · Patch classes live in the exact `javaPkgName` package.** `PatchEngine.collectPatches` keeps a `@Patch` class
only if `patchClass.getPackage().getName().equals(packageName)` (`PatchEngine.java:706`). ZombieBuddy's
ModdingGuide example uses a `.patches` subpackage, which this code ignores. Our first build did exactly that, and the
offline probe (D26) reported `no patches in package pzmod.realflags`. Patch classes are now
`pzmod.realflags.Patch_*`. Global `@LuaMethod` classes have the same exact-package rule (`:720`).

**D24 · Capture-and-subdivide renderer.** Instead of re-deriving depth UVs (the original plan in D4), `SpriteMesh` runs
vanilla's own `texdModifier` once on a scratch `TextureDraw` covering the vanilla quad. It then emits sub-quads whose
positions, UVs, depth-texture UVs and second-texture UVs are bilinear interpolations of that captured quad. This is
exactly what the GPU interpolates across the single vanilla quad, and it also covers `TileSeamModifier`, which clips
positions and UVs. `TextureDraw.nextZ/nextChunkDepth` are restored before every sub-quad (D5). Falls back to vanilla
when: texture not ready, `tex.flip`, non-FBO path or weather mask, render state has cutaway textures or the wall-shader
slice (`RenderStateProbe`, via `privateLookupIn` VarHandles), or the modifier recolours corners.

**D25 · Hook shape.** `performRenderFrame` enter/exit (exit also on throwable) sets a one-shot context. Only the
**first** `Texture.render` inside it is replaced; the context is consumed, so the same draw is never replaced twice.
ZombieBuddy logs `Texture.render` as advised twice; with one-shot consumption that is harmless. The context is set
only when `FBORenderCell.instance.renderTranslucentOnly` (flags are only drawn in the per-frame pass) and the
**drawing sprite** (`@This`, not `obj.getSprite()`) is a registered flag. The hot path for non-flag draws is one static
read plus one identity-map probe for translucent-pass sprites only.

**D26 · Offline patch verification task.** `gradlew :pz-adapter:verifyPatches` starts a throwaway JVM with the
ZombieBuddy agent (`patches_jar=RealFlags.jar:pzmod.realflags`, `policy=allow-all`, private `config_dir` so the user's
approvals are never touched). It loads the target classes *without initialising them* and fails on agent errors.
Confirms all three patches apply to build 42.21.0's classes with `warmUp=false`. Run it after every game update.
Quirk: `patches_jar` is split on the first `:`, so it runs from the jar's directory with a relative path.

**D27 · Lua debug API, no Lua files.** `RealFlags_status()`, `RealFlags_debug(bool)`, `RealFlags_setEnabled(bool)`,
`RealFlags_wind()` — static `@LuaMethod(global=true)` on `LuaApi`, discovered by ZombieBuddy (D23 rule).

**D28 · Debug overlay texture.** `Texture.getWhite()` without an `isReady()` gate: it is built by a constructor, not
the asset manager, and vanilla draws it through the same four-corner path ungated (`AnimalZones.java:496`).

**D29 · Mod packaging.** `gradlew :pz-adapter:deployMod` syncs `build/mod/RealFlags` (template `mod/` + one jar with
cloth-core bundled) into `<modsDir>/RealFlags`; `Sync` only ever touches that folder. `check_mod.py`: 0 errors. Its
warnings are the three ZombieBuddy keys (read by ZombieBuddy, ignored by vanilla by design) and the missing poster.

## Phase 2 in-game evidence (2026-11-06, first run)
Source: `console.txt` (flushed up to 00:23) and the user's video `2026-11-06 00-26-37.mp4`. The video is 3.6 s,
1920×1080; frames were extracted with Windows' WinRT `MediaComposition`, since there is no ffmpeg on the machine.
- Load: jar approved; `Main` ran; ZombieBuddy found all 3 patch classes; `LuaApi` exposed; `FBORenderCell` patched
  on its in-game load; `flags.json` read (5 definitions). No `[RealFlags]` ERROR/WARN. The `-debug` launch option now
  reaches the game.
- Pole flag `street_decoration_01_16` (z+1) placed over `_24`: magenta overlay drawn exactly over the upper tile's
  trimmed sprite rectangle; sprite art inside undistorted and in place; the two pole halves line up.
- **Per-frame redraw confirmed**: the overlay's mean colour oscillates with a 2.0 s period (peaks at 0.5/2.5 s,
  troughs at 1.5/3.5 s), matching `0.25 + 0.15·sin(πt)`. The flag is in the per-frame layer and our draw replaces it every frame.
- Not yet covered: wall flags, picking, `RealFlags_status()` counters, overlay-off identity, WindSway trees, wind
  direction. Dark outlines on grass/sidewalk seen in the video also appear far from any flag; they are presumably
  from another mod or a debug render option, but this is unverified.

### Second run (2026-11-06, screenshots)
- Wall flags `walls_decoration_01_19` (E, `WallOverlay`+`attachedW`) and a US wall flag rendered **stippled**
  (about half the pixels missing in a regular pattern); still present with the overlay off. The pole flag (no wall
  behind it) was clean.
- `RealFlags_debug(false)` works and printed status. `RealFlags_status` typed without `()` is a Lua syntax error
  (`console:1: '=' expected`) — not a mod issue.
- Wind samples (`RealFlags_wind()`, rain falling): intensity 0.100 / 0.335 / 0.557, kph 12.0 / 40.2 / 66.8,
  angleDeg 341.7 ("SE") / 319.4 ("E") / 315.1 ("E"), angleIntensity −0.352 / −0.475 / −0.500. Screen slant of the rain
  not yet reported → convention still open. Note the game's own label calls 315° "E".
- WindSway tree sway was disabled by the user for this run, so coexistence was not checked.

**D30 · Wall-flag z-fighting: cause and fix.** Cause (traced, not guessed): for sprites with `WallOverlay` +
`attachedW/N`, `IsoSprite.setupTileDepth` calls `TileDepthModifier.setupWallDepth`, i.e. the **wall's own depth
preset**. In the cached chunk pass that's fine (overlay painted right after its wall). In the per-frame pass,
`startTileDepthShader` sets `glDepthFunc(GL_LEQUAL)` against the cached chunk depth, so flag and wall are at equal
depth and precision noise decides per pixel → stipple. It is caused by D12 (moving flags to the per-frame layer), not
by the sub-quad mesh. Vanilla's own remedy for the same situation in that method (object highlight, animated
attachments, fireplace fire) is to pull both depth bounds 5e-5 toward the viewer. Fix: new advice
`Patch_StartTileDepthShader` raises the `z2` argument by `5e-5 / 0.0028867084 = √3/100` for registered flag sprites
with `WallOverlay` in the per-frame pass. Vanilla's existing `farDepthZ/frontDepthZ -= (z2 − z)·0.0028867084` line then
applies exactly vanilla's bias. Idempotent (won't re-bias its own output). Verified offline to apply; in-game
confirmation pending.

**D31 · A/B toggles for in-game diagnosis.** `RealFlags_mesh(false)` draws vanilla's single quad (still in the
per-frame layer), isolating the mesh from the layer change. `RealFlags_depthBias(false)` reproduces the z-fighting.
`depthBiased` counter added to status.

### Third run (2026-11-06, screenshots with the D30 build)
- `depthBias=true` (5e-5): both wall flags render **solid**, except thin stippled diagonal bands at some edges (blue
  flag right edge and top-left; US flag top-left and lower right).
- `RealFlags_depthBias(false)`: both wall flags **vanish entirely**. `RealFlags_mesh(false)` with bias off: still
  invisible → the sub-quad mesh is **not** the cause; the per-frame layer + equal wall depth is (confirms D30).
- Picking (3.4): right-click on the flag square gives the vanilla Tile Report for `walls_decoration_01_19`. ✓
- Status: `fallbacks` all 0; `depthBiased` stops growing exactly when the bias is off; `frameContexts ≈ drawsReplaced`.
- Rain with `angleDeg=308.2 ("E")`, `angleIntensity=−0.538`, 70 kph: user's arrows show the rain slanting from upper
  right to **lower left** on screen.

**D32 · Wind direction convention (adopted).** World "blows-toward" vector = `(sin θ, cos θ)` with
`θ = getWindAngleDegrees()` in radians, world x = east, y = south, the same mapping vanilla `WorldFlares` uses for
drift (`WorldFlares.java:233`). Check against the rain sample: θ = 308.2° → (−0.79, +0.62). Screen x ∝ x − y
(`IsoUtils.XToScreen`, `IsoUtils.java:72`) = −1.40 → screen-left, matching the rain. The other candidate,
`(cos θ, sin θ)`, gives screen-right, which contradicts it. Caveat: one sample, negative `angleIntensity` branch only.
Re-check once with rain slanting right (positive `angleIntensity`, θ in 45°–225°).

**D33 · Wall-flag depth bias is tunable; default 2e-4.** Vanilla's 5e-5 left stippled edge bands because the flag's
and the wall's depth texels at the same screen pixel differ more toward the flag's edges. The cached pass never sees
this, because it draws with `GL_ALWAYS`. Default raised to 2e-4 (4× vanilla). For scale, one z-level of height is
0.0028867 depth units, so 2e-4 is about 1/14 of a level. Lua `RealFlags_depthBiasAmount(x)`, clamped to [0, 0.003].
**The value to ship is the smallest one that clears the bands without drawing the flag over objects just in front of
the wall**, to be found in-game.

User check (2026-11-06): the 2e-4 default clears the edge bands and does not overdraw → **shipped value 2e-4**.

## Phase 3 — pole + wall flag animated (2026-11-06)

**D34 · Projection constants (traced).** `IsoUtils.XToScreen/YToScreen` give screen = ((x−y)·32, (x+y)·16 − z·96)·tileScale.
The 2:1 ground diamond means a 30° camera elevation, so 1 vertical metre = 16√6·tileScale px and one level = √6 ≈ 2.449 m
(PZ walls are 8 ft). The simulation uses metres, x = east, y = south, z up. `Projection.java`, unit-tested against
IsoUtils.

**D35 · Measured sprite geometry → flags.json v2.** Wall cloth parallelograms were measured from the extracted 2×
sprites: top-edge slope +0.53/+0.48 for S-facing `_16/_18` (u = +x) and −0.51/−0.52 for E-facing `_17/_19` (u = −y),
confirming the wall axes. Pole: finial rows 151–157; the upper shaft is cols 61–64 but is covered by the painted cloth
at rows 158–169 and partly below. The lower tile `_24` has a clean 6-px shaft (cols 61–66) reaching upper-frame row 221. So
the pole is rebuilt from this sprite's finial plus a clean 64-px shaft slice of `_24` (no stretch), which joins the
real lower tile. This **resolves the earlier "z-level offset" open item**: the upper frame is exactly 192 px (one
level at 2×) above the lower. Schema reference: `docs/FLAGS_JSON.md`.

**D36 · Wall rendering = vanilla image + displacement field.** The render mesh (default 6×10) covers the sprite
rectangle. Each vertex finds its (u, v) on the wall cloth by inverting the wall parallelogram mapping (clamped
outside it) and adds the bilinearly sampled projected `(pos − ref)`. Render resolution is decoupled from simulation
resolution. Per-vertex shading `1 − 0.35·(1 − |n·n_ref|)` is exactly 1 at rest. Zero wind → identical to vanilla (the
cloth sleeps exactly on its reference, D18).

**D37 · Pole rendering.** Static quads first (finial + shaft), then the DGVFlags canvas at the cloth particles'
absolute screen positions (anchor + projected `pos − origin`). Shading `0.72 + 0.28·|n·L|` with L toward the iso
camera side. Canvas depth is a single texel of the pole's depth map at `depthPoint`, so the flag sorts with its pole.
Known limitation: the flag always draws in front of its own pole, even when it swings behind it.

**D38 · Wind and time per flag.** `WindProvider` (once per frame) converts `getWindspeedKph()/3.6` at
`getWindAngleDegrees()` to a world vector (D32). Each `FlagInstance` has its own `WindForce` seeded by square
coordinates (gust and turbulence phases differ between flags) and scaled by `exposure` (walls 0.35, poles 1.0). Wall
flags on non-outside squares get 0, so they never wake. Steps once per `IsoWorld.getFrameNo()` with real seconds,
0 while paused (D7). Dev override: `RealFlags_forceWind(kph, deg)` / `RealFlags_forceWindOff()`.

**D39 · Instances.** `WeakHashMap<IsoObject, FlagInstance>`, created lazily on first draw (the instance holds no
reference to its object). Creation runs the cloth's settle: ≈10 ms for a pole flag, once per flag. To do in Phase 4:
share the settled pose per FlagDef, cap, culling, LOD, eviction.

**D40 · Debug overlay off by default** from Phase 3 on (`RealFlags_debug(true)` to show it).

### Phase 3 in-game evidence (2026-11-06)
Source: user's screenshot plus four clips (`step-2`, `step-3`, `step-4-1`, `step-4-2`; frames extracted every 0.5 s
with WinRT; the forced-wind commands were read from the in-game console in each clip). The user reports that the
remaining steps (5 pause, 6 return to vanilla, 7 indoor, 8 climate wind, 9 picking/no flicker, 10 status) pass.
- **Direction, both halves of the compass:** `forceWind(40, 308)` → pole flag streams to screen-left (same as the
  rain); `forceWind(40, 135)` → it swings round and streams to screen-right. **D32 is confirmed for both signs of
  `angleIntensity`.**
- **Strength:** 10 km/h → partly lifted, drooping diagonally (matches the Phase 1 sweep: ≈40% extension at 3 m/s);
  40 km/h → extended and waving; 70 km/h → nearly flat, streaming.
- **Wall flags, physically consistent by direction:** the S/gable US flag lifts off its wall at 308° and stays flat
  at 135° (wind into the wall → plane clamp); the blue E-wall flag does the opposite. Exposure 0.35 gives clear
  but not extreme motion at 70 km/h → **keep 0.35**.
- **Pole quality:** canton at the hoist, shading follows folds, no visible stretch, pole continuous finial → shaft →
  lower tile. The limp-fold stretch concern from Phase 1 is not visible with the real texture → closed.
- Status line: `instances=11`, `reclassified=10`, `failed=false`, wall depth bias 2e-4 active.
- Minor visual note: under strong wind the wall flag's *painted* top edge appears to tilt a little, because the art's
  top edge is a draped curve while the pinned line is straight. Acceptable for the MVP; can be refined with a
  per-sprite `cloth` y-offset.

## Phase 4 — instance manager, overlay, failure path (2026-11-06)

**D41 · Shared settled pose.** `Cloth(Cloth prototype)` copies the prototype's reference/positions and skips the settle.
`Prototypes` keeps one settled cloth per (FlagDef, LOD), lazily. A new flag instance is an array copy: the ≈10 ms
per-pole hitch (D39) now happens once per flag *type*. Test: the copy equals a fresh settle bit for bit, before and
after stepping.

**D42 · Level of detail with continuous motion.** Each FlagDef has a far profile: wall 4×6, pole 6×4, 4 iterations;
`physics.lodGrid` overrides it. The metric is planar tile distance from the camera character + 2 tiles per level of
height difference, × max(1, zoom-out). FAR beyond `lodDistance` (12) + 1, NEAR below 12 − 1: a hysteresis band.
`Cloth.resampleFrom` bilinearly resamples positions and previous positions (so velocity too), so switching never
resets a flying flag. At FAR the wall render mesh is also halved (6×10 → 3×5 quads).

**D43 · Cap = nearest N simulate.** `Scheduler` records each drawn instance's distance; at the next frame the nearest
`maxActive` (64) may step, the rest hold their pose (frozen, still drawn). Ranking uses preallocated arrays and an
in-place sort of at most a few hundred floats. New instances are allowed until ranked.

**D44 · Culling is structural.** Instances are only stepped from their own draw call, so anything the engine doesn't
draw (off-screen, unloaded, behind cutaways) costs nothing. A distance gate (`simDistance` 45 tiles) freezes the rest.
Eviction every 128 frames: not drawn for `evictFrames` (600 ≈ 10 s) → dropped. The WeakHashMap also drops instances
of unloaded objects.

**D45 · Calm wall flags hand back to vanilla.** A sleeping wall cloth is exactly on its reference, and our mesh at
rest is identical to vanilla's single quad (Phase 2: `mesh(false)` comparison). So the real flag returns false and
vanilla draws its one quad: the cheapest possible draw. Pole flags always draw (no vanilla equivalent).

**D46 · Debug overlay** (`RealFlags_overlay`): drawn on exit of `UIManager.render()` (new patch, static, no overloads,
`strictMatch`) with `TextManager.DrawString(UIFont.Small, …)`. Text is rebuilt 4×/s (no per-frame string allocation).
CPU time = `System.nanoTime` around our whole flag hook per frame (simulation + geometry + submission), with a
60-frame avg/max. Not included: the cheap advice checks on non-flag draws, and GPU time.

**D47 · Stress mode for the 50-flag measurement** (`RealFlags_stress(n)`): each real flag is also drawn n−1 times as
offset ghosts, each with its own cloth and seed, through the real render pipeline. This measures the true per-flag
cost without placing objects in the user's save. Ghosts never take the calm→vanilla shortcut (they always draw).

**D48 · Failure path exercisable in-game** (`RealFlags_testFailure()`): the next flag draw throws inside the hook,
exercising the real fail-once → disable → invalidate → vanilla path.

### Phase 4 in-game measurements, run 1 (2026-11-06, user's PC, overlay screenshots)
CPU = our hook's time per frame (simulation + geometry + submission), 60-frame avg / max. Forced wind 40 km/h @135°,
`RealFlags_stress(10)`.
| Scenario | Drawn | Simulated | Avg | Max | Per flag |
|---|---|---|---|---|---|
| Near LOD forced (`lodDistance(100)`) | 30 | 30 | 0.906 / 0.897 ms | 2.36 / 2.19 ms | ≈30 µs → **≈1.5 ms for 50 ✗** |
| Far LOD forced (`lodDistance(0)`) | 30 | 30 | 0.279 ms | 0.449 ms | ≈9.3 µs → ≈0.47 ms for 50 ✓ |
| Zoomed out, default LOD | 120 | 60 (cap) | 1.143 ms | 1.725 ms | 100 coarse, 50 frozen |
| Calm, zoomed out | 90 | 0 | 0.339 ms | 0.642 ms | 7 handed back to vanilla |
Behaviour: cap ✓ (`setCap(10)` → frozen = drawn − 10; at 64 only 60 stepped), eviction ✓ (`evicted=45`), shared
prototypes ✓ (`prototypes=8` for 56 created), calm→vanilla ✓, failure path ✓ (`RealFlags_testFailure()` → exactly
one `[RealFlags] ERROR in Texture.render … disabled for this session` + full stack, then vanilla). Found:
`fallbacks canvas=21`, i.e. the pole showed its vanilla furled flag for about 20 frames on first sight while the
DGVFlags canvas was loading.

**D49 · Cheaper near LOD to meet the budget.** In-game near cost was ≈30 µs/flag, of which ≈22 µs was simulation
(offline bench). New built-in defaults: wall **5×8**, pole **8×5**, **4 iterations**, wall render mesh 5×8.
Offline bench, 50 awake: wall 22.1 → 11.0 µs, pole 23.8 → 12.0 µs per cloth. Steady-wind response is essentially
unchanged (sweep), and the limp pole fold's edge stretch dropped from 18% to 8%. Visual check in the demo render: still
reads as a flag. Expected in-game: about 19 µs per flag → ≈0.95 ms for 50 at full detail (needs re-measuring). Far LOD is
unchanged.

**D50 · Pole pose memory 3 → 5 /s.** With the 8×5 grid, `poleReturnsHomeAndSleeps` failed: a storm from 240° left
the cloth in another limp fold, 23 cm from home after 10 s. New `cloth-demo --mode calm` probe (storm from 12
directions → calm → time to sleep exactly on the reference): rate 3 → 1 of 12 stuck; **rate 5 → 0 of 48 stuck
(12 directions × 4 seeds), worst 3.5 s**; rate 8 → 2.7 s but risks a visible snap. Wall flags: worst 4.3 s.

**D51 · Preload textures.** Canvas and static-quad textures are requested as soon as flags.json loads, so the
async load has finished before the pole is first drawn (fixes the `canvas=21` flash).

### Phase 4 in-game run 2 (2026-11-06, D49–D51 build)
User report: all Phase 4 checks pass, including section D (50 flags at full detail, target avg < 1.0 ms) and no
canvas flash. **The exact overlay figures were not provided**, so the measurement table in the final report quotes run 1
plus this pass/fail statement. Expected from the offline numbers: ≈0.95 ms for 50 at full detail (D49).

## Phase 5 — documentation (2026-11-06)

**D52 · Data-only flags from any mod (implements D15).** Writing the README showed that D15 was never implemented: the
registry read only the one `media/realflags/flags.json` the game resolves. A second mod shipping that path would have
*replaced* ours, and other file names were ignored. Now `FlagRegistry` walks `ZomboidFileSystem.getModIDs()` (load
order) → `ChooseGameInfo.getModDetails(id).getCommonDir()/getVersionDir()` → `media/realflags/*.json` (sorted by name)
and merges them; for the same sprite a later file wins. Falls back to the resolved single file. Unit-tested with temp
mod folders; in-game confirmation is the log line `loaded N flag definitions (M entries) from K file(s)`.

**D53 · `tools/flagtool.py`.** `extract` (PZPK v1 reader, verified on Tiles2x.pack) and `measure` (cloth
parallelogram + facing from the top-edge slope). It reproduces the shipped entries exactly for `_16` (S) and `_19` (E).

**D54 · README.md** covers install, build (properties, Gradle tasks), the ZombieBuddy rules this mod relies on, how it
works, adding flags (wall step by step, pole), tuning (Lua, per-flag data, offline harness), measured performance and
known limitations.

## Phase 6 — interaction with the world (2026-11-09)

Request: simulating flags interact with the environment, players and vehicles; a wall flag blocked by an item taller
than 1.5 cells does not simulate. Evidence below is from build 42.21.0's `projectzomboid.jar`, decompiled with
Vineflower 1.12 into a scratch directory (not committed), and from `media/tileGeometry.txt`.

**D55 · Colliders live in cloth-core.** New `Colliders`: vertical cylinders and vertical boxes with a horizontal
orientation, each with a horizontal velocity, flagged *dynamic* (characters, vehicles) or *static* (scenery). Contact is
position-based: after the constraints a particle inside a body moves to the nearest surface (side, top or bottom), then
`friction` (0.35) of its motion relative to the body *along* that surface is removed, so a body walking through drags
the cloth along. Verlet turns the projection into velocity. Order per step: constraints → bodies → wall plane clamp
(nothing goes through the wall). A sleeping cloth wakes when a dynamic body touches its reference bounding box; static
bodies never wake it. A cloth in contact never falls asleep (sleep snaps to the reference, which would put it through
the body). `Cloth.resetToReference()` added. Allocation-free per step; 7 new tests (`ColliderTest`).

**D56 · World placement of the cloth.** The cloth's frame is axis-aligned with the world (Prototypes), so world =
origin + cloth position. Origin from flags.json pixels: the square's north floor corner is at 2× frame pixel (64, 192)
(`IsoObject.offsetX/offsetY` = 64/192, `IsoObject.java:257`), and a floor point (dx, dy) draws at
(64 + 64(dx − dy), 192 + 32(dx + dy)). Wall S: north wall line, dx = (clothX − 64)/64; wall E: west wall line,
dy = (64 − clothX)/64; height = vertical pixels from that floor point / 32√6. Pole: the shaft stands at the square centre
— `street_decoration_01_24`'s opaque pixels end at frame row 228 (offset 29 + 200 px, `flagtool.py extract`), the centre
row is 224. Test: the origin projects back exactly onto each shipped flag's measured pixel. Result: wall flags hang
from ≈2.0 m down to ≈0.87 m, so a standing character (1.8 m) brushes them.

**D57 · Object heights from Build 42 tile geometry.** B42 ships per-tile solid shapes for depth rendering
(`media/tileGeometry.txt`, 22 596 tiles; boxes, cylinders, polygons; mods add their own `tileGeometry.txt`).
Units, traced: values ×10000 (`TileGeometryFile.COORD_MULT`), then **metres with y up** — `walls_exterior_house_01_0`
is 2.4495 = √6 tall (one level), `appliances_refrigeration_01_0` 1.55. x/z are centred on the square (west wall at
x = −0.4765, north wall at z = −0.4767). Placement `translation(t).rotateXYZ(r°)` (`TileGeometryFile.java:606`);
cylinders along local z, ±h/2 (`CylinderUtils.java:20`); lookup `TileGeometryManager.getGeometry(modID, tileset,
index % 8, index / 8)`, last mod wins. `Scenery` turns each primitive into a square-local AABB, cached per sprite.
Objects raised on a surface add `renderYOffset·2 / 32√6` m. **Fallback** for sprites without geometry (modded furniture):
the top of the opaque pixels above the wall's floor line where the picture overlaps the cloth, assuming the object
stands against that wall; counted as `blockedByEstimate` in the status.

**D58 · Blocked wall flags (the 1.5 rule).** "1.5 cells" read as 1.5 tiles = **1.5 m**, in this project's convention
(1 tile = 1 m; one level = 2.45 m). A wall flag is *blocked* when an object on its own square has a solid box that
(a) tops out above 1.5 m, (b) reaches below the cloth's top edge, (c) is within 0.5 m of the flag's wall and (d)
overlaps the cloth's span along the wall. Walls, windows, doors, other wall decorations (WallOverlay/attached*),
floors and loose items (`IsoWorldInventoryObject`) never block. A blocked flag is not simulated at all: its cloth is put
back on the reference and vanilla draws its single quad. Fridges (1.55 m), wardrobes and tall shelves block; counters
and tables (≈0.9 m) don't — they become scenery the flag can drape over (D59). Re-checked every ~64 frames per flag
(staggered), so placing or picking up furniture takes effect within about a second. Tunable: `RealFlags_blockHeight(m)`,
0 = never blocked. Test: `InteractionsTest.fridgeInFrontOfAWallFlagBlocksIt` with the fridge's real geometry.

**D59 · Scenery colliders.** Static AABBs from the tile geometry of objects within the cloth's reach (rectangle
diagonal from the origin), on the flag's level and the one below. Skipped: the flag itself, other registered flags,
loose items, anything behind a wall flag's own wall plane, and **any shape the reference pose already touches** — the
vanilla art already shows the flag there, so zero wind stays pixel-identical to vanilla, and the flag's own wall is
dropped this way (the plane clamp handles it). At most 14 per flag. Result: a pole flag no longer swings through the
building beside it; a wall flag billowing over a counter lands on top of it.

**D60 · Bodies and air wash.** Every simulated frame (far-LOD flags every 4th frame):
- **Players and zombies** from the moving-object lists of the squares in reach: cylinder r = 0.25 m, 1.8 m tall
  (0.6 m when crawling or sitting on the ground). Dead ones and players inside a vehicle are skipped. Velocity =
  `getMovementLastFrame()` / real frame seconds (`IsoMovingObject.update`, `IsoMovingObject.java:1123`).
- **Vehicles** from `IsoChunk.vehicles` of the chunks within reach + 5 tiles (each vehicle is listed in the chunk of its
  centre, as `IsoGridSquare.getVehicleContainer` does): oriented box from `getPoly()` (world footprint corners), from
  0.15 m above ground to `script.getExtents().y + 0.4` m (body height plus wheels; approximate). Velocity:
  `getLinearVelocity()` (physics x/z = world x/y, `BaseVehicle.java:1935`), else `getCurrentSpeedKmHour()` × forward
  vector for remote vehicles without physics.
- **Air wash** added to the flag's wind (not reduced by a wall's exposure, it is local air): moving vehicles within
  3 m of the cloth centre add 0.6·v·(1 − d/3)² plus a sideways bow wave 0.25·|v|·(1 − d/3)² away from the vehicle;
  characters running faster than 2.5 m/s within 1.2 m add 0.35·v·(1 − d/1.2)². Capped at 20 m/s. So an indoor wall flag
  (no climate wind) still stirs when someone runs past, and a car speeding past a pole flag makes it snap.
- Bodies behind a wall flag's wall (other room, outside) are ignored.
Animals are not included. `RealFlags_interactions(false)` restores the 0.1.0 behaviour (wind only).

### Phase 6 status
Built, 49 unit tests pass (12 new), `verifyPatches` applies all five patches to 42.21.0, deployed
(jar SHA-256 `fb6e4380112ebaac8eed9bce291ef173ac8544cce198314e87d2e203cb31088d`). **Not yet run in-game**: no collision, blocking or wash behaviour and no CPU cost has
been observed in the game. Test sheet: `docs/PHASE6_TEST.md`.

## Phase 6b — in-game feedback on 0.2.0 (2026-11-09)

User report after playing 0.2.0: (1) the flag returns to idle too quickly after an interaction; (2) when a character
interacts with it, the flag sinks into the wall instead of staying in front; (3) the flag is invisible when the
character is inside the building — use that as the cue for a thicker wall collider.

**D61 · "Sinks into the wall" was depth, not physics; fixed per vertex. Plus a thick wall.**
*Cause, traced:* the wall plane clamp kept every particle at w ≥ 0 (tests), but wall-flag vertices kept their rest-pose
depth (D24/D36). Fragment depth = `mix(zDepthBlendZ, zDepthBlendToZ, d)` with `d` sampled from the depth texture at the
vertex's depth UV (`media/shaders/tileWithDepth.frag`); per-quad `TextureDraw.z` is not used by that shader.
`IsoDepthHelper.calculateDepth` makes a point nearer by 0.00144 per tile of x+y and 0.00118 per metre up. Wind moves cloth
*out* of the wall, which lands on screen over *farther* wall pixels — always visible. A body pushes cloth *along* the
wall (up, sideways); after ≈0.14 m toward the camera or ≈0.17 m up, the wall pixels under it are nearer than the vertex's
rest depth + the 2e-4 bias, and the wall hides it. That matches the report and why 0.1.0 wind never showed it.
*Fix:* `WallDepth` computes, from each vertex's screen offset, the depth of the wall surface it is now drawn over; if that
is nearer than its rest depth, the vertex samples its depth UV that much higher up the same wall (`SpriteMesh.drawWall`),
so it gets exactly the wall's depth there and the existing bias keeps it in front. Characters in front still hide it
correctly. Out-of-wall motion keeps the rest depth (0.1.0 behaviour). The lift stays inside the cloth's own rectangle;
any part a vertex near the pinned top could not get becomes extra bias for that flag on its next draw
(`Hooks.onStartTileDepthShader`, capped at 6e-4 per D33). `WallDepthTest` checks the math against the game's formula on
both wall orientations.
*Thick wall (the user's cue):* the flag's own wall now has a thickness — from its tile geometry (the box touching the wall
line: 0.0466 m for house walls; 0.047 m if none) + 0.03 m of cloth. (a) Bodies never push cloth *into* the wall: when the
nearest exit from a body points into the wall, the cloth leaves sideways along the wall, over the body or under it
(`Colliders`, wall-aware exits for cylinders and boxes). (b) Cloth that a body has moved along the wall stands off it by
min(thickness, 0.5 × along-wall displacement), so bunched cloth sits proud of the wall; the rest pose is untouched
(zero displacement → zero standoff), and wind-only motion is unaffected (applies only after a contact). Bodies behind the
wall plane (inside the building, for an outside flag) were already ignored (D60) and still are.

**D62 · Gradual settling after an interaction, and no more stuck folds.**
*Settling:* after a body touches a cloth, (a) pose memory starts at 50% and smoothsteps to full over 3 s instead of
pulling at once, (b) it may not fall asleep for 4 s, and (c) the final snap waits until every particle is within 4 mm
(under a pixel) instead of 2 cm. Wind-only behaviour is unchanged. `Cloth.setContactSettling(ramp, floor, settle, snap)`.
*Why a 50% floor, not 0:* measured with a probe (body walking through at 3 m/s from 12 directions, then calm): starting
pose memory at 0 left the limp pole flag in another fold in 4 of 12 directions — D50's finding that it needs strong memory
to find its own fold.
*Stuck folds (a 0.2.0 bug found by the same probe):* with 0.2.0's settings the **wall** flag stayed 20–29 cm off its
rest pose for ever in 4 of 12 directions: the push folds it against the wall, and its weak pose memory (0.5/s) cannot
beat the constraints holding the fold. Now, after 3 s (4 s after a contact) of no wind and no contact, pose memory grows
×1.5 per second up to ×20, so a fold lets go smoothly instead of never.
*Measured (probe, 12 directions):* nothing stuck for either flag; at 0.5 s after release the pole is still 0.19 m off
home (0.2.0: 0.06 m) — a visibly gentler return; worst time to rest: pole 5.9 s (0.2.0: 3.3 s), wall 9.9 s (0.2.0:
never, in 4 directions). Regression test `ColliderTest.neverStuckAfterWalkThrough`.

### Phase 6b status
0.2.1 built, 57 unit tests pass (8 new), `verifyPatches` applies all five patches, deployed (jar SHA-256
`be28a315f3aff84783bcdf2961d17fc44ae8153ca520ed3ff28abb69227dcfb4`). **Verified in-game by user report
(2026-11-09): "it works"** — the three reported issues are resolved. Per-step results of `docs/PHASE6_TEST.md` and
CPU figures were not recorded.

## Phase 7 — curtains (2026-11-09)

Request: physics for every curtain inside buildings, simulated only while its window is **open or broken**.
Evidence from build 42.21.0's decompiled source (pz-mcp-server `pz_read_java`, Vineflower) and the game's `media/`.

**D63 · What a curtain is (traced).** `IsoCurtain extends IsoObject`; its orientation is the tile type
`curtainN/S/W/E` (`IsoObject.getType()`, set from the tile property of the same name). Vanilla tiles:
`fixtures_windows_curtains_01_*` and `_02_*` (144 tiles; `newtiledefinitions.tiles.txt`), each row of 8 = closed
W/E/N/S then open (+4). `IsoWindow.HasCurtains()` (IsoWindow.java:152) defines where they hang:
`curtainN`/`curtainW` on the window's own square, `curtainS`/`curtainE` on its opposite square. So, from the curtain:
N → window on (x, y) north; W → (x, y) west; S → (x, y+1) north; E → (x+1, y) west. Curtains are plain sprites
(`IsoCurtain.render` → `super.render`); only `fixtures_windows_curtains_01_16` has a 3D sprite model
(`spriteModels.txt`, `SheetDoorClosed`), used only on doors (`curtainOffset` comes from a door sprite) — doors have no
window, so it is never simulated. Curtains have their own depth textures (`tileDepthTextureAssignments.txt`), not the
wall preset.

**D64 · A curtain is a wall cloth with two more facings; its geometry is measured at runtime.** Same renderer as wall
flags (vanilla image + displacement field, D36), so a calm curtain is pixel-identical to vanilla. New facings
`N` (curtainS, on the south edge, room to the north) and `W` (curtainE, east edge, room to the west): same screen axis
as S/E, origin on the far edge, **normal flipped** (`AnchorFrame.flipNormal`) so the plane clamp keeps the cloth on the
room side. No data file: the cloth parallelogram comes from the sprite's trimmed rectangle (`Texture` offset/size in the
2× frame): pinned top-left = trim top-left (+½·width down for W/E-axis curtains, whose top edge rises to the right),
height = trim height − ½·width. Measured vanilla trims fit: e.g. `_01_2` (curtainN) trim x 59–122 on the north wall
line 64–128. Curtains under 36 px (≈0.46 m) of cloth stay vanilla — rolled-up sheets (`_01_20..23`) and rolled
blinds (`_02_4..7`) are bundles, not hanging cloth. Mods' curtains work the same way (detected by tile type).

**D65 · Only open or broken windows (the request).** Window state, per curtain: `IsoWindow` on the square above →
destroyed (smashed or glass removed) = 1.0, open = 0.6 (a sash is a half opening), closed = 0; barricaded × 0.35;
an empty `IsoWindowFrame` (no window object) = 1.0; nothing found = 0 (e.g. sheets on doors). Player-built
`IsoThumpable` windows are not handled (limitation). A curtain whose window is closed is not reclassified: it stays
in vanilla's cached chunk layer at zero cost. If the window closes while it is moving it keeps settling (no wind, no
bodies) until asleep, then vanilla draws it again — no snap.

**D66 · Re-classification on window changes.** Windows are always in vanilla's per-frame layer, so vanilla never
invalidates the chunk when a window opens. Three new advices call `IsoObject.invalidateRenderChunkLevel(256)`
(`DIRTY_OBJECT_MODIFY`, what `IsoCurtain.ToggleDoor` itself uses) on `window.HasCurtains()`:
`IsoWindow.ToggleWindow(IsoGameCharacter)` exit, `IsoWindow.smashWindow(boolean, boolean)` exit (the 0/1-arg
overloads delegate to it), `IsoWindow.syncIsoObjectReceive(ByteBufferReader)` exit (state from the server in MP).
Opening/closing the curtain itself already invalidates (`IsoCurtain.ToggleDoor`, `invalidateRenderChunkLevel(256L)`).

**D67 · Wind through the window.** Only if the square on the other side of the window `isOutside()`. With the
climate wind w scaled by `curtainExposure` (0.5) × openness: inflow = w·n (n = the room side); the cloth is driven by
n·(max(inflow, 0) + 0.15·|w|) + 0.3·(w − (w·n)n): wind into the room billows it, a leeward window still breathes
(the 0.15 draft), wind along the wall sways it. Wind out of the room presses it to the window plane (clamp), as in
reality. Bodies and air wash act as for flags (D60), but only while the window is open/broken.

**D68 · Curtain profile.** `ClothProfiles.CURTAIN`: 5×9 grid, 0.3 kg, stiffness (1, 0.8, 0.03) — softer bend than a
flag so it folds — damping 0.03, Cd 0.8, top edge pinned (the rod), clamp to the window plane; far LOD 4×6. Never
"blocked" by furniture (the 1.5 m rule is for flags hung *on* walls; furniture under a window is normal), but furniture
is scenery it drapes over.

**D69 · Depth.** Curtains get the same toward-viewer bias as wall-overlay flags in the per-frame pass (D30/D33), as a
precaution: they are drawn with `GL_LEQUAL` against the cached wall depth at the window line. Not yet observed in-game.

**D70 · Controls.** `RealFlags_curtains(bool)` (default on; off re-bakes curtains as vanilla),
`RealFlags_curtainExposure(x)` (default 0.5); status shows `curtains{defined layer closedDraws rebakes windowEvents}`.

**D71 · The pz-mcp-server decompile is not build 42.21.0 (found while building).** Its source
(`D:\ZomboidDecompiler\…\source`) has `IsoGridSquare.getWindow(boolean)`, `IsoWindow.getNorth()` and a 1-argument
`syncIsoObjectReceive`; the installed jar has `getWindow(GridSquareEdge)`, `isNorth()` and
`syncIsoObjectReceive(ByteBufferReader, UdpConnection)` (javac errors, then reflection on the jar). Every curtain-related
body was therefore re-read from the **installed jar**, decompiled with Vineflower 1.12 into a scratch directory:
`IsoWindow.HasCurtains`, `ToggleWindow`, `syncIsoObjectReceive` (no render-chunk invalidation in either), `IsoCurtain.render`,
and `IsoGridSquare.getEdgeElement` — `getWindow(SOUTH/EAST)` returns the north/west window of the square to the
south/east, which is exactly a curtainS/curtainE's window. Treat that MCP source as a lead, not evidence, for this build.

## Phase 7b — player sheets, light through curtains, metal barricades (2026-11-09)

Request: (1) sheets hung by the player do not move; (2) a moving closed curtain at an open window should make the room's
light fluctuate subtly, without excessive flicker; (3) a window barricaded with metal sheets is a closed window.
Evidence: the installed 42.21.0 jar, decompiled with Vineflower 1.12 into the scratchpad (D71).

**D72 · Sheets are 3D models; while their window is open they are drawn as their 2D sprite.** `IsoWindow.addSheet` places
`fixtures_windows_curtains_01_16..23` (open = rolled 20..23, closed = hanging 16..19). All eight have a sprite model in
`media/spriteModels.txt` (`Base.SheetDoorClosed` / `SheetDoorOpen`), and window tiles carry a `CurtainOffset` in
`tileGeometry.txt` (`TileGeometryFile` → `IsoSprite.setCurtainOffset`; 286 tiles incl. `fixtures_windows_01`).
`IsoCurtain.render` draws the model when `curtainOffset != null && getSpriteModel() != null`, and `IsoObject.render` →
`renderModel` returns before the sprite path, so `Texture.render` (our hook) is never reached: that is why sheets never
moved. Deforming the model is out of reach; instead a new exit advice on `IsoObject.getSpriteModel()` returns null for a
curtain **we have moved to the per-frame layer** (window open/broken or still settling), so the vanilla sprite path draws
its 2D sheet sprite and our cloth mesh bends it. Behind a closed window it is the vanilla 3D model again. Cost of the
advice for every other object: one null check and one `instanceof`. Visible consequence: when the window opens the sheet
switches from the 3D model to the 2D sheet art (and back after it settles once the window is closed). A rolled-up sheet
(open state) is too short to hang (D64) and stays the model.

**D73 · Light through a moving closed curtain = one dim lamppost light per curtain, changed at most 4×/s.** Vanilla
gives a *closed* curtain a fixed light transmission (default 0.33, `LightFilterIntensity`) uploaded per chunk in
`LightingJNI.updateChunk`; changing that per frame would mean re-uploading chunks. The vanilla precedent for a light that
varies is `IsoTelevision`: one `IsoLightSource` added with `IsoCell.addLamppost`, recoloured later with
`IsoGridSquare.recalcLightTime = -1` and `GameTime.lightSourceUpdate = 100`; `LightingJNI.checkLights` pushes the new
colour (`setLightColor`, ×2) when `r/g/b` differ from what it sent. Lampposts are not saved (`IsoCell` only clears them).
Our light: on the curtain's own square (the room side for all four facings), radius 3, local to the building, warm
daylight tint. Strength = gain (0.12) × outside sky light (`GameTime.getSkyLightLevel`) × smoothstep(gap), gap = mean
outward lift of the cloth / 0.35 m. Only for a **closed** curtain (an open one already lets the light in) at a live,
outdoor window and with the cloth awake. Anti-flicker: exponential smoothing (τ = 0.35 s), quantised to 1/48, pushed at
most every 15 frames and only when the quantised value changes; faded out to 0 then removed when the cloth rests,
the window closes, the curtain is no longer drawn (sweep every frame from the UI hook), or the mod/curtains are turned
off. Lua: `RealFlags_curtainLight(bool)`, `RealFlags_curtainLightGain(x)`.

**D74 · Barricades.** `IsoBarricade` (jar): `isMetal()` = metal sheet (`getLightTransmission` 0), `isMetalBar()` = bars
(transmission 1), `getNumPlanks()` 0–4. A window's barricades on either side (`getBarricadeOnSameSquare/OppositeSquare`)
multiply its openness: metal sheet → 0 (**fully closed**, as requested), bars → 1, planks → 1 − 0.2·n. Replaces D65's
flat ×0.35. Adding/removing a barricade adds/removes an object, which re-renders its chunk; the draw-time gate (D65)
catches anything that is missed: the curtain settles and is re-baked.

**D75 · Bookkeeping.** A curtain that is classified as not animating is dropped from the reclassified set at once (so the
model suppression of D72 ends exactly when vanilla's layer is used again).

**D76 · Correction (in-game report 2026-11-09: player sheets still did not move). Curtains are already per-frame in
vanilla.** Every tile of `fixtures_windows_curtains_01` has `Translucent = true` in `tileGeometry.txt`;
`TileGeometryFile` turns that into `sprite.depthFlags |= 2` (jar, decompiled), and `calculateObjectRenderLayer` sends
such objects to `Translucent`/`TranslucentSE`, never `MinusFloor`. So our layer hook never sees a curtain:
- Ordinary curtains worked anyway, because the draw-time gate (D65) decides — that is what the user saw working.
- D72's model switch was keyed on "objects we reclassified", a set no curtain ever entered, so a sheet always drew its 3D
  model. **Fix:** `onGetSpriteModel` decides itself — curtain definition + window open/broken, or cloth still settling
  (`curtainAnimates`) — cached once per frame per sheet, only on the render thread (set from the render hooks). First
  use logs `first sheet drawn as its 2D sprite`.
- D65's "a curtain behind a closed window stays in the cached layer at zero cost" is wrong for vanilla curtains: they
  were always drawn every frame by vanilla; ours adds one window lookup per draw and returns to vanilla's quad. The
  re-bake/invalidation code stays for modded curtains without `Translucent` (harmless otherwise).

### Phase 7 status
0.3.0 built, 66 unit tests pass (9 new), `verifyPatches` applies all nine advices (three on `IsoWindow`, one on
`IsoObject.getSpriteModel`) to 42.21.0, deployed. In-game (user, 2026-11-09): curtains, light and barricades work as expected; player sheets did not move →
D76 fix, jar SHA-256 `7b1616d6db63482b26aabe5fb4090a7507bb864f99a1ef3294f9d053be093f59`. **D76 verified in-game by user report (2026-11-09): player sheets now move.** Per-step results and CPU figures of `docs/PHASE7_TEST.md` were not recorded.

## Phase 8 — fabric decorations (2026-11-10)

Request: cloth physics for the items made of fabric on `location_restaurant_pileocrepe_01`, `location_restaurant_pie_01`,
`location_restaurant_bar_smutz`, `location_military_knox_01`, `location_entertainment_theatre_01`,
`location_community_medical_01`, `location_community_church_small_01` and `walls_decoration_01`. Evidence: every sprite of
the eight atlases extracted from `Tiles2x.pack` (build 42.21.0, 1075 tiles) and inspected; tile properties from
`media/tile_definitions/newtiledefinitions.tiles.txt`; shapes from `media/tileGeometry.txt`; depth textures from
`media/tileDepthTextureAssignments.txt`.

**D77 · Free-hanging cloths: `inset`.** The hospital privacy curtains (`location_community_medical_01_41..47`, `_144..149`,
properties `cutN`/`cutW`, `Material = Fabric`) and the theatre stage curtains (`location_entertainment_theatre_01_92..94`,
`_101..103`, `cutN`/`cutW`, `MaterialType = Fabric`) hang from a ceiling rail across the **middle** of their square, not on
its edge: the S-facing cloth spans frame x 32–96 (the line y = 0.5 runs from screen x 32 to 96) and its hem meets the
floor line of y = 0.5 (frame row 208 at x 32) exactly; their tile geometry boxes sit at y ≈ 0.42 (medical, ±0.11) and
≈ 0.44 (theatre). Placing them on the edge would put the simulated cloth half a tile away from the picture (wrong
bodies, wrong scenery). New wall field `inset` (metres, [0, 1)): the cloth line is the facing's edge moved into the
square (`Projection.clothOrigin`; along the line the screen-x relation is unchanged, so the origin still draws on the
measured pixel — tested for every shipped wall entry). An inset cloth has no wall: `clampToAnchorPlane` is turned off
for its near and far profiles (as for pole flags), `behindWall` is false (bodies on both sides touch it), the thick-wall
standoff is 0, and it is never "blocked" (the 1.5 m rule is for furniture standing against a wall). They use the
`curtain` profile (folds). The rail is drawn in the same sprite above the cloth: the cloth is measured from the first
saturated pixel under the rail, and mesh vertices above the pinned top edge are never displaced, so the rail stays still.
The white curtains `_144..149` have the alpha masks of `_41, 42, 43, 45, 46, 47` (≤ 30 px differ) and share their
depth textures (`tileDepthTextureAssignments`), so they reuse the blue measurements. Rails `_40`, `_44` and theatre rods
`_95`, `_100` carry no cloth and are not entries.

**D78 · Depth bias for wall-attached cloths that are not WallOverlay.** D30/D33 bias wall-overlay flags toward the viewer
in the per-frame pass. The church banners are `attachedN`/`attachedW` wall objects (`MoveType = WallObject`), not
`WallOverlay`, and hang on the wall like one. The bias now applies to every wall cloth with `inset` 0 whose tile is a
`WallOverlay`, `attachedN` or `attachedW`, plus curtains (D69); free-hanging cloths and pole flags keep vanilla depth.
The Knox flags have neither property and their own depth texture (`location_military_knox_01_8`/`_11`); they are also
`Translucent` in `tileGeometry.txt`, so vanilla already draws them in the per-frame pass (as curtains, D76). Precaution,
not observed in-game.

**D79 · What was added, what was not.** Added (measured with the 2× frame, overlays checked by eye):
- `location_community_church_small_01_76..78` (S) and `_84..86` (E): banners (`CustomName = Banner`, `Material = Fabric`),
  single tiles (Lower/Middle/Upper are different designs, not one stacked banner). The art of `_77, 78, 84, 85` runs
  3–9 px below the floor line (geometry: the banner hangs ≈ 0.09 m off the wall); `h` stops at the floor line under the
  pinned corner so the cloth never rests below the floor (rows under it move with the hem).
- `location_military_knox_01_8..10` (S) and `_11..13` (E): one flag over a 3-tile sprite grid (`SpriteGridPos`). Each
  tile is its own cloth pinned along its top; seams can open while one part is pushed (limitation; no cloth spans
  objects yet). Top edges measured by a median fit of slope ±0.5 (their cut corners fool `flagtool.py measure`).
- `location_restaurant_bar_smutz_23`: the white banner (`WallOverlay`, Tiles2x only), measured with `flagtool.py`.
- D77's hospital and theatre curtains.
Not added in 0.4.0 (all added by 0.5.0 with simulations of their own, D80–D82), with reasons:
- Bunting (`walls_decoration_01_64..67, 72..75, 80..83`, `location_restaurant_bar_smutz_32..35`): pennants on a sagging
  string; no straight pinned top edge, and the string line is not a wall line. Needs its own kind.
- Awnings (`walls_decoration_01_68..71, 84..87, 92..95, 100..103, 108..167`, `location_restaurant_pileocrepe_01_4..5`,
  `location_restaurant_pie_01_4..5`, `location_restaurant_bar_smutz_12..15`): sloped or domed canopies stretched on
  frames; a hanging-cloth model would make them sag off their frames. Needs its own kind (e.g. a framed canopy with a
  flutter of the valance only).
- Church altar cloth (`location_community_church_small_01_40..43`): draped over an altar, i.e. resting on furniture.
- Everything else on the eight atlases is not fabric (walls, signs, furniture, posters, string lights, the
  pileocrepe/pie atlases have only the awnings as fabric) — `walls_decoration_01_16..19` were already animated.

### Phase 8 status
0.4.0: 69 unit tests pass (3 new: inset parsing, free-hanging placement, wall-hung placement; the origin round-trip test
now covers all 35 wall entries), `verifyPatches` applies all advices to 42.21.0, deployed to `mods/RealFlags` (jar SHA-256
`fcb3ac5709da7fc04bea8846cd538ccb632ab7bb97ce9acf592addd6fc14ec0f`). **Not yet tested in-game.**

## Phase 9 — strings, canopies and drapes (2026-11-10)

Request: cloth physics for every fabric object D79 left out — bunting, awnings and canopies, the altar cloth — plus the
outdoor string lights, with dedicated simulations where the flag cloth does not fit, without regressions. Evidence:
`newtiledefinitions.tiles.txt` (CustomName/Facing/SpriteGridPos/lights properties), every sprite extracted from
`Tiles2x.pack`, pz-mcp-server (`pz_search_java` for `HasLightOnSprite`) and, because that decompile is an older build
(D71), the installed 42.21.0 jar decompiled with Vineflower (`IsoObject`, `IsoSprite`, `FBORenderCell`).

**D80 · Strings are not cloth sheets: `StringLine`.** Tile properties: `CustomName = Bunting` (`walls_decoration_01_64..67,
72..75, 80..83`; `location_restaurant_bar_smutz_32..35` has the same pictures, `CustomName = CustomName`) and
`CustomName = Lights` (`walls_decoration_01_76..79` Rainbow, `88..91` Small, `96..99` Large; `exterior`,
`HasLightOnSprite`, `lightswitch`). `lighting_outdoor_01_48..51` (also "Lights") are flood lights and
`lighting_indoor_01_56..58` metal lamps: not strings. Each string is two tiles (`SpriteGridPos` (0,0)+(1,0) facing S,
(0,0)+(0,1) facing E). Fitting the top of the opaque pixels of both tiles on the edge line with a parabola: every
string spans the full 2 m edge at ≈ 2.35–2.47 m (the top of a 2.449 m wall) with a sag of 0.20–0.24 m; pennants hang
≈ 0.22 m. A pennant sewn along its top cannot be a hanging rectangle pinned on a straight edge (D79), so: a 12-segment
Verlet chain pinned at both ends (inextensible PBD segments, 16 iterations: 6 let it stretch 25 % at 30 m/s), one
pendant particle per interior node at the hang length — pennants held in the plane perpendicular to the local tangent
(they swing about the string, never along it) with flat-plate drag ½ρ·Cd·A·(v·n)|v·n| on the pennant's normal, bulbs
free pendulums with sphere drag — thin-cylinder drag on the string, 1/120 s step, settle → reference, calm restore,
sleep exactly on the reference. Both tiles of a string list its whole geometry relative to themselves; the instance is
keyed by the absolute end points (cm), so they share one simulation and the seam between them cannot open. A pixel is
placed along the string by its frame x; below the string's rest line it blends toward the pendant there (a rigid swing
up to 1.5 × the hang length).

**D81 · Canopies are not hanging cloths either: `Canopy`.** 84 sprites (`CustomName = Canopy`: `walls_decoration_01_68..71,
84..87, 92..95, 100..103, 108..167`, `location_restaurant_bar_smutz_12..15`, `_pie_01_4..5`, `_pileocrepe_01_4..5`).
Alpha masks group them into 31 shapes in color variants. Fabric stretched over a frame is held on every edge, so the
eye reads two things: the panel breathing and the valance fluttering. Panel: the two lowest membrane modes
sin(πs)sin(πt), sin(2πs)sin(πt) as oscillators with damping and a cubic large-deflection stiffening κa³; driven by
½ρ(Cn(w·n)|w·n| + Cl|w∥|² + Cg|w|²η); κ set so 15 m/s straight at the panel gives 6 cm (cap 10 cm). A linear membrane
cannot be both slow (visible) and stiff (cm-scale deflection) — f₁ = 2.2 Hz needs T ≈ 4 N/m, which would sag ≈ 2 m —
the cubic term is what real fabric does. At the cap the stiffened frequency is < 300 rad/s, so ω·h < 2 at the 1/240 s
step (tested). Valance: K = 8 uncoupled hinged strips, θ'' = −(3g/2L + k)sinθ − 2ζωθ' + (3ρCd/4ρ_sL)·cosθ·v|v| with the
projected-height cosθ; k = 60 s⁻² (coated canvas) keeps a 3 m/s breeze near 24°; tapered to zero at the hem's ends
(sewn to the frame corners). Picture: per 4-px column, panel top → valance top (pivot) → hem, from the alpha mask after
a 5×5 opening (removes the frame's 2–3 px bars and legs). The valance band (14–18 px ≈ 0.2 m) runs along the hem only:
for straight canopies where the smoothed outline follows the hem's iso slope (+0.5 along x, −0.5 along y; a line fit
over ±12 px, because scallops and 4-px steps defeat a local gradient), for round ones the low part of the outline
(within 28 px of its lowest point). Its top is a smooth line above the scallop tips. Back views of plain canopies
(`92/93/108/109/124/125/136/137/140/141`, curved `95/111/127/139/143`) show no valance: panel only. Panel normal and hem
direction come from the family and the tile's `Facing` (tilt 30–35° toward it; round hems turn by 90° or 180°).
Generated by `tools/decotool.py` into `media/realflags/decorations.json`; overlays checked by eye for every shape.

**D82 · Altar cloth = a drape (a wall cloth over furniture).** `location_community_church_small_01_40..43` (Altar,
Decorated, `Surface = 34`) are one sprite each for wood and cloth. The hanging part *is* a cloth on a straight edge, so
it reuses the wall cloth, with a new `drape` flag: the altar's face is the plane the cloth may not pass (clamp kept,
unlike a free-hanging inset cloth), never blocked, standoff = the cloth's half thickness, and the wall mesh inserts
columns at the cloth's left and right edges and moves nothing outside them (the altar beside it stays still).
Faces: 40/41 east (top edge rising to the right), 42/43 south. Inset from an altar top at Surface 34 px (1x) ≈ 0.87 m:
40/43 at 0.52/0.54 m, 41/42 on the tile edge (the computation gives −0.06, clamped to 0).

**D83 · One gust field for all decorations; their bodies and air.** `GustField`: gusts advected downwind,
g(t − (r·ŵ)/U) (frozen turbulence), fixed seed, sampled at each particle/flap's world position with a shared real-time
clock — so two tiles of a string, or a row of market stalls, are hit by one gust front a moment apart (tested: same
point and clock ⇒ identical motion; g(t, x) = g(t + Δx/U, x + Δx·ŵ)). `BodyScan` reuses the flags' constants for
characters, vehicles and air wash around an arbitrary box (the flags' `Interactions` code is untouched): strings get
body colliders (in practice only tall vehicles reach 2.2 m), canopies only the air vehicles and runners move.

**D84 · Lit string lights draw an `_on_` overlay; it is bent with the same motion.** Jar: `IsoWorld` sets
`HasLightOnSprite` when a `<tileset>_on_<index>` sprite exists; `IsoObject.hasAnimatedAttachments` creates that overlay
and `renderAnimatedAttachments` draws it via `IsoSprite.render → renderCurrentAnim → performRenderFrame(…, obj, …)` in
`FBORenderCell.renderAnimatedAttachments`, which sets `renderTranslucentOnly = true`. So our existing performRenderFrame
and Texture.render advice see it, with the light object: `FlagRegistry` maps every `<tileset>_on_<n>` name to the base
sprite's definition (not counted as a definition), the same instance is found and (already stepped this frame) only
drawn again. The light source itself stays put.

**D85 · Architecture.** `FlagDef.Kind` gains LINE and CANOPY ("decorations"); `Hooks.onTextureRender` hands them to
`Decorations` before any flag/curtain code, inside the same failure isolation. They have their own instances
(`LineInstance` keyed by geometry, `CanopyInstance` by object, both evicted when unseen), their own LOD (half mesh beyond
the flags' LOD distance × zoom), sleep → vanilla quad, and `SpriteMesh.drawField` — a displacement-field mesh sharing
`SpriteMesh`'s capture/emit (refactored into `begin`/`finish`, behaviour unchanged for flags). No new advice; all
previous ones still apply (`verifyPatches`).

### Phase 9 status
0.5.0: 92 unit tests pass (23 new), `verifyPatches` applies all advices, deployed to `mods/RealFlags` (jar SHA-256
`67c0c5a45c72888548b2ef210261fa6414e980bc3d9413c47e9b1b3ae87acb32`). **Not yet tested in-game.**

## Acceptance status (MVP)
| Criterion | Status |
|---|---|
| A wall flag and a pole flag visibly wave and react to wind strength and direction (B42 42.21.0) | **Verified in-game** (Phase 3 videos; both directions, 10/40/70 km/h) |
| No wind → identical to vanilla | **Verified** for wall flags (Phase 2 A/B, Phase 3 step 6). The pole intentionally shows the DGVFlags canvas (D11) |
| 50 flags < 1 ms/frame | Far LOD measured ≈0.47 ms; full detail **passed by user report** after D49 (figures not recorded); original defaults measured ≈1.5 ms |
| No errors in console.txt | No `[RealFlags]` ERROR/WARN in any run except the deliberate `RealFlags_testFailure()` |
| Disabling the mod restores vanilla; save/load unaffected | **By design** (no objects, no modData, client-only render changes); `RealFlags_setEnabled(false)` verified in-game; disabling in the mod manager + reloading a save **not explicitly tested** |
| cloth-core has no PZ imports, tests pass | **Verified** (`checkNoPzImports` proven to fail on a planted import; 21 core tests) |
| Dedicated server no-op | **By design** (client jar path skipped by ZombieBuddy on servers); not run on a server |

## Open items (not guessed)
- Final acceptance checks (disable in the mod manager → reload save → vanilla; re-enable → animate; D52 log line):
  **skipped by the user on 2026-11-06**, MVP accepted as done. They remain untested; the design guarantees are in
  the acceptance table above.
- Known limitations (README §8): canvas always in front of its own pole (D37); wall flags stored as attached sprites
  are not caught; depth follows the rest pose (D24); occasional single-frame max ≈2 ms (avg is the metric).
- Wall flags stored as an *attached sprite* of a wall object would not be caught (layer reclassification keys on the
  object's own sprite). None seen so far; the test placements were separate objects.
- The canvas always draws in front of its own pole (D37).
