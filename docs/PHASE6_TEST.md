# Phase 6 in-game test — flags meet the world

Build: Floppy Fabrics 0.2.1 on Build 42.21.0. ZombieBuddy will ask to approve the new jar; check its SHA-256 against
`DECISIONS.md` "Phase 6b status". Launch with `-agentlib:zbNative -- -debug` so the Lua console works.

Setup for every step: `RealFlags_overlay(true)`. The last overlay line shows `interactions`, `blocked`, `in contact`
and `bodies near flags`. Report the overlay's CPU avg/max for steps 2, 4 and 7.

| # | Do | Expect |
|---|---|---|
| 1 | Stand by a wall flag indoors, no wind. Walk slowly into it. | The cloth bends away from your body, follows you a little as you walk along it, and settles back flat a few seconds after you step away (then `calm->vanilla` counts it again). `in contact` ≥ 1 while touching. |
| 2 | Same flag: sprint past it, close, without touching. | The flag stirs in the air you push (indoors, so this is the wash, not the climate wind). |
| 3 | Wall flag on a square with a **fridge** (or wardrobe / tall bookshelf) placed right in front of it. `RealFlags_forceWind(70, <deg that lifts it>)`. | The flag does **not** move; overlay `blocked` = 1. Pick the fridge up: within ~1 s the flag starts billowing. Put a **counter or table** there instead: the flag billows and lands on top of it rather than through it. |
| 4 | `RealFlags_blockHeight(1.6)` with the fridge (1.55 m) back in place. | The flag is no longer blocked (fridge is shorter than 1.6 m). `RealFlags_blockHeight(1.5)` blocks it again within ~1 s. |
| 5 | American Flag pole next to a building wall; `RealFlags_forceWind(70, …)` toward the wall. | The flag folds against the wall instead of passing through it. |
| 6 | Drive a car past the pole flag at speed, ~1–2 m away; then park the car right under it. | Passing: the flag snaps toward the car's direction of travel and is pushed out sideways. A tall vehicle (van/truck) parked under it pushes the lowest part of the cloth up. |
| 7 | Walk zombies (or let a horde) past a wall flag on an outside wall. | They push it like the player does; CPU stays within the earlier budget (avg < 1 ms with ~50 flags). |
| 8 | `RealFlags_interactions(false)`, repeat step 1. | Back to 0.1.0 behaviour: the body passes through the flag. `RealFlags_interactions(true)` restores it. |
| 9 | Stand behind the flag's wall (other room / outside) right against it. | Nothing happens to the flag. |
| 10 | `RealFlags_status()` | One line, no errors. `geometry{sprites=… none=…}` > 0, `blockedByEstimate` normally 0 with vanilla furniture. No `[RealFlags] ERROR` in console.txt. |

## 0.2.1 re-test (fixes for the first run's findings, D61–D62)

| # | Do | Expect |
|---|---|---|
| 11 | Push into a wall flag from the front, slowly, then sideways along the wall, then crouch/stand under it. | The cloth bunches and slides **in front of** the wall and stays visible everywhere it moves; nothing disappears into the wall. Bunched parts stand a little off the wall. |
| 12 | Step away and watch it settle. Repeat twice in a row (a second push while it is still settling). | It swings back gently and takes a few seconds (≥ 4 s after the last touch) to come to rest; no sudden jump to the vanilla picture at the end. A second push simply continues the motion. |
| 13 | Walk through a wall flag at different angles (diagonally, along the wall, straight in). | It always comes back to its flat vanilla look within ~10 s; it never stays folded. |
| 14 | Go inside the building behind an outside wall flag and stand against that wall. | Nothing happens to the flag (it is hidden by the cutaway anyway, and bodies behind the wall are ignored). |
