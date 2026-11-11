# Phase 4 in-game test — instance manager, overlay, performance

Same setup and test spot as Phase 3 (the pole + the 4 wall flags; more is fine). Approve the new jar (SHA-256 in chat).
All commands need parentheses.

## A. Overlay
`RealFlags_overlay(true)` → yellow text top-left:
```
RealFlags 0.1.0  on
flags drawn N (ghosts G)  awake A  stepped S  far-LOD F  frozen Z  calm->vanilla V  instances I  cap 64
CPU  last x ms   avg(60) y ms   max(60) z ms
wind … m/s (… km/h) @ … deg (forced)
```
`CPU` = our own work per frame (simulation + building the flag geometry), measured inside the hook.

## B. Performance — the acceptance measurement (50 flags < 1 ms)
1. `RealFlags_forceWind(40, 135)` (every outside flag awake).
2. `RealFlags_stress(10)` → every real flag is drawn 10× (9 ghost copies around it, each with its own cloth).
   With 5 real flags on screen the overlay should say **flags drawn 50 (ghosts 45)**. Adjust n so drawn ≈ 50.
3. **Near LOD**: `RealFlags_lodDistance(100)` (forces full detail). Wait 10 s, screenshot the overlay → note avg/max.
4. **Far LOD**: `RealFlags_lodDistance(0)` (forces coarse detail). Wait 10 s, screenshot → avg/max.
5. **Default**: `RealFlags_lodDistance(12)`, normal zoom, then zoomed out fully. Screenshot each.
6. Optional: same at `RealFlags_forceWind(0, 0)` after ~10 s (calm: wall flags hand back to vanilla, `calm->vanilla` > 0).
7. `RealFlags_stress(1)` to end.

## C. Behaviour
| # | Do | Expect |
|---|---|---|
| C1 | Zoom out / walk 15+ tiles away from a flying flag | `far-LOD` count rises; no visible pop when it switches (motion continues) |
| C2 | `RealFlags_setCap(10)` with stress on | `frozen` = drawn − 10; the nearest keep moving, far ones hold their pose; `RealFlags_setCap(64)` restores |
| C3 | Calm (`RealFlags_forceWind(0, 0)`), wait 10 s | wall flags: `calm->vanilla` = number of wall flags, they look exactly vanilla |
| C4 | Walk far away (> 1 screen) for 15 s, come back | `RealFlags_status()` shows `evicted` > 0; flags reappear correctly (new instances from the shared pose, no hitch) |
| C5 | First sight of the pole after a restart | no noticeable hitch on the second and later pole flags (`prototypes` stays at 1–2 per flag type) |
| C6 | **Last:** `RealFlags_testFailure()` | console.txt: exactly one `[RealFlags] ERROR in Texture.render … RealFlags disabled for this session` block + stack; flags turn vanilla (pole shows its vanilla furled flag); `RealFlags_status()` → `failed=true`; no further errors. Restart the game afterwards. |

## D. Re-measure after D49 (cheaper near LOD)
Same as B with the new build: `RealFlags_overlay(true)`, `RealFlags_forceWind(40, 135)`, `RealFlags_stress(n)` so that
**flags drawn = 50** (e.g. 5 real flags × 10), `RealFlags_lodDistance(100)` (all full detail), wait 10 s, screenshot.
Target: `avg(60)` < 1.0 ms. Also check the pole shows the pole flag texture from the very first frame after a restart
(`RealFlags_status()` → `fallbacks{… canvas=0 …}`).

## Send back
Screenshots of the overlay for B3, B4, B5 (and B6 if done), the `RealFlags_status()` line after C4, and the console.txt
lines from C6.
