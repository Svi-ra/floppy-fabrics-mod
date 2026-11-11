# Phase 2 in-game test — hello-jar

Goal: prove in the real game that (1) the jar loads and the patches apply, (2) flags move to the per-frame layer,
(3) our sub-quad renderer reproduces the vanilla flag exactly (depth, lighting, position), (4) the debug quad
appears over flag sprites, (5) the fallback/disable paths restore vanilla. No wind/cloth yet: that's Phase 3.

Build deployed to `C:\Users\user\Zomboid\mods\RealFlags` (jar SHA-256 in CHANGELOG / chat).

## 0. Setup (once)
1. Mod manager: enable **ZombieBuddy** and **Real Flags**.
   Real Flags declares `require=\ZombieBuddy`.
2. Launch with **`-debug`** (Steam → Properties → Launch options) so the Lua console and Brush Tool are available.
3. ZombieBuddy shows an approval dialog for `RealFlags.jar` (new, unsigned). Check the SHA-256 matches the one I
   gave you, then approve (session-only is fine while iterating: every rebuild changes the hash).

## 1. Load (main menu is enough)
In `C:\Users\user\Zomboid\console.txt` expect:
- `[ZB] Found patch class: pzmod.realflags.Patch_CalculateObjectRenderLayer` (and `Patch_PerformRenderFrame`, `Patch_TextureRender`)
- `[RealFlags] v0.1.0 loaded (ZombieBuddy 2.3.4, game 42.21.0 ...)`
- no `[RealFlags] ERROR` / `WARN`.

## 2. Put flags in front of you (new sandbox game or a test save)
Right-click ground → **Debug → Brush Tool → Brush Tool Manager**, pick and place:
- `walls_decoration_01_16` (S-facing) and `_17` (E-facing) on an **outside** wall, `_18` / `_19` anywhere;
- the pole: `street_decoration_01_24` on the ground and `street_decoration_01_16` on the square directly above it (z+1).
(If the Brush Tool places a different tile, use "Copy tile" on an existing flag instead.)

## 3. What to look at (screenshots welcome)
| # | Check | Expected |
|---|---|---|
| 3.1 | Each placed flag | Looks exactly like vanilla **plus a pulsing magenta tint** over its sprite rectangle |
| 3.2 | Walk behind/in front of the flag, near walls | Depth order identical to vanilla: you're hidden behind it / it's hidden behind walls as before. No tint "floating" in front of walls |
| 3.3 | Day/night or a light source | Flag lit exactly like before (tint aside) |
| 3.4 | Hover/right-click the flag | Still selectable (object picker works); context menu as vanilla |
| 3.5 | Lua console: `RealFlags_status()` | `drawsReplaced` and `subQuads` grow every frame; `layerOverrides` > 0; fallbacks ~0 |
| 3.6 | `RealFlags_debug(false)` | Tint gone, flag **pixel-identical** to vanilla (compare with a screenshot with the mod disabled if you can) |
| 3.7 | `RealFlags_setEnabled(false)` | Flags drawn by vanilla (status: counters stop growing) |
| 3.8 | Pole flag | Tint appears on the upper tile; the two pole halves still line up |
| 3.9 | Trees/grass (WindSway is enabled) | Still sway as before — no interaction |
| 3.10 | Wall flags after the D30 fix (`RealFlags_debug(false)`) | **No stipple**: solid flag on the wall, as vanilla |
| 3.11 | `RealFlags_depthBias(false)` then `(true)` | `false` brings the stipple back, `true` removes it (proves the cause) |
| 3.12 | `RealFlags_mesh(false)` then `(true)` | No visible difference (our sub-quads = vanilla's single quad) |

| 3.13 | `RealFlags_depthBiasAmount(0.00005)`, `(0.0001)`, `(0.0002)`, `(0.0004)` with `RealFlags_debug(false)` | Find the smallest value where the thin stippled bands at the flag edges are gone |
| 3.14 | At that value, stand right in front of the flag (and/or put a crate/fridge against that wall) | The character/object still covers the flag (bias not too large) |

Lua needs the parentheses: `RealFlags_status()`, not `RealFlags_status`.

## 4. Wind direction convention (open item, RECON §6)
On a rainy/windy day: run `RealFlags_wind()` and note **which way the rain slants on screen** (left/right, up/down
the screen) and the compass string it prints. Two or three samples on different days are ideal.

## 5. Send back
- `console.txt` (or just the lines containing `RealFlags` / `[ZB]` / `ERROR`),
- the `RealFlags_status()` line,
- screenshots of 3.1 / 3.6 / 3.8 if possible, and the §4 notes.
