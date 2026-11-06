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
