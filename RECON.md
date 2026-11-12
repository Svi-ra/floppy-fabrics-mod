# RECON — wind-reactive cloth for vanilla flags (Phase 0)

Status: **Phase 0 complete; Q1–Q4 answered (see DECISIONS.md D11–D15). Phase 1 not started.**

Project: Gradle source in `D:\Floppy-Fabrics`; the build copies a clean mod folder to
`C:\Users\user\Zomboid\mods\FloppyFabrics`. Mod id `FloppyFabrics`, package `pzmod.floppyfabrics`, log tag `[FloppyFabrics]`.
Dependencies: `\ZombieBuddy` (DGVFlags was dropped, D91; see §3b for history).
Date: 2026-11-05. All class/method names below were read from the installed game,
decompiled locally with IntelliJ's Fernflower into a scratch directory (reference only, **not
committed**). Line numbers refer to that decompilation of build 42.21.0 and will drift between builds.

The pz-mcp-server tools were not available in this session; every claim was traced by grepping
the install (`media/`) and the decompiled `projectzomboid.jar` directly.

---

## 1. Environment

| Item | Value | Evidence |
|---|---|---|
| Game build | **42.21.0**, revision `4a0e9546ec` | `C:\Users\user\Zomboid\version.txt` |
| PZ install | `D:\Program Files\Steam\steamapps\common\ProjectZomboid` | `java.home` line in `console.txt` |
| Game classes | `projectzomboid.jar` (23,829 classes; classpath `.` + jar) | `ProjectZomboid64.json` |
| Game JRE | Azul Zulu **25.0.1** (`jre64/release`) | |
| User dir | `C:\Users\user\Zomboid` | |
| Mods dir | `C:\Users\user\Zomboid\mods` | |
| JDK for building | IntelliJ JBR **25.0.3** has `javac.exe` (`D:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\jbr`). System `java` on PATH is **24.0.1** (`C:\Program Files\Java\jdk-24`) — too old for `--release 25`. No `gradle` on PATH → use a Gradle wrapper. | `which`, `release` files |
| Tile resolution in use | `texture2x=true` → `Tiles2x.pack` (128×256 frames) | `options.ini`, `console.txt` |
| console.txt baseline | 16,901 lines, **6,406 `ERROR` lines already present** from other mods | grep |

Because the log is already noisy, the acceptance criterion "no errors in console.txt" will be
measured as **zero lines tagged by this mod** (all our logging uses a `[FloppyFabrics]` prefix).

## 2. ZombieBuddy

| Item | Value |
|---|---|
| Version | **2.3.4** (`java/VERSION`, and `ZombieBuddy.getVersion() = 2.3.4` in console.txt) |
| Location | Workshop item `3619862853` → `...\workshop\content\108600\3619862853\mods\ZombieBuddy` |
| Agent install | `ProjectZomboid64.json` vmArgs contain `-agentlib:zbNative`; `ZombieBuddy.jar` + `zbNative.dll` copied to the game dir |
| Approval | Unsigned/new JARs trigger a native **approval dialog** on first load; decisions in `%USERPROFILE%\.zombie_buddy\mod_approvals.json` (`doc/Installation.md`) |
| Docs read | `doc/ModdingGuide.md` (full), `java/src/.../Patch.java`, `PatchEngine.java` (matching logic) |

**mod.info keys** (ModdingGuide.md): `require=\ZombieBuddy`, `javaJarFile=<path relative to version dir>`,
`javaPkgName=<pkg>` (mandatory with jar), optional `ZBVersionMin`/`ZBVersionMax`.
One jar per mod. Optional `Main.main(String[])` in `javaPkgName`; all `@Patch` classes in the
package are discovered automatically.

**Client/server split** (ModdingGuide.md "Client/server JAR paths"): a `javaJarFile` path containing
`media/java/client/` is **skipped on a dedicated server**. → Our jar goes in
`42/media/java/client/FloppyFabrics.jar`, which makes it a guaranteed no-op server-side.

