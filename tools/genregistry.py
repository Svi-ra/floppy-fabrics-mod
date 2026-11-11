#!/usr/bin/env python3
"""Regenerates docs/registry/tiles.json and docs/TILE_REGISTRY.md from the shipped
flags.json / decorations.json. Descriptive names and roles are curated below (from DECISIONS.md);
the sprite lists themselves always come from the JSON files, so new entries cannot be missed.

    python tools/genregistry.py
"""
import json, re, collections, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
MEDIA = ROOT / "mod/42/media/realflags"
DOCS = ROOT / "docs"
FACE = {"S": "south-facing (lies along the north wall)", "E": "east-facing (lies along the west wall)",
        "N": "north-facing (on the south edge)", "W": "west-facing (on the east edge)"}
FAMILY = {"wall": "sloped wall awning", "flat": "flat awning", "back": "awning, back view",
          "cone": "conical awning", "quarter": "quarter-round awning", "curvedback": "curved awning, back view",
          "arch": "arched awning", "archback": "arched awning, back view", "domeback": "dome awning, back view"}
LINE = {"bunting": "bunting string (pennants)", "bulbs": "string of large festoon bulbs",
        "fairy": "string of small fairy lights"}
LIGHTS = {"bulbs": "Large", "fairy": "Small"}


def split(s):
    m = re.match(r"^(.*)_(\d+)$", s)
    return m.group(1), int(m.group(2))


def describe(e, fname):
    s, kind, face = e["sprite"], e["kind"], e.get("facing")
    ts, i = split(s)
    g = e.get("group")
    cat, role, name, notes = None, "primary", None, e.get("comment", "")
    if kind == "pole":
        cat, name = "pole flag", "American flag pole, upper tile (replaced furled cloth)"
    elif kind == "wall":
        if ts == "walls_decoration_01":
            cat, name = "wall flag", f"Wall-mounted flag, {FACE[face]}"
        elif ts == "location_restaurant_bar_smutz":
            cat, name = "wall flag", f"White wall banner, {FACE[face]}"
        elif "church_small" in ts:
            cat, name = "banner", f"Church banner, {FACE[face]}"
        elif "medical" in ts:
            cat = "curtain"
            name = f"Hospital privacy curtain ({'white' if i >= 144 else 'blue'}), {FACE[face]}"
        elif "theatre" in ts:
            cat, name = "curtain", f"Theatre stage curtain, {FACE[face]}"
        elif "knox" in ts:
            cat, name = "wall flag", f"Fort Knox flag, 3-tile cloth, {FACE[face]}"
        elif ts == "signs_one-off_09":
            if i <= 327:
                cat, name = "banner", f"Riverside street banner segment {g['offset'] and (-g['offset'][1] if g['offset'][1] else g['offset'][0]) + 1}/16, {FACE[face]}"
            else:
                cat, name = "banner", f"Riverside pole banner on light pole, {FACE[face]}"
    elif kind == "line":
        t = e["line"]["type"]
        cat = "string line"
        if t == "bunting":
            name = "Bunting, tile of a 2-tile string"
        else:
            name = {"bulbs": "Large bulb string lights", "fairy": "Small fairy string lights"}[t] + ", tile of a 2-tile string"
        if ts == "walls_decoration_01" and 76 <= i <= 79:
            name = "Rainbow string lights, tile of a 2-tile string"
        name += f", {'south (runs east)' if e['line']['a'][1] == 0 and e['line']['b'][1] == 0 else 'east (runs south)'}"
        if t != "bunting":
            notes = (notes + " Lit variant: " + ts + "_on_" + str(i) + " (derived).").strip()
    elif kind == "canopy":
        cat = "canopy"
        name = f"{FAMILY[e['family']].capitalize()}, {FACE[face]}"
    elif kind == "drape":
        cat, name = "drape", f"Church altar cloth, {FACE[face]}"
    return dict(sprite=s, tileset=ts, index=i, name=name, category=cat, kind=kind,
                facing=face, config=fname, group=(g["id"] if g else None), role=role, notes=notes)


