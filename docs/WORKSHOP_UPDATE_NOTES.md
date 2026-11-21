# Workshop update notes

Player-facing notes for the Steam Workshop page (item 3842366760), written in plain language as changes land. When the
mod is uploaded, everything under **Pending** is summarised into the one changenote for that upload (and into the
page's changelog section if it has one), then moved under a dated heading in **Published**, with the version.

Rules for entries: what a player sees or can do, not how it works; name other mods with their Workshop names; say when
something needs another mod, and that nothing changes without it; one line per change where possible. Technical detail
lives in `CHANGELOG.md` and `DECISIONS.md`.

## Pending (next upload)

**New**
    - **Fabric sounds:** flags flap, curtains rustle, tarps and awnings thump and bunting flutters. Each cloth sounds
      the way it moves: soft in a breeze, hard flapping with snaps in a gale, and a rustle when someone brushes past it
      even on a still day. Volume in Options > Mods > Floppy Fabrics (0 turns it off); it also follows the game's
      ambient volume. Zoom the camera out and the sounds drift away and soften, as if heard from a rooftop.
    - **More settings** in Options > Mods > Floppy Fabrics:
      - **Enable** switch: turn the mod off and back on without leaving the game.
      - **Wind**: Vanilla, Calm (never stronger than a fresh breeze), Breezy or Windy (fabric keeps moving on still
        days), or Custom with your own minimum and maximum wind. Only the fabric feels it; the weather is unchanged.
      - **Quality**: Low, Medium, High (as before) or Ultra, or Custom with simulation detail, how many cloths move at
        once and how far away they move. If you had raised *Simulation detail* before, choose Custom to keep it.
      - Switches for people and vehicles pushing fabric, window curtains, light past curtains, and bunting, string
        lights and canopies.
    - **Translations:** the options and the mod description are now in all 28 languages the game supports. They
      follow the game's language setting.

**Fixed**
    - **Wind Sway compatibility:** with Wind Sway also installed, nothing moved (flags, curtains, banners, awnings),
      depending on mod order. Flags, banners and decorations now move with Wind Sway in any order. If curtains and
      door sheets still stay still, the mod notices on its own, repairs itself a few seconds after the world loads, and
      writes a note to the game log.
    - **Wind sprite effects option:** with the game's wind sprite effects turned on (Wind Sway turns them on too), some
      flags, curtains and window sheets stayed as still pictures. They now move.

## Published

- 0.7.1 (2026-11-20, item 3842366760)

**Fixed**
    - **ZombieBuddy "invalid ZBS" error:** the mod would not load for some players, because ZombieBuddy could not reach
      Steam to check the author's signature. The signature is removed for now. ZombieBuddy shows its usual "allow
      this mod?" prompt again; allow it and the mod works. The signature comes back in a later update, once the author
      is in ZombieBuddy's list of known authors.

- 0.7.0 (2026-11-20, item 3842366760)

**New**
    - The mod is now **signed for ZombieBuddy** (author svi_ra). ZombieBuddy shows who signed it, and you can choose to
      trust the author once so future updates load without asking again.
    - **Military training nets** (the cargo net under a beam and the tall net between posts) now sway in the wind and give
      when you walk into them, each as one piece across its whole length. The frames and posts stay put.
    - **Military tents** (green and tan): the roof and gables now breathe in the wind and flap in a storm, and the eaves
      flap between their guy ropes. The walls stay put.
    - **Tents and shelters** now breathe in the wind and flap in a storm: the tarp tent, all four camping tents, the
      improvised tent and the tarp and hide shelters. Poles, pegs and bedding stay put.
    - **Car-wash entrance covers** now swing aside when you drive or walk through them, and sway in the wind outdoors.
    - **Volleyball nets** now sway in the wind and give when you walk into them. Each net moves as one piece across its
      whole length, however long the map made it; the poles stay put.
    - Compatibility with **Fence Sheets**: sheets and tarps hung on fences and empty door frames now move. Wind through the
      fence billows them, and people walking through a covered doorway push the sheet aside. Tarps stay tied at their top
      and sides, so a run of tarp tiles moves as one sheet with no gaps. Nothing changes if you don't use Fence Sheets.
    - Sheets hung on **doors** now move in the wind when they face the outdoors (hung on the outside, or on an open door).
    - Sheets hung on the **outside of a window** now move in the wind whatever the window is doing, like a flag on the wall.
      Works with **Fitted Sheets**' made-to-measure sheets too.
    
    **Fixed**
    - Flags and banners that span several tiles (the Fort Knox flag, the long Riverside street banner) no longer come
      apart between tiles.
    - A moving sheet on the outside of a door no longer disappears behind the door.

- 0.6.0 (2026-11-19, item 3842366760) — notes were written directly on the page; history in this file starts after it.
