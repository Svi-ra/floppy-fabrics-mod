# Phase 7 in-game test — window curtains

Build: Floppy Fabrics 0.3.0 on Build 42.21.0. ZombieBuddy will ask to approve the new jar; check its SHA-256 against
`DECISIONS.md` "Phase 7 status". Launch with `-agentlib:zbNative -- -debug` so the Lua console works.

Setup: a house with curtains on an **outside** window. `RealFlags_overlay(true)`. `RealFlags_status()` prints
`curtains{defined layer closedDraws rebakes windowEvents}` — note the numbers before and after each step.
Use `RealFlags_forceWind(40, <deg>)`; `RealFlags_wind()` prints the current angle. Try two angles about 180° apart:
one blows into the room through the window, the other out of it.

| # | Do | Expect |
|---|---|---|
| 1 | Window **closed**, forced wind 70 km/h. Look at several curtains (closed and open curtains, all four wall sides of a room if you can). | Nothing moves; every curtain looks exactly vanilla. `layer` does not grow. |
| 2 | Open the window (right-click → Open window). | Within a frame or two the curtain starts moving. `windowEvents` +1, `layer` grows. Wind blowing in: it billows into the room. Wind blowing out: it stays flat against the window but still breathes a little. |
| 3 | Close the window again while the curtain is billowing. | It swings back and settles over a few seconds (no jump), then looks exactly vanilla. `rebakes` +1 once it is at rest. |
| 4 | Smash a window (with a curtain) instead of opening it. | Moves more than an opened window at the same wind. |
| 5 | Open the window, then walk into the curtain / walk along it. | It is pushed by your body and drags along, then settles. Close the window: walking into it no longer moves it. |
| 6 | Window open, `RealFlags_forceWindOff()` on a calm day (wind ≈ 0). | Curtain is still and exactly vanilla (`calm->vanilla` counts it). |
| 7 | A curtain on an **interior** window (between two rooms), window open. | No wind effect; only bodies move it. |
| 8 | Curtain with something under the window (bed, table, counter). Open the window, wind blowing in. | It billows and rests on the furniture rather than passing through it. |
| 9 | Look closely at the curtain's edges while it moves, including curtains on the near (south/east) side of a room seen through the cutaway. | No stippling, flicker or disappearing parts. If stippled: try `RealFlags_depthBiasAmount(3e-4)`; if it draws over things in front of it: `1e-4`. Report which. |
| 10 | Rolled-up sheet curtain or a rolled blind, window open, strong wind. | Stays vanilla (too short to simulate). |
| 11 | `RealFlags_curtains(false)` with a moving curtain; then `true`. | Off: it is vanilla at once. On: it moves again once its chunk re-renders (open/close the window if it does not). |
| 12 | Multiplayer (if available): another player opens/smashes the window. | Your client starts moving the curtain (`windowEvents` grows via the sync patch). |
| 14 | Hang a **sheet** on a window (right-click → Add sheet), close the sheet so it hangs down, then open the window with wind blowing in. | The sheet switches from the 3D model to the 2D sheet picture and moves like a curtain (`sheetsAs2D` grows). Close the window: it settles, then is the 3D model again. Note how noticeable the switch is. console.txt shows `[RealFlags] first sheet drawn as its 2D sprite` once. A freshly hung sheet is rolled up (open) and too short to move: close it first. |
| 15 | Daytime, closed curtain, window open, `RealFlags_forceWind(70, <deg blowing in>)`. Stand in the room away from the window. | The room near the window brightens and dims gently as the curtain billows (`lights{active=1}`, `updates` grows by ≤ 4/s). No strobing. At night, or with `RealFlags_forceWindOff()`, no change and the light goes away (`active=0`). Too weak/strong: `RealFlags_curtainLightGain(0.2)` / `(0.06)` — report the value you like. |
| 16 | Same curtain, but **open** the curtain (pull it aside). | No extra light (an open curtain already lets daylight in); it still moves. |
| 17 | Barricade the open window with a **metal sheet**. | The curtain stops getting wind and settles, as behind a closed window. Remove the sheet: it moves again. With 1–4 **planks** it still moves, less with more planks; with **metal bars** only, as much as without. |
| 18 | `RealFlags_status()` and console.txt. | No `[RealFlags] ERROR`. Report the overlay's CPU avg/max with a few moving curtains in view. |