**Patch API** (`me.zed_0xff.zombie_buddy.Patch`):
- `@Patch(className, methodName, isAdvice=true, warmUp=false, strictMatch=false)`
- `@Patch.OnEnter(skipOn=true)` → advice returns `boolean`; `true` skips the original (which then returns its default value).
- `@Patch.OnExit`, param annotations `@Patch.This`, `@Patch.Argument(i)`, `@Patch.AllArguments`,
  `@Patch.Return(readOnly=false)` (to rewrite a return value), `@Patch.Local`.
- **Overload matching** (`PatchEngine.java:332-548`): un-annotated advice params ⇒ *exact* signature
  match by count and assignable types; `@Argument(i)` ⇒ "at least i+1 params"; no-param advice ⇒ matches
  *every* overload unless `strictMatch=true`. ⇒ We will always declare full un-annotated parameter lists
  to pin a single overload.
- Lua exposure: static methods annotated `@LuaMethod(name=..., global=true)` become Lua globals
  (used for the dev wind override — no Lua file needed).

**Other ZB mods enabled in `mods/default.txt` that touch the same area: `WindSway` (0.6.0, `pzmod.windsway`).**
Its patch targets (from class names / constant pool of `windsway.jar`):
`FBORenderCell.renderFloor`, `renderMinusFloor_DoorOrWall`, `renderMinusFloor_NotDoorOrWall`,
`renderTranslucentObjects`, `FBORenderTrees.addTree/render`, `ObjectRenderEffects.update/updateStatic`,
`ClimateManager.getWindTickFinal`, `Core.getOptionDoWindSpriteEffects`, `SpriteRenderer.drawGeneric`,
`IsoCell.getStencilTexture`, `IsoTree.renderChopTreeIndicators`, `ParameterWindIntensity.calculateCurrentValue`,
`LuaManager.init`. **None of our planned hook points overlap** (see §4), and we avoid `getWindTickFinal`
because WindSway rewrites it. Compatibility will be re-checked in Phase 2 with both mods on.

## 3. How flags are represented

Flags are **plain `zombie.iso.IsoObject`s** with a sprite — there is no flag class. Identification is by
sprite name. No flag tile has `MoveWithWind`, so vanilla never animates them
(`IsoWorld.java:826` sets `IsoSprite.moveWithWind` only from that property; trees set it in code at `:1617`).

Source: `media/newtiledefinitions.tiles.txt` (text dump of `newtiledefinitions.tiles`). All tiles whose
properties contain "Flag" (complete list), plus banner-like tiles for future work:

| Sprite | Kind | Facing / grid | Key properties |
|---|---|---|---|
| `street_decoration_01_24` | **Flagpole, lower level** (pole only) | grid 0,0 (z0) | CustomName=Pole, GroupName=American Flag, PickUpTool=Shovel, StopCar |
| `street_decoration_01_16` | **Flagpole, upper level** (pole top + **limp/furled** flag) | `SpriteGridPos=0,0,1` → separate object on the **square at z+1** | same group |
| `walls_decoration_01_16` | Wall flag, US, draped from top | S, attachedN | Material=Fabric, MoveType=WallObject, IsHigh, WallOverlay |
| `walls_decoration_01_17` | Wall flag, US, draped | E, attachedW | " |
| `walls_decoration_01_18` | Wall flag, blue state flag, draped | S, attachedN | GroupName=Administrative |
| `walls_decoration_01_19` | Wall flag, blue state flag, draped | E, attachedW | " |
| `location_restaurant_bar_smutz_23` | Wall flag, white banner | S, attachedN | **present in Tiles2x only** (missing from Tiles1x) |
| `location_military_knox_01_8/9/10` | Flat US flag, **3-tile sprite grid** | S, grid (0..2,0) | MoveType=WallObject |
| `location_military_knox_01_11/12/13` | Flat US flag, 3-tile grid | E, grid (0,0..2) | " |
| `walls_decoration_01_64..67`, `_72..83` | Bunting (future) | | |
| `location_community_church_small_01_76..86`, `walls_decoration_03_45..47`, `_104..107` | Banners (future) | | |

