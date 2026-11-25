"""Worn-cloth Stage 0: build a local test mod with copies of the vanilla long coat whose front hem is skinned to
bones that exist only in the garment file (FF_TestFront, FF_TestFront02, FF_TestFrontNub).

Nothing vanilla-derived is committed: the script reads the game's .X files and writes a throwaway mod into the
Zomboid mods folder (not into this repo, and not into FloppyFabrics, which deployMod syncs and would wipe).

    py tools/make_test_garment.py [--game "D:/Program Files/Steam/steamapps/common/ProjectZomboid"]
                                  [--mods "C:/Users/<you>/Zomboid/mods"]

Result: mods/FloppyFabricsGarmentTest/42/media/models_X/Skinned/Clothes/FF_Test_LongCoat_Bob.X and _Kate.X.
In game the swap is  FloppyFabrics_garmentSwap("LongCoat_Bathrobe", "skinned\\clothes\\ff_test_longcoat_bob")
(see docs/WORN_CLOTH_STAGE0_TEST.md).

What changes in the copy:
  * the Frame Bip01_DressFront subtree is duplicated next to itself with every Bip01_DressFront* renamed to
    FF_TestFront* (same transforms, so the bind pose is identical);
  * the SkinWeights blocks of "Bip01_DressFront" and "Bip01_DressFront02" are renamed to the FF_ bones, so the front
    hem vertices now follow bones the animation skeleton does not have.
"""
import argparse
import os
import re
import sys

SOURCES = {"Bob": "Bob_LongCoat.X", "Kate": "Kate_LongCoat.x"}


def find_block(lines, start):
    """Index of the line closing the brace block that opens on lines[start]."""
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if depth == 0 and i > start:
            return i
    raise ValueError("unbalanced braces from line %d" % (start + 1))


def convert(text):
    lines = text.splitlines(keepends=True)
    start = next((i for i, l in enumerate(lines) if re.match(r"\s*Frame Bip01_DressFront\s*\{", l)), None)
    if start is None:
        raise ValueError("no Frame Bip01_DressFront")
    end = find_block(lines, start)
    copy = [l.replace("Bip01_DressFront", "FF_TestFront") for l in lines[start:end + 1]]
    lines[end + 1:end + 1] = copy
    out = "".join(lines)
    renamed = 0
    for old, new in (('"Bip01_DressFront";', '"FF_TestFront";'), ('"Bip01_DressFront02";', '"FF_TestFront02";')):
        n = out.count(old)
        if n != 1:
            raise ValueError("expected one SkinWeights for %s, found %d" % (old, n))
        out = out.replace(old, new)
        renamed += n
    return out, renamed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game", default="D:/Program Files/Steam/steamapps/common/ProjectZomboid")
    ap.add_argument("--mods", default=os.path.join(os.path.expanduser("~"), "Zomboid", "mods"))
    a = ap.parse_args()
    src_dir = os.path.join(a.game, "media", "models_X", "Skinned", "Clothes")
    root = os.path.join(a.mods, "FloppyFabricsGarmentTest")
    out_dir = os.path.join(root, "42", "media", "models_X", "Skinned", "Clothes")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(os.path.join(root, "common"), exist_ok=True)
    with open(os.path.join(root, "42", "mod.info"), "w", encoding="utf-8") as f:
        f.write("name=Floppy Fabrics - garment test (local)\nid=FloppyFabricsGarmentTest\n"
                "description=Local Stage 0 test models for Floppy Fabrics worn cloth. Do not upload.\n"
                "versionMin=42.21\n")
    for body, name in SOURCES.items():
        with open(os.path.join(src_dir, name), encoding="utf-8-sig", errors="strict") as f:
            text = f.read()
        out, renamed = convert(text)
        dst = os.path.join(out_dir, "FF_Test_LongCoat_%s.X" % body)
        with open(dst, "w", encoding="utf-8", newline="") as f:
            f.write(out)
        print("%s: %d skin weight blocks renamed -> %s" % (name, renamed, dst))
    print("Enable 'Floppy Fabrics - garment test (local)' in the mod list (after Floppy Fabrics).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