tiles = []
for fname in ("flags.json", "decorations.json"):
    d = json.load(open(MEDIA / fname, encoding="utf-8"))
    for e in d["flags"]:
        t = describe(e, fname)
        t["source"] = "vanilla"
        t["state"] = "shipped"
        tiles.append(t)

# Referenced helper / host sprites that are not entries themselves
refs = [
    dict(sprite="street_decoration_01_24", name="American flag pole, lower tile (shaft source)", category="pole flag",
         role="reference", notes="Clean shaft slice (src 60,100-68,164) copied by flags.json staticQuads onto the upper tile."),
    dict(sprite="lighting_outdoor_01_4", name="Street light pole, bare (host of Riverside pole banner)", category="banner",
         role="host", notes="signs_one-off_09_336/337 hang from it, as own object or as attached sprite of this pole (D90)."),
]
for r in refs:
    ts, i = split(r["sprite"])
    r.update(tileset=ts, index=i, kind=None, facing=None, config="flags.json", group=None, source="vanilla", state="shipped")
    tiles.append(r)

tiles.sort(key=lambda t: (t["tileset"], t["index"]))

ranges = [
    dict(pattern="fixtures_windows_curtains_01_*, fixtures_windows_curtains_02_*", source="vanilla", category="window curtain",
         name="Vanilla window curtains (144 tiles; each row of 8 = closed W/E/N/S then open)", config="auto (curtainN/S/W/E tile type, DECISIONS D63/D64)",
         notes="No JSON entry; geometry measured at runtime. Any mod's sprite with a curtain tile type works the same."),
    dict(pattern="fixtures_windows_curtains_01_16..23", source="vanilla", category="window curtain",
         name="Player-hung sheets (closed 16..19, rolled 20..23)", config="auto (D72)",
         notes="3D sprite models; swapped to the 2D sprite while the window is open/broken."),
    dict(pattern="<tileset>_on_<n>", source="any", category="lit overlay",
         name="Lit overlay of a string-light tile", config="derived (D84)",
         notes="Vanilla's lit-overlay naming; mapped to the base sprite's definition by FlagRegistry.onSpriteName."),
]
excluded = [
    ("location_community_medical_01_40, _44", "Curtain rails, no cloth (D77)"),
    ("location_entertainment_theatre_01_95, _100", "Stage curtain rods, no cloth (D77)"),
    ("lighting_outdoor_01_48..51", "Flood lights named 'Lights', not strings (D80)"),
    ("lighting_indoor_01_56..58", "Metal lamps, not strings (D80)"),
]

textures = [dict(id="pole_flag", file="mod/42/media/realflags/pole_flag.png", runtimePath="media/realflags/pole_flag.png",
                 size=[512, 286], format="PNG 8-bit RGB",
                 sha256="19a763d643676d220268f3d150b6cf2b46290a089c0c1163e28f0866914e1393",
                 usedBy=["street_decoration_01_16"], config="flags.json canvas.texture, uv [0,0,1,1]",
                 source="realflags", origin="Magnific creation YMjQSixWeC, downscaled from 2752x1536 (D91)")]
reg = dict(schema=1, textures=textures,
           comment="Generated by tools/genregistry.py. Add third-party mods as new entries in 'sources' and tag their tiles with that source id.",
           sources=[dict(id="vanilla", name="Project Zomboid", version="42.21.0", kind="vanilla"),
                    dict(id="realflags", name="Floppy Fabrics (custom textures)", version="0.6.0", kind="mod")],
           tiles=tiles, ranges=ranges, excluded=[dict(sprites=a, reason=b) for a, b in excluded])
(DOCS / "registry").mkdir(exist_ok=True)
json.dump(reg, open(DOCS / "registry/tiles.json", "w", encoding="utf-8", newline="\n"), indent=1)
(DOCS / "registry/tiles.json").write_text((DOCS / "registry/tiles.json").read_text(encoding="utf-8") + "\n", encoding="utf-8")

# ---------------- markdown
def prev(sp):
    return f'<img src="registry/previews/{sp}.png" width="48" alt="{sp}">' if (DOCS / "registry/previews" / (sp + ".png")).exists() else "—"