Vanilla ships **exactly one flagpole** (the 2-level American Flag pole).

### Atlas coordinates (Tiles2x, frame 128×256)

`atlasXYWH` = trimmed rect inside the page; `offset` = trim offset inside the 128×256 frame.

| Sprite | Page | atlas x,y,w,h | offset |
|---|---|---|---|
| street_decoration_01_16 | Tiles2x216 | 819,883,18,93 | 59,151 |
| street_decoration_01_24 | Tiles2x216 | 1001,128,18,200 | 55,29 |
| walls_decoration_01_16 | Tiles2x249 | 576,706,53,113 | 66,35 |
| walls_decoration_01_17 | Tiles2x249 | 441,844,53,113 | 8,34 |
| walls_decoration_01_18 | Tiles2x249 | 575,456,57,126 | 64,30 |
| walls_decoration_01_19 | Tiles2x249 | 519,603,55,127 | 4,28 |
| location_restaurant_bar_smutz_23 | Tiles2x125 | 779,834,57,124 | 67,31 |
| location_military_knox_01_8 | Tiles2x122 | 712,838,53,124 | 71,22 |
| location_military_knox_01_9 | Tiles2x122 | 936,433,64,144 | 60,1 |
| location_military_knox_01_10 | Tiles2x122 | 460,812,45,104 | 60,24 |
| location_military_knox_01_11 | Tiles2x122 | 979,205,43,105 | 23,24 |
| location_military_knox_01_12 | Tiles2x122 | 646,709,64,147 | 2,0 |
| location_military_knox_01_13 | Tiles2x122 | 767,840,53,122 | 2,24 |

### Atlas coordinates (Tiles1x, frame 64×128)

| Sprite | Page | atlas x,y,w,h | offset |
|---|---|---|---|
| street_decoration_01_16 | Tiles1x30 | 889,894,9,46 | 30,76 |
| street_decoration_01_24 | Tiles1x30 | 944,827,9,100 | 28,15 |
| walls_decoration_01_16 | Tiles1x32 | 703,801,27,56 | 33,18 |
| walls_decoration_01_17 | Tiles1x32 | 801,705,27,57 | 4,17 |
| walls_decoration_01_18 | Tiles1x32 | 635,804,29,62 | 32,16 |
| walls_decoration_01_19 | Tiles1x32 | 869,574,28,64 | 2,14 |
| location_military_knox_01_8 | Tiles1x17 | 783,875,26,61 | 36,12 |
| location_military_knox_01_9 | Tiles1x17 | 560,954,32,60 | 30,13 |
| location_military_knox_01_10 | Tiles1x17 | 989,765,23,52 | 30,12 |
| location_military_knox_01_11 | Tiles1x17 | 701,511,21,53 | 12,12 |
| location_military_knox_01_12 | Tiles1x17 | 802,728,32,74 | 1,0 |
| location_military_knox_01_13 | Tiles1x17 | 977,651,27,61 | 1,12 |

Pack format verified by parsing: `"PZPK"`, int version(=1), int pageCount; per page: string name,
int entryCount, int hasAlpha, entries `{string name, int x,y,w,h, offX,offY, frameW,frameH}`, int pngLength, PNG.

**Consequence for `flags.json`:** cloth rects will be stored **normalised to the frame** (0..1 of
`widthOrig × heightOrig`), so one entry works for both 1x and 2x.

### Art observations that affect the design
- **Pole flag** (`street_decoration_01_16`): the cloth is painted **furled/limp** against the pole,
  ≈8 px wide at 2x, stripes vertical. Stretching this texture into an extended, flying flag will look
  smeared. → **Open question Q1.**
- **Wall flags** (`walls_decoration_01_16..19`, `bar_smutz_23`): single tile, draped from the top with baked
  folds. Natural pin edge: **top**. Good MVP candidates.
- **Knox flags**: one flag split across **3 separate objects** (sprite grid). Simulating each slice independently
  would tear at the seams; doing it properly needs a cross-object instance. → proposed for **post-MVP**.
