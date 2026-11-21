# Fabric sound design

How Floppy Fabrics' cloth sounds are made and played (DECISIONS D111).

## Rebuilding the sounds

Needs Python 3.13 with `librosa` and `soundfile` (`pip install --user librosa soundfile matplotlib`), SoX and ffmpeg
(`winget install ChrisBagwell.SoX` / `winget install Gyan.FFmpeg`; found on PATH, through `$SOX` / `$FFMPEG`, or in the
winget package folder).

```powershell
python tools/sound/fabricsfx.py --src "D:/Sounds source"
```

Options: `--only flag,tarp` (a derived material pulls its base in), `--no-preview`, `--work`, `--out`, `--script`.
It rewrites `mod/42/media/sound/floppyfabrics/*.ogg` and the generated `mod/42/media/scripts/FloppyFabrics_sounds.txt`
in about two minutes, then `gradlew :pz-adapter:deployMod` ships them. Output is deterministic for a given config
(`seed`).

Sources (not in the repo): `flag new.wav` (60 s, heavy flag; replaced `Flag.wav`), `Curtains.wav`, `Tarp.wav`. Per material, `loopSeconds` and `snapLayer` (extra snaps mixed into the strong loop, × `snapRate`) override the defaults: the flag uses 1.5–3.5 s grains and a 20 s loop to keep its flapping rhythm. A new material = a new entry in
`tools/sound/materials.json` (either `source` or `derive` + a SoX effect chain) and a new `FabricSound.Material`.

## What the pipeline does

| Stage | Tool | What |
|---|---|---|
| ingest | ffmpeg | any format → 48 kHz mono float WAV |
| analyse | librosa | silence trim; **high-pass** where low 1/3-octave bands stop following the 1–6 kHz flutter envelope (correlation < 0.35 = rumble, not cloth), clamped to `highpassRange`; **low-pass** at the recording's bandwidth; **noise print** from the quietest 0.5 s only if it is ≥ 12 dB under the median |
| clean | SoX | `noisered` (when there is background to print), 24 dB/oct high/low-pass, trim, normalise |
| derive | SoX | effect chain on another material's cleaned audio (bunting = flag sped up ×1.3, thinner) |
| segment | librosa | 0.5 s windows scored by level + spectral flux → calm (bottom 40 %) and strong (top 45 %) regions; onset peaks × crest factor → snaps, start backtracked to the energy minimum, end where the envelope has fallen 24 dB |
| synthesise | numpy | concatenative granular loops: each grain picked from 14 candidates to continue the previous one's level and brightness, penalised for dropouts, repeats and (calm) the hardest transients; equal-power crossfades; loop-point search over ±10 % of the length for the least noticeable wrap; the strong loop gets snaps scattered through it (wrapping) |
| effects | SoX | gentle `compand` (+ small `reverb` room for curtains) on the loop tiled ×3, keeping the middle copy, so the seam stays clean; snaps get a presence lift and the same room |
| loudness | ffmpeg | EBU R128 (loudnorm analysis) → calm −26 LUFS, strong −20 LUFS, snaps −3 dBFS peak, plus per-material offsets; memoryless soft limiter |
| export | ffmpeg | mono 44.1 kHz Ogg Vorbis q5 + the sound script |
| report | librosa | `build/sound/report.json` (per material: analysis decisions, segment lengths, loudness, seam check), `layers.png` spectrograms, `preview_<material>.ogg` |

The seam check reports, for the wrap of each loop, the 50 ms level step and the sample jump as percentiles of the
same measure at every point of that loop: 50 = an ordinary moment, 99 = the most abrupt spot.

`preview_<material>.ogg` is a 40 s wind sweep (still → gale → breeze) mixed with the same mapping the game uses
(`RUNTIME` in the script mirrors `FabricSound`). Listen to these first when tuning.

## How the game plays them

`FabricSound` (Java). Each frame every drawn, simulated cloth reports where it is, the wind at it and how fast its
particles really move (`MotionProbe`). Then:

- **drive** = 0.6 · wind (1 → 14 m/s, smoothstep) + 0.4 · cloth speed / full-scale speed
  (wall flags and curtains 1.3 m/s, pole flags 2.4, canopies 4.0, bunting 1.8 — measured in the simulator at 16–20 m/s);
- **level** = drive^0.8 × option volume × (game ambient volume, through `master = Ambient`);
- the **calm** and **strong** loops crossfade (equal power) over drive 0.30 → 0.85;
- **pitch** follows the drive (faster flutter), with a slow random drift per cloth;
- **snaps** above drive 0.4, more often in stronger wind and right when a gust fills the cloth.
- **highrise** (D112): zooming out lifts the ears. From the default zoom to the farthest one every fabric sound
  gets quieter (to 32 %) and duller (FMOD's channel low-pass), and snaps fade further (to 45 %), so from above cloth
  is a soft part of the street. `FloppyFabrics_status()` shows the current `height=` (0 street, 1 farthest zoom).

Because the cloth's own motion is part of the drive, a zombie walking through a curtain or a car's draught past a flag
makes it rustle even on a still day. At most 3 cloths per material (10 in all) are voiced: the loudest after distance
(fading out over 18–24 tiles). Cloths off screen are not simulated, so their voice fades out.

Option: Options > Mods > Floppy Fabrics > *Fabric sound volume* (0 = off). Console: `FloppyFabrics_soundVolume(x)`,
and `FloppyFabrics_status()` lists the active voices (`sound{... [flag d=0.62 c=0.21 s=0.44]}`).

## In-game check

1. `FloppyFabrics_forceWind(5, 0)`: near a flag a soft rustle; `FloppyFabrics_status()` shows a `flag` voice.
2. `FloppyFabrics_forceWind(60, 0)`: hard flapping and snaps; walk away — it fades out by ~24 tiles.
3. Open a window with a curtain, run past it in still air: a short rustle.
4. Market canopy / awning in wind: heavier tarp flapping; bunting: light, fast flutter; string lights: nothing.
5. Zoom out step by step in a gale: the flapping gets quieter and duller, snaps fade first; zoom back in: crisp again.
6. Pause: silence; unpause: it comes back. Slider to 0: everything stops at once.
