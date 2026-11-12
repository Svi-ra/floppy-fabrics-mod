# Phase 3 in-game test — pole + wall flag animated end-to-end

Setup as in Phase 2 (ZombieBuddy + Floppy Fabrics, `-agentlib:zbNative -- -debug`). Approve the new jar
(SHA-256 in chat). Use the same test spot: the pole (`street_decoration_01_24` + `_16` above it) and the wall flags
on the **outside** wall. Debug overlay is now **off** by default (`FloppyFabrics_debug(true)` to show it).

All commands need parentheses. `kph` = km/h; the angle uses the game's own convention (same number `FloppyFabrics_wind()` prints).

| # | Do | Expect |
|---|---|---|
| 1 | Look at the flags with calm weather or `FloppyFabrics_forceWind(0, 0)` | Wall flags exactly vanilla; pole shows the US flag (media/floppyfabrics/pole_flag.png) hanging limp from the pole top (no painted furled cloth) and a continuous pole |
| 2 | `FloppyFabrics_forceWind(40, 308)` | Pole flag swings out and flies toward **screen-left/down-left** (same way the rain went at 308°); wall flags billow (outside wall only) |
| 3 | `FloppyFabrics_forceWind(40, 135)` | Pole flag swings round to fly toward **screen-right** |
| 4 | `FloppyFabrics_forceWind(10, 308)`, then `(25, 308)`, then `(70, 308)` | Visibly stronger response each time; at 70 the pole flag streams nearly flat |
| 5 | Pause the game (Space) while it flies | Flags freeze; unpause → continue |
| 6 | `FloppyFabrics_forceWind(0, 0)` and wait ~10 s | Wall flags settle back to **exactly** vanilla; pole flag droops back to limp |
| 7 | A wall flag on an **inside** wall (place one indoors) with wind 40 | Does not move |
| 8 | `FloppyFabrics_forceWindOff()` | Back to the climate wind (`FloppyFabrics_status()` shows `wind=…` without `(forced)`) |
| 9 | Walk around the pole / stand in front of the wall flag | No flicker; flag hidden behind things that are in front of it; picking (right-click) still works |
| 10 | `FloppyFabrics_status()` | `instances` = number of flags seen; `fallbacks{… canvas=0 static=0}`; no `[FloppyFabrics] ERROR` in console.txt |

Send back: a short video (pole + wall flag with steps 2–4), the `FloppyFabrics_status()` line, and anything that looks
wrong (stretching, jitter, the pole joint, the canvas size/position relative to the pole).