- Many wall flags are on **interior** walls. → Wind must be zero indoors (see §6).

## 3b. DGVFlags — cloth textures for the pole flag (your Q1 answer) — HISTORICAL: dependency removed in D91; texture now ships as media/floppyfabrics/pole_flag.png

| Item | Value | Evidence |
|---|---|---|
| Mod | "Any Wall Flags 3D [B42] byDGV", `id=DGVFlags`, `versionMin=42.13` | `42/mod.info` |
| Location | Workshop `3818605688` → `...\108600\3818605688\mods\DGVFlags` | |
| Enabled now? | **No** — not in `mods/default.txt`. Must be enabled; our `require=` enforces it. | |
| Its own content | Tile pack `dgv_flags.pack`, `tiledef=dgv_flags 7318`, Lua placement UI, items, OBJ models for Project Viewpoint | file list |
| Flat cloth textures | 73 loose PNGs `42/media/dgv_flags/<Name>_0_0_256x<H>.png` (e.g. `UnitedStates_0_0_256x143.png`, `Kentucky_0_0_256x143.png`), referenced as `map_Kd` by the models' `.mtl` files | `Flag_UnitedStates_E_cut.mtl` |
| Texture layout | Full rectangular flag, **plus an 8 px brown rod strip at the bottom** (US: rows 135–142 of 143). Cloth UV rect for US = `v ∈ [0, 135/143]`. Rod height must be measured per texture. | pixel probe |

**Loading at runtime** (verified): `Texture.getSharedTexture("media/dgv_flags/UnitedStates_0_0_256x143.png")`
(`Texture.java:424-430`) → `getSharedTextureInternal` misses the pack tables, then
`ZomboidFileSystem.instance.getString(name)` (`ZomboidFileSystem.java:337`) maps the relative path through
`activeFileMap` to the **active** mod's file and loads it through `TextureAssetManager`. The load is asynchronous, so
check `Texture.isReady()` (the render state already skips unready textures, `GenericSpriteRenderState.java:405`).
If DGVFlags is not active the path is cached in `nullTextures` and returns `null`. The pole then falls back to vanilla.

**Dependency syntax** (verified): `require=\ZombieBuddy,\DGVFlags`. `ChooseGameInfo.java:245-246` matches any line
*containing* `require=`, strips `\` and splits on `,`. So **no other mod.info line may contain the text
`require=`** (e.g. in the description).

### Pole flag = vanilla pole + DGVFlags canvas
`street_decoration_01_16` (2x frame) column coverage, measured from the extracted sprite:
- Pole shaft: x 59–64 (core 61–64), rows 152–225; finial x 59–68, rows 151–157.
- Painted furled cloth: x 64–76, rows ~157–243. It **hides the shaft below row ~225**; a pole pixel reappears at
  x 65, rows ~240–243.

Plan (data in `flags.json`, per sprite):
- `staticQuads`: list of `{src rect → dst rect}` in normalised frame coords, drawn from the vanilla texture. One quad
  for finial + shaft (x 59–64/68, rows 151–225), and one that re-samples a clean shaft strip to fill rows 225–243
  where the vanilla art had cloth. The painted furled cloth is **not drawn**.
- `canvas`: `{texture: "media/dgv_flags/UnitedStates_0_0_256x143.png", uv: [0,0,1,0.944], pin: "left",
  restSize: [w,h] in world units, anchor: point on the shaft below the finial}` → simulated cloth.
- Consequence: at zero wind the pole flag hangs limp along the pole with the DGVFlags texture; it is **not
  pixel-identical** to vanilla (accepted by your Q1 answer). Wall flags remain pixel-identical at zero wind.
- If DGVFlags is missing or its texture is not ready: draw the vanilla sprite unchanged.

## 4. Render call path (FBO render-chunk path, B42 default)

```
FBORenderCell.renderOneLevel(IsoChunk,int,...)                      FBORenderCell.java:1233
  └─ if chunk level dirty: calculateObjectRenderInfo(square)         :1297  → calculateObjectRenderLayer(IsoObject) :1697
       ObjectRenderLayer.MinusFloor  → baked into the cached chunk FBO texture (NOT redrawn per frame)
       ObjectRenderLayer.Translucent → redrawn every frame via renderTranslucent(square) :2938