out = ["# RealFlags tile registry", "",
       "Every tile RealFlags reads or animates, with its exact sprite name. Machine-readable twin: "
       "[`registry/tiles.json`](registry/tiles.json). Custom textures are registered separately in [CUSTOM_TEXTURES.md](CUSTOM_TEXTURES.md).", "",
       "Regenerate with `python tools/genregistry.py` (sprite lists come from `flags.json` / `decorations.json`; names are curated in the script).", "",
       "**Names** are descriptive labels derived from the tile family, `facing`, and the evidence in `DECISIONS.md`; "
       "they are not vanilla display names. **Facing:** S lies along the north wall, E along the west wall, N/W on the south/east edge.", "",
       "## Sources", "", "| Source id | Name | Version | Kind |", "|---|---|---|---|",
       "| `vanilla` | Project Zomboid | 42.21.0 | vanilla |", "| `realflags` | Floppy Fabrics (custom textures) | 0.6.0 | mod |", "",
       "### Adding a third-party mod", "",
       "1. Add a row to `sources` (`id` = the mod's id, name, version, `kind: \"mod\"`).",
       "2. Add its tiles to `tiles` with `\"source\": \"<id>\"`; keep the sprite name exact.",
       "3. The mod itself ships `media/realflags/*.json` (see [FLAGS_JSON.md](FLAGS_JSON.md)); registry entries should name that file in `config`.", "",
       "Sprite names are globally unique, so `source` is informational, not a namespace.", "",
       "Previews (`registry/previews/<sprite>.png`, full 128x256 2x tile frames) are extracted from the game's `Tiles2x.pack` with "
       "`python tools/flagtool.py extract <Tiles2x.pack> docs/registry/previews <sprites...>`. They are Project Zomboid art (c) The Indie Stone, "
       "included for documentation only; delete the folder to drop them (the table falls back to a dash).", ""]
c = collections.Counter(t["category"] for t in tiles if t["role"] == "primary")
out += ["## Summary", "", f"{len(tiles)} sprites registered ({sum(c.values())} configured entries, {len(refs)} reference/host tiles) plus pattern-based entries.", "",
        "| Category | Sprites |", "|---|---|"] + [f"| {k} | {v} |" for k, v in sorted(c.items())] + [""]
cats = collections.OrderedDict()
for t in tiles:
    cats.setdefault(t["category"], []).append(t)
for cat, ts in cats.items():
    out += [f"## {cat.capitalize()}", "", "| Preview | Sprite | Name | Kind | Facing | Group | Config | Role |", "|---|---|---|---|---|---|---|---|"]
    for t in ts:
        out.append(f"| {prev(t['sprite'])} | `{t['sprite']}` | {t['name']} | {t['kind'] or '—'} | {t['facing'] or '—'} | {t['group'] or '—'} | {t['config']} | {t['role']} |")
    notes = [t for t in ts if t["notes"]]
    if notes:
        out += ["", "Notes:"] + [f"- `{t['sprite']}`: {t['notes']}" for t in notes]
    out.append("")
out += ["## Custom textures (created for RealFlags)", "",
        "Details and notes: [CUSTOM_TEXTURES.md](CUSTOM_TEXTURES.md).", "",
        "| Preview | ID | File | Size | Used by | Config | Origin |", "|---|---|---|---|---|---|---|"]
for x in textures:
    out.append(f'| <img src="../{x["file"]}" width="96" alt="{x["id"]}"> | `{x["id"]}` | `{x["file"]}` | {x["size"][0]}x{x["size"][1]} | '
               + ", ".join(f"`{u}`" for u in x["usedBy"]) + f' | {x["config"]} | {x["origin"]} |')
out += [""]
out += ["## Pattern-based entries (no per-sprite JSON entry)", "", "| Pattern | Name | Config | Notes |", "|---|---|---|---|"]
out += [f"| `{r['pattern']}` | {r['name']} | {r['config']} | {r['notes']} |" for r in ranges]
out += ["", "## Examined and deliberately excluded", "", "| Sprites | Reason |", "|---|---|"]
out += [f"| `{a}` | {b} |" for a, b in excluded] + [""]
(DOCS / "TILE_REGISTRY.md").write_text("\n".join(out), encoding="utf-8", newline="\n")
print(len(tiles), "tiles;", dict(c))