renderMinusFloor(IsoObject) :2509  /  renderTranslucent(IsoObject) :2959
  └─ renderMinusFloor_NotDoorOrWall(IsoObject) :2621    (flags are not cutN/cutW → this branch)
       └─ IsoObject.render(x,y,z,ColorInfo,bDoAttached,bWallLightingPass,Shader)   IsoObject.java:3410
            └─ IsoSprite.render(IsoObject,x,y,z,IsoDirections,offX,offY,ColorInfo,boolean)  IsoSprite.java:692
                 └─ renderCurrentAnim → renderCurrentAnim_FBORender :754
                      sets depth state; setupTileDepth(...) configures TileDepthModifier.instance
                      └─ performRenderFrame(IsoSpriteInstance,IsoObject,IsoDirections,int frame,
                                            float tx,float ty,float tdepth,Consumer<TextureDraw>) :1368  (private)
                           TextureDraw.nextZ = tdepth
                           tex.render(tx,ty,w,h,r,g,b,a,texdModifier)    Texture.java:915
                              └─ SpriteRenderer.instance.render(tex,x,y,w,h,r,g,b,a,mod)  SpriteRenderer.java:220
                                   └─ GenericSpriteRenderState.render(...) :404 → TextureDraw.Create(..., mod) TextureDraw.java:882
                           then writes ObjectRenderInfo.renderX/Y/Width/Height (used by FBORenderObjectPicker)
```

### Critical finding: the static-chunk cache
B42 bakes `MinusFloor` objects into a per-chunk-level FBO texture that is only redrawn when invalidated.
A flag drawn in that pass **cannot animate**. Vanilla handles animated objects (trees, `MoveWithWind`
vegetation, `isAnimating()`, objects with `ObjectRenderEffects`) by classifying them into the
**`Translucent`** layer instead (`isObjectRenderLayer_MinusFloor` returns false / `isObjectRenderLayer_Translucent`
returns true for them, `FBORenderCell.java:1756-1960`), which is drawn every frame with depth test on and depth
write off, against the cached chunk depth.

### Chosen hooks (proposal)
1. **`zombie.iso.fboRenderChunk.FBORenderCell.calculateObjectRenderLayer(IsoObject)`** (private) — `@OnExit`,
   `@Return(readOnly=false)`: if the result is `MinusFloor` and the object's sprite is a registered flag and the mod
   is enabled → return `Translucent`. This is the same layer vanilla uses for wind-swayed objects. Pure render
   classification; no object/game state touched.
2. **`zombie.iso.sprite.IsoSprite.performRenderFrame(...)`** (private) — `@OnEnter`/`@OnExit` only to set/clear a
   static "current flag context" (object, tdepth, `texdModifier`, `TextureDraw.nextChunkDepth`). It is **not skipped**,
   so vanilla's `ObjectRenderInfo` bookkeeping (used by the object picker for hover/click) still runs.
3. **`zombie.core.textures.Texture.render(float,float,float,float,float,float,float,float,Consumer)`** —
   `@OnEnter(skipOn=true)`: when the context is set *and* `this` is the flag's frame texture, emit the cloth mesh
   instead and return `true` (skip the single quad). Otherwise one static-field check and return `false`.
   This **replaces** the draw in the same call site, same pass, same colour/alpha/lighting, same depth state.

Rejected alternatives: skipping `performRenderFrame` (breaks object picking: `FBORenderObjectPicker.java:180-192`
reads `renderInfo.renderX/renderWidth` written there); setting `windRenderEffects`/`setAnimating(true)` on the
object (mutates object fields — violates "observer, not replacer"); patching `renderMinusFloor_NotDoorOrWall`
(WindSway already advises it).

Fallback/disable: on any exception → log once, set `disabled`, call `IsoObject.invalidateRenderChunkLevel(long)`
(`IsoObject.java:5832`) on known flag objects so the chunk re-bakes them in the static layer → exact vanilla.

## 5. Drawing custom geometry

**Four-corner textured quads are supported** without raw GL:
- `SpriteRenderer.instance.render(Texture, double x1,y1, x2,y2, x3,y3, x4,y4, float r,g,b,a, Consumer<TextureDraw>)`
  (`SpriteRenderer.java:184`) → `TextureDraw.Create(..., c0..c3, u0..v3, modifier)` (`TextureDraw.java:882`).
- `Create` sets corners and default UVs (the whole texture), **then calls the modifier**, which may overwrite
  `u0..u3/v0..v3` and `tex1*` (all public fields, `TextureDraw.java:58-80`), then sets `z = nextZ`,
  `chunkDepth = nextChunkDepth` and **resets both statics to 0**.
- Corner order: 0=TL, 1=TR, 2=BR, 3=BL (matches `Texture.render(ObjectRenderEffects,...)`, `Texture.java:921`).
- Vanilla already deforms sprite quads this way for wind (`Texture.render(ObjectRenderEffects,...)`).

**Plan:** draw the cloth as a grid of quads, each via the call above with one preallocated `Consumer<TextureDraw>`
("ClothQuadModifier") that (a) sets the cell's main-texture UVs and (b) sets **per-corner depth-texture UVs**.
Before every quad: restore `TextureDraw.nextZ = tdepth` and `nextChunkDepth` (they're consumed per quad).

Why a custom depth modifier: `TileDepthModifier.accept` (`tileDepth/TileDepthModifier.java`) derives `tex1` UVs
from the **whole sprite rect**, not from the quad's vertices, so reusing it for sub-quads would smear depth.
Ours computes each corner's depth UV from that vertex's **rest-pose** position in the sprite frame, using the same
formula (`xStart + (soffX + px·scaleX − tex1.offsetX)/widthHW`). Depth therefore follows the rest pose (the flag
sorts as if flat); displacement only affects colour pixels. Good enough for the MVP; noted as a limitation.

Edge cases to fall back to vanilla on: `tex.flip == true`, non-null `ObjectRenderEffects` on the object,
weather-mask / highlight / outline passes (`IsoSprite.java:734` routes those away from the FBO path).

## 6. Wind API (`zombie.iso.weather.ClimateManager`)

| Method | Meaning | Line |
|---|---|---|
| `static ClimateManager getInstance()` | singleton | :204 |
| `float getWindIntensity()` | 0..1 (`windIntensity.finalValue`) | :491 |
| `float getWindspeedKph()` | `windPower·120` | :507 |
| `float getWindAngleDegrees()` / `getWindAngleRadians()` | compass bearing from `windAngleIntensity` | :527 / :548 |
| `float getWindAngleIntensity()` | −1..1, **screen-space sign** used by rain/trees | :495 |
| `static double getWindTickFinal()` | noisy wind used by vegetation — **patched by WindSway**, so we don't use it | :1001 |

Direction convention is **not consistent in vanilla**: `WorldFlares` moves by `(sin θ, cos θ)` in world x/y
(`WorldFlares.java:233`), fog rotates by −135° into screen space (`ImprovedFog.java:307`), and rain/tree sway use only
the *sign* of `windAngleIntensity` (screen left/right, `IsoWeatherFX.java:228-237`, `ObjectRenderEffects.java:480`).
**Assumption to verify in Phase 2:** world wind vector = `(sin θ, cos θ)` à la `WorldFlares`; the debug overlay will
draw it and we'll check that its screen-horizontal sign agrees with rain drift and tree sway. Not guessed into code
until verified.

Indoor/outdoor: `IsoGridSquare.isOutside()` (`IsoGridSquare.java:9058`); vanilla's own wind check uses
`isExteriorCache` (`IsoObject.checkMoveWithWind`, `:2048`). Wall flags whose square is not outside get zero wind.

Time: `GameTime.getInstance().getRealworldSecondsSinceLastUpdate()` (`= 1/60 · fpsMultiplier`, `GameTime.java:206`),
gated by `static GameTime.isGamePaused()` (`:195`). We deliberately do **not** use `getMultiplier()` (game speed,
fast-forward and the ×200 sleep multiplier would explode the solver).

## 7. Threading model

- `RenderThread` (`core/opengl/RenderThread.java:79`, thread "Render Thread") consumes draw lists.
- The **main/game thread** populates them: `SpriteRenderer` → `states.getPopulatingActiveState()`
  (`SpriteRenderer.java:184+`, `sprite/SpriteRendererStates.java:13`). `IsoObject.render` and therefore all our hooks
  run on the main thread during population.
- `TextureDraw.Create` copies values into a pooled `TextureDraw`; our modifier runs synchronously inside it, so a single
  preallocated modifier and preallocated float arrays are safe. Simulation and mesh building run on the main thread;
  nothing is shared with the render thread.
- Dedicated server: the jar under `media/java/client/` is never loaded (§2). `IsoObject.checkMoveWithWind` and
  `ObjectRenderEffects.updateStatic` also early-out on `GameServer.server`, confirming wind visuals are client-only.
  **Nothing is needed server-side; no network sync.**

## 8. Other verified facts

- Object position/sprite: `IsoObject.getSprite()`, `IsoSprite.getName()` (`IsoSprite.java:1776`), `IsoSprite.name`,
  `IsoSprite.soffX/soffY`, `IsoObject.getSquare()`, `getRenderSquare()` (`:6013`), `getSpriteGrid()` (`:1967`).
- Texture metrics: `Texture.getXStart/XEnd/YStart/YEnd/OffsetX/OffsetY/Width/Height/WidthOrig/HeightOrig/getName`
  (`Texture.java:752-879`).
- `Texture.render(x,y,w,h,...)` adds `offsetX/offsetY` itself (`:915`) — the rest quad is
  `(tx+offX, ty+offY, w, h)`.
- Scale: `performRenderFrame` sets `inst.scale = 2` for 64×128 textures at `Core.tileScale == 2` (`:1371`) — our mesh
  must use the passed `width/height`, not texture pixels.

## 8b. ZombieBuddy behaviour verified while building Phase 2

- **Patch discovery is exact-package only** (`PatchEngine.java:706`, ClassGraph scan of the jar). The ModdingGuide's
  `.patches` subpackage example would be silently ignored, and nothing is logged beyond `no patches in package X`.
  Global `@LuaMethod` classes follow the same rule (`:720`). → DECISIONS D23.
- Mod jars are appended to the **system class loader** (`Loader.java:512`, `appendToSystemClassLoaderSearch`), so
  advice inlined into `zombie.*` classes can call our classes.
- `@Patch.*` annotations are renamed to ByteBuddy's `Advice.*` with their values kept (`PatchTransformer.java:34-47`).
  ByteBuddy binds an **unannotated** advice parameter to the target argument at the *same index*. → we annotate
  every parameter explicitly.
- `patches_jar=<path>:<pkg>` agent argument splits on the first `:` (`Agent.java:104`) → Windows drive letters break
  it; use relative paths (our `verifyPatches` task does).
- Advice with `@Argument(i)` matches overloads with ≥ i+1 parameters whose typed arguments are assignable
  (`PatchEngine.java:506-545`); verified it pins `Texture.render(float×8, Consumer)` among four overloads.

## 9. Questions

Q1–Q4 were answered on 2026-11-05 and recorded as D11–D15 in `DECISIONS.md`.

Still to verify in-game (Phase 2), not guessed:
- World-axis convention of `getWindAngleDegrees()` (§6).
- Exact z-level pixel offset between `street_decoration_01_16` (z+1) and `_24` (z0), so the shaft gap fill lines up.
- Whether WindSway's advice on `renderMinusFloor_NotDoorOrWall` interferes with flags in the Translucent layer.
- That the advice on `Texture.render` and the private `IsoSprite.performRenderFrame` /
  `FBORenderCell.calculateObjectRenderLayer` applies (and whether `warmUp=true` is needed).
