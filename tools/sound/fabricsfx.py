#!/usr/bin/env python3
"""
Floppy Fabrics sound pipeline: raw field recordings -> game-ready, wind-adaptive fabric sound sets.

    python tools/sound/fabricsfx.py --src "D:/Sounds source"            # build everything into the mod
    python tools/sound/fabricsfx.py --src ... --only flag,tarp           # some materials only
    python tools/sound/fabricsfx.py --src ... --no-preview               # skip the wind-sweep auditions

For every material in tools/sound/materials.json it produces three layers the runtime (FabricSound.java) mixes
from the simulated cloth:

    <mat>_calm.ogg      seamless loop, gentle rustle          (low wind / slow cloth)
    <mat>_strong.ogg    seamless loop, hard flapping           (high wind / fast cloth)
    <mat>_snap_N.ogg    one-shot cracks and whips              (gust peaks of the cloth's own motion)

Stages (each writes into --work so every step can be listened to):
  1 ingest    ffmpeg   any format -> 48 kHz mono float WAV
  2 analyse   librosa  silence trim, long-term spectrum, auto high/low-pass, quietest stretch for the noise print
  3 clean     SoX      noise print + noisered, 24 dB/oct high/low-pass, trim, peak normalise
                       (derived materials: SoX effect chain on another material's cleaned audio instead)
  4 segment   librosa  0.5 s windows scored by level + spectral flux -> calm / strong regions;
                       onset peaks x crest factor -> snap candidates with backtracked starts and decay-based ends
  5 synthesise numpy   concatenative granular loops: grains picked to match the level and brightness of the one
                       before, equal-power crossfades, seamless wrap; strong loop layered with scattered snaps
  6 effects   SoX      compand (+ small room for indoor cloth), applied on a 3x tiled loop so the seam stays clean
  7 loudness  ffmpeg   EBU R128 integrated loudness -> per-layer targets; snaps peak-normalised
  8 export    ffmpeg   mono 44.1 kHz Ogg Vorbis into mod/42/media/sound/floppyfabrics, plus the sound script
  9 report    librosa  seam check, spectrograms, wind-sweep previews rendered with the runtime's own mapping
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field

import numpy as np
import librosa
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))

# ---------------------------------------------------------------------------------------------------------- tools


def find_tool(name: str) -> str:
    """ffmpeg / sox from $FFMPEG / $SOX, PATH, or a winget install (winget's PATH change needs a new shell)."""
    env = os.environ.get(name.upper())
    if env and os.path.isfile(env):
        return env
    p = shutil.which(name)
    if p:
        return p
    local = os.environ.get("LOCALAPPDATA", "")
    hits = glob.glob(os.path.join(local, "Microsoft", "WinGet", "Packages", "*", "**", name + ".exe"), recursive=True)
    if hits:
        return hits[0]
    sys.exit(f"{name} not found: install it (winget install Gyan.FFmpeg / ChrisBagwell.SoX) or set ${name.upper()}")


FFMPEG = SOX = ""


def run(cmd: list[str]) -> str:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stderr}")
    return r.stderr + r.stdout


def db(x):
    return 20.0 * np.log10(np.maximum(x, 1e-12))


def undb(d):
    return 10.0 ** (d / 20.0)


def moving_mean(x: np.ndarray, w: int, mode: str = "valid") -> np.ndarray:
    """Moving average in O(n) (np.convolve is O(n*w), minutes on a 0.5 s window)."""
    w = max(1, min(w, len(x)))
    if mode == "same":
        x = np.pad(x, (w // 2, w - 1 - w // 2))
    c = np.concatenate([[0.0], np.cumsum(x, dtype=np.float64)])
    return (c[w:] - c[:-w]) / w


def log(msg: str):
    print(msg, flush=True)


# ---------------------------------------------------------------------------------------------------------- 1 ingest


def ingest(src: str, dst: str, sr: int):
    run([FFMPEG, "-y", "-v", "error", "-i", src, "-ac", "1", "-ar", str(sr), "-c:a", "pcm_f32le", dst])


# ---------------------------------------------------------------------------------------------------------- 2 analyse


@dataclass
class Analysis:
    start: float
    end: float
    highpass: float
    lowpass: float
    noise_at: float
    noise_len: float
    notes: list[str] = field(default_factory=list)
    noise_ok: bool = False


def analyse(path: str, cfg: dict) -> Analysis:
    y, sr = sf.read(path, dtype="float32")
    notes = []
    # silence at the head and tail (relative to the loudest part)
    _, (i0, i1) = librosa.effects.trim(y, top_db=45, frame_length=2048, hop_length=512)
    pad = int(0.02 * sr)
    i0, i1 = max(0, i0 - pad), min(len(y), i1 + pad)
    if i0 > 0 or i1 < len(y):
        notes.append(f"trimmed silence: {i0 / sr:.2f}s head, {(len(y) - i1) / sr:.2f}s tail")
    yt = y[i0:i1]

    n_fft, hop = 4096, 1024
    S = np.abs(librosa.stft(yt, n_fft=n_fft, hop_length=hop)) ** 2
    f = librosa.fft_frequencies(sr=sr, n_fft=n_fft)
    ltas = 10 * np.log10(S.mean(axis=1) + 1e-20)  # long-term average spectrum, dB

    # high-pass: what belongs to the fabric moves with the fabric. Each 1/3-octave band from 25 to 400 Hz has its
    # envelope correlated with the 1-6 kHz flutter envelope (the part of the sound that is unmistakably cloth);
    # bands that do not follow it are wind on the microphone, traffic or handling noise. The cut-off is the top of
    # the highest uncorrelated band, clamped to the material's range (a tarp keeps its thumps, a curtain loses
    # everything under the room).
    hp_lo, hp_hi = cfg.get("highpassRange", [60, 200])
    hp = None
    if cfg.get("highpass", "auto") == "auto":
        fine = np.abs(librosa.stft(yt, n_fft=8192, hop_length=1024)) ** 2  # 5.9 Hz bins: resolves 25 Hz bands
        ff = librosa.fft_frequencies(sr=sr, n_fft=8192)
        lenv = lambda lo, hi: np.log(fine[(ff >= lo) & (ff < hi)].sum(axis=0) + 1e-20)
        ref = lenv(1000, 6000)
        cut, corr_txt = None, []
        for c in 25 * 2 ** (np.arange(0, 13) / 3):  # 25 .. 400 Hz
            lo, hi = c / 2 ** (1 / 6), c * 2 ** (1 / 6)
            r = float(np.corrcoef(lenv(lo, hi), ref)[0, 1])
            corr_txt.append(f"{c:.0f}:{r:.2f}")
            if r < 0.35:
                cut = hi
        raw = cut if cut is not None else hp_lo
        hp = float(np.clip(raw, hp_lo, hp_hi))
        notes.append(f"band/flutter envelope correlation {' '.join(corr_txt)} -> uncorrelated below {raw:.0f} Hz "
                     f"-> high-pass {hp:.0f} Hz")
    elif cfg.get("highpass") != "skip":
        hp = float(cfg["highpass"])

    # low-pass: where the recording's own bandwidth ends (a lossy source stops at 10-16 kHz; above it is hiss only).
    lp = None
    if cfg.get("lowpass", "auto") == "auto":
        rms = librosa.feature.rms(S=np.sqrt(S), frame_length=n_fft)[0]
        quiet = S[:, rms <= np.percentile(rms, 10)]
        floor = 10 * np.log10(quiet.mean(axis=1) + 1e-20) if quiet.shape[1] else ltas - 30
        floor_hf = np.median(floor[f > 16000]) if np.any(f > 16000) else floor.min()
        above = np.where((ltas > floor_hf + 10.0) & (f > 2000))[0]
        bw = f[above.max()] if len(above) else 16000
        lp = float(np.clip(bw * 1.05, 6000, 18000))
        notes.append(f"signal bandwidth ~{bw:.0f} Hz -> low-pass {lp:.0f} Hz")
    elif cfg.get("lowpass") != "skip":
        lp = float(cfg["lowpass"])

    # noise print: the quietest half second, but only if it really is background. A recording with no pause in it
    # (a curtain rustling throughout) has no noise to print: its quietest stretch is soft cloth, and noisered would
    # carve holes wherever the cloth is soft. Required: 12 dB under the median half-second.
    win = int(0.5 * sr)
    usable_noise = False
    if len(yt) > 2 * win:
        e = moving_mean(yt.astype(np.float64) ** 2, win)
        k = int(np.argmin(e[:: hop // 4])) * (hop // 4)
        noise_at, noise_len = (i0 + k) / sr, win / sr
        margin = 10 * np.log10(np.median(e) + 1e-24) - 10 * np.log10(e[k] + 1e-24)
        usable_noise = margin >= 12.0
        notes.append(f"quietest 0.5 s is {margin:.1f} dB under the median -> "
                     + ("noise print taken there" if usable_noise else "no background to print: noise reduction off"))
    else:
        noise_at, noise_len = i0 / sr, min(0.25, len(yt) / sr)
    return Analysis(i0 / sr, i1 / sr, hp or 0.0, lp or 0.0, noise_at, noise_len, notes, usable_noise)


# ---------------------------------------------------------------------------------------------------------- 3 clean


def clean(src: str, dst: str, a: Analysis, cfg: dict, work: str, name: str):
    effects = []
    nr = float(cfg.get("noiseReduction", 0.0))
    if nr > 0 and a.noise_ok:
        prof = os.path.join(work, f"{name}.noiseprof")
        run([SOX, src, "-n", "trim", f"{a.noise_at:.4f}", f"{a.noise_len:.4f}", "noiseprof", prof])
        effects += ["noisered", prof, f"{nr:.3f}"]
    effects += ["trim", f"{a.start:.4f}", f"={a.end:.4f}"]
    if a.highpass > 0:  # two 2-pole sections = 24 dB/octave
        effects += ["highpass", "-2", f"{a.highpass:.0f}", "highpass", "-2", f"{a.highpass:.0f}"]
    if a.lowpass > 0:
        effects += ["lowpass", "-2", f"{a.lowpass:.0f}", "lowpass", "-2", f"{a.lowpass:.0f}"]
    effects += ["gain", "-n", "-1"]
    # noisered must see the untrimmed file (the noise print offsets are in its time base): it goes first
    run([SOX, src, dst] + effects)


def derive(src: str, dst: str, cfg: dict, sr: int):
    run([SOX, src, "-r", str(sr), dst] + list(cfg["effects"]) + ["gain", "-n", "-1"])


# ---------------------------------------------------------------------------------------------------------- 4 segment


@dataclass
class Segmentation:
    hop: int
    win_starts: np.ndarray      # window start samples
    win_len: int
    calm: np.ndarray            # bool per window
    strong: np.ndarray
    transient: np.ndarray       # bool per onset frame (frames a calm grain must not contain)
    snaps: list[tuple[int, int, float]]  # (start, end, score) in samples


def segment(y: np.ndarray, sr: int, cfg: dict, snap_count: int) -> Segmentation:
    hop = 256
    onset = librosa.onset.onset_strength(y=y, sr=sr, hop_length=hop)
    rms = librosa.feature.rms(y=y, frame_length=2048, hop_length=hop, center=True)[0]
    n = min(len(onset), len(rms))
    onset, rms = onset[:n], rms[:n]
    rdb = db(rms)

    # windows: level and flux, z-scored and summed
    wl, wh = int(0.5 * sr / hop), int(0.125 * sr / hop)
    starts = np.arange(0, max(1, n - wl), wh)
    lev = np.array([rdb[s:s + wl].mean() for s in starts])
    flux = np.array([onset[s:s + wl].mean() for s in starts])
    z = lambda v: (v - v.mean()) / (v.std() + 1e-9)
    score = z(lev) + 0.6 * z(flux)
    calm = score <= np.percentile(score, 40)
    strong = score >= np.percentile(score, 55)

    transient = onset > np.percentile(onset, 98.5)

    # snap candidates: onset peaks, scored by flux x crest factor
    gap = max(1, int(cfg.get("snapMinGap", 0.3) * sr / hop))
    peaks = librosa.util.peak_pick(onset, pre_max=3, post_max=3, pre_avg=20, post_avg=20,
                                   delta=float(np.std(onset)) * 0.5, wait=gap)
    peaks = peaks[(peaks > 4) & (peaks < n - 4)]
    starts_bt = librosa.onset.onset_backtrack(peaks, rms) if len(peaks) else peaks
    cands = []
    maxlen = int(cfg.get("snapMaxLength", 0.45) * sr)
    for p, b in zip(peaks, starts_bt):
        s0 = max(0, int(b) * hop - int(0.008 * sr))
        pk = int(p) * hop
        seg = y[pk:pk + int(0.03 * sr)]
        ctx = y[max(0, pk - int(0.15 * sr)):pk + int(0.15 * sr)]
        if len(seg) == 0 or len(ctx) == 0:
            continue
        crest = float(np.abs(seg).max() / (np.sqrt(np.mean(ctx ** 2)) + 1e-9))
        # end: the 10 ms envelope has fallen 24 dB below the snap's peak, or the length cap
        env = np.sqrt(np.maximum(moving_mean(y[pk:pk + maxlen] ** 2, int(0.01 * sr), mode="same"), 0.0))
        top = env[:int(0.05 * sr)].max() if len(env) else 0
        below = np.where(env[int(0.03 * sr):] < top * undb(-24))[0]
        e = pk + int(0.03 * sr) + (int(below[0]) if len(below) else len(env) - int(0.03 * sr))
        e = min(len(y), max(e, s0 + int(0.08 * sr)))
        cands.append((s0, e, float(onset[p]) * crest))
    cands.sort(key=lambda c: -c[2])
    snaps: list[tuple[int, int, float]] = []
    for c in cands:  # best first, no overlaps
        if all(c[1] <= s[0] or c[0] >= s[1] for s in snaps):
            snaps.append(c)
        if len(snaps) >= snap_count:
            break
    snaps.sort()
    return Segmentation(hop, starts * hop, wl * hop, calm, strong, transient, snaps)


def regions(seg: Segmentation, mask: np.ndarray) -> list[tuple[int, int]]:
    """Merged sample ranges of the windows selected by mask."""
    out: list[list[int]] = []
    for s, m in zip(seg.win_starts, mask):
        if not m:
            continue
        a, b = int(s), int(s) + seg.win_len
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return [(a, b) for a, b in out]


# ---------------------------------------------------------------------------------------------------------- 5 synthesise


def grain_features(y, a, b, sr):
    n = int(0.05 * sr)
    head, tail = y[a:a + n], y[b - n:b]
    c = lambda x: float(librosa.feature.spectral_centroid(y=x, sr=sr, n_fft=512, hop_length=256).mean()) if len(x) >= 512 else 0.0
    return db(np.sqrt(np.mean(head ** 2))), db(np.sqrt(np.mean(tail ** 2))), c(head), c(tail)


def granular_loop(y: np.ndarray, sr: int, regs: list[tuple[int, int]], seconds: float, grain: list[float],
                  xf_s: float, rng: np.random.Generator, avoid: np.ndarray | None = None, hop: int = 256,
                  level_pull: float = 0.5) -> np.ndarray:
    """Concatenative loop from grains of the given regions; each grain chosen to continue the one before it."""
    total = int(seconds * sr)
    xf = int(xf_s * sr)
    usable = [(a, b) for a, b in regs if b - a > int(grain[0] * sr) + xf]
    if not usable:  # not enough calm (or strong) material: fall back to the whole file
        usable = [(0, len(y))]
    weights = np.array([b - a for a, b in usable], dtype=float)
    weights /= weights.sum()
    med_level = float(np.median([db(np.sqrt(np.mean(y[a:b] ** 2))) for a, b in usable]))

    out = np.zeros(0, dtype=np.float64)
    prev_tail_db = prev_tail_c = None
    prev_src = -10 ** 9
    t = np.linspace(0, 1, xf, endpoint=False)
    fin, fout = np.sin(t * np.pi / 2), np.cos(t * np.pi / 2)  # equal power: the material is noise-like
    guard = 0
    limit = int(total * 1.1)  # 10 % spare for the loop point search
    while len(out) < limit + xf and guard < 10000:
        guard += 1
        best, best_cost = None, 1e18
        for _ in range(14):
            r = usable[rng.choice(len(usable), p=weights)]
            L = int(rng.uniform(*grain) * sr) + xf
            L = min(L, r[1] - r[0])
            if L <= 2 * xf:
                continue
            a = int(rng.integers(r[0], r[1] - L + 1))
            b = a + L
            hd, td, hc, tc = grain_features(y, a, b, sr)
            cost = 0.0
            # dropouts: 20 ms stretches 20 dB under the grain's own level read as the loop cutting out
            lv = db(np.sqrt(np.maximum(moving_mean(y[a:b] ** 2, int(0.02 * sr))[::int(0.005 * sr)], 1e-24)))
            cost += 4.0 * float((lv < np.median(lv) - 20.0).mean() * 100)
            if avoid is not None:  # a calm grain should not carry the recording's hardest cracks
                cost += 25.0 * float(avoid[a // hop:b // hop].mean())
            if prev_tail_db is not None:
                cost += abs(hd - prev_tail_db) + abs(hc - prev_tail_c) / 400.0
            cost += 6.0 if abs(a - prev_src) < L else 0.0  # don't replay what was just heard
            cost += rng.uniform(0, 1.5)  # keep it from always taking the same "best" grain
            if cost < best_cost:
                best, best_cost = (a, b, td, tc), cost
        if best is None:
            continue
        a, b, td, tc = best
        g = y[a:b].astype(np.float64)
        lvl = db(np.sqrt(np.mean(g ** 2)))
        g = g * undb((med_level - lvl) * level_pull)  # pull every grain part of the way to the median level
        if len(out) == 0:
            out = g.copy()
        else:
            out[-xf:] = out[-xf:] * fout + g[:xf] * fin
            out = np.concatenate([out, g[xf:]])
        prev_tail_db, prev_tail_c, prev_src = td + (med_level - lvl) * level_pull, tc, a
    if len(out) < limit + xf:
        sys.exit(f"granular loop: could not gather {seconds}s of grains (regions {usable})")
    total = best_loop_length(out, sr, total, limit, xf)
    loop = out[:total].copy()
    loop[:xf] = out[:xf] * fin + out[total:total + xf] * fout  # wrap: the end flows into the start
    return loop


def best_loop_length(out: np.ndarray, sr: int, lo: int, hi: int, xf: int) -> int:
    """Loop point search: the length L in [lo, hi] whose wrap is least noticeable. At the wrap the loop's end
    out[L-n:L] is followed by a crossfade from out[L:L+xf] to out[0:xf]; both pairs should match in level (a cut
    inside a lull that lands on a flap reads as a click of the loop, even though each side is clean)."""
    n = int(0.05 * sr)
    lvl = lambda a, b: db(np.sqrt(np.mean(out[a:b] ** 2) + 1e-24))
    head = lvl(0, xf)
    best, best_cost = lo, 1e18
    for L in range(lo, hi + 1, int(0.01 * sr)):
        cost = abs(lvl(L - n, L) - lvl(L, L + n)) + abs(lvl(L, L + xf) - head)
        if cost < best_cost:
            best, best_cost = L, cost
    return best


def resample(x: np.ndarray, factor: float) -> np.ndarray:
    """Varispeed by factor (>1 = higher and shorter), linear interpolation: plenty for short transients."""
    n = int(len(x) / factor)
    return np.interp(np.arange(n) * factor, np.arange(len(x)), x)


def layer_snaps(loop: np.ndarray, snaps: list[np.ndarray], rate: float, sr: int, rng: np.random.Generator,
                gain_db: tuple[float, float]) -> np.ndarray:
    """Scatters snaps through a loop (wrapping around its end, so the seam stays clean)."""
    out = loop.copy()
    peak_ref = np.sqrt(np.mean(loop ** 2)) * undb(14)
    t = 0.0
    dur = len(loop) / sr
    while snaps:
        t += rng.exponential(1.0 / max(rate, 1e-3))
        if t >= dur:
            break
        s = resample(snaps[int(rng.integers(len(snaps)))], float(rng.uniform(0.93, 1.07)))
        s = s / (np.abs(s).max() + 1e-12) * peak_ref * undb(rng.uniform(*gain_db))
        i = int(t * sr)
        idx = (np.arange(len(s)) + i) % len(out)
        np.add.at(out, idx, s)
    return out


def shape_snap(y: np.ndarray, a: int, b: int, sr: int) -> np.ndarray:
    s = y[a:b].astype(np.float64).copy()
    fi = int(0.003 * sr)
    s[:fi] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, fi))
    k = int(len(s) * 0.4)  # exponential release over the last 40 %
    s[-k:] *= np.exp(np.linspace(0, -6, k))
    return s


# ---------------------------------------------------------------------------------------------------------- 6 effects


def sox_loop_effects(loop: np.ndarray, sr: int, effects: list[str], work: str, name: str) -> np.ndarray:
    """Runs stateful SoX effects (compressor, reverb) on three copies of the loop and keeps the middle one: what
    the effects see at its start is the loop's own end, so the result loops as cleanly as the input."""
    if not effects:
        return loop
    a, b = os.path.join(work, f"{name}.fx_in.wav"), os.path.join(work, f"{name}.fx_out.wav")
    sf.write(a, np.tile(loop, 3).astype(np.float32), sr, subtype="FLOAT")
    run([SOX, a, b] + effects)
    out, _ = sf.read(b, dtype="float64")
    if out.ndim > 1:
        out = out.mean(axis=1)
    n = len(loop)
    return out[n:2 * n]


def sox_snap_effects(s: np.ndarray, sr: int, effects: list[str], work: str, name: str, tail: float) -> np.ndarray:
    if not effects:
        return s
    a, b = os.path.join(work, f"{name}.fx_in.wav"), os.path.join(work, f"{name}.fx_out.wav")
    sf.write(a, np.concatenate([s, np.zeros(int(tail * sr))]).astype(np.float32), sr, subtype="FLOAT")
    run([SOX, a, b] + effects)
    out, _ = sf.read(b, dtype="float64")
    if out.ndim > 1:
        out = out.mean(axis=1)
    # drop the silent end of the tail
    env = np.abs(out)
    last = np.where(env > env.max() * undb(-60))[0]
    out = out[:last[-1] + 1] if len(last) else out
    f = min(len(out), int(0.01 * sr))
    out[-f:] *= np.linspace(1, 0, f)
    return out


# ---------------------------------------------------------------------------------------------------------- 7 loudness


def lufs(path: str) -> float:
    """Integrated loudness via ffmpeg's EBU R128 meter (loudnorm analysis pass)."""
    out = run([FFMPEG, "-hide_banner", "-nostats", "-i", path, "-af", "loudnorm=print_format=json", "-f", "null", "-"])
    m = re.search(r'"input_i"\s*:\s*"(-?[\d.]+|-inf)"', out)
    return float(m.group(1)) if m and m.group(1) != "-inf" else -70.0


def soft_limit(x: np.ndarray, ceiling_db: float = -1.0) -> np.ndarray:
    """Memoryless soft knee above 75 % of the ceiling (no state = no seam)."""
    c = undb(ceiling_db)
    k = 0.75 * c
    y = x.copy()
    m = np.abs(y) > k
    y[m] = np.sign(y[m]) * (k + (c - k) * np.tanh((np.abs(y[m]) - k) / (c - k)))
    return y


def to_loudness(x: np.ndarray, sr: int, target: float, work: str, name: str) -> tuple[np.ndarray, float]:
    p = os.path.join(work, f"{name}.meter.wav")
    # a loop is measured over two passes: the meter's 400 ms gating blocks then see the wrap too
    sf.write(p, np.tile(x, 2).astype(np.float32), sr, subtype="FLOAT")
    cur = lufs(p)
    y = soft_limit(x * undb(target - cur))
    sf.write(p, np.tile(y, 2).astype(np.float32), sr, subtype="FLOAT")
    return y, lufs(p)


# ---------------------------------------------------------------------------------------------------------- 8 export


def export_ogg(x: np.ndarray, sr: int, out_sr: int, q: int, path: str, work: str):
    tmp = os.path.join(work, os.path.basename(path) + ".wav")
    sf.write(tmp, x.astype(np.float32), sr, subtype="FLOAT")
    run([FFMPEG, "-y", "-v", "error", "-i", tmp, "-ac", "1", "-ar", str(out_sr), "-c:a", "libvorbis", "-q:a", str(q), path])


SCRIPT_HEAD = """/*
 * Floppy Fabrics: fabric sounds. GENERATED by tools/sound/fabricsfx.py from tools/sound/materials.json; do not edit.
 * FabricSound.java plays these on its own emitters and sets every voice's volume and pitch each frame from the
 * simulated cloth (wind at the cloth, the cloth's own motion, gusts). master = Ambient: the game's ambient volume
 * slider scales them; the mod option "Fabric sound volume" scales them again.
 */
module Base
{
"""


def sound_block(name: str, files: list[str], loop: bool, dmin: int, dmax: int, reverb: float) -> str:
    clips = "".join(
        f"""        clip
        {{
            file = {f},
            distanceMin = {dmin},
            distanceMax = {dmax},
            reverbFactor = {reverb},
        }}
""" for f in files)
    return f"""    sound {name}
    {{
        category = World,
        master = Ambient,
        is3D = true,
        loop = {'true' if loop else 'false'},
{clips}    }}

"""


# ---------------------------------------------------------------------------------------------------------- 9 report

# Mirror of FabricSound.java's mapping (keep in sync): what the preview renders is what the game plays.
RUNTIME = {
    "windFull": 14.0, "windStart": 1.0,      # m/s at the cloth: smoothstep(start, full)
    "windWeight": 0.6,                       # drive = windWeight * wind + (1 - windWeight) * motion
    "xfadeLo": 0.30, "xfadeHi": 0.85,        # calm -> strong crossfade over the drive
    "gainCurve": 0.8,                        # overall level = drive ^ gainCurve
    "calmPitch": (0.94, 0.12), "strongPitch": (0.90, 0.18),  # pitch = a + b * drive
}


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def render_preview(mat: str, layers: dict, sr: int, seconds: float, rng: np.random.Generator,
                   snap_rate: float) -> np.ndarray:
    """A wind sweep (calm -> gale -> breeze) through the same mapping as the game, with a synthetic cloth motion
    that follows the gusts. Block-based (20 ms) like the game's per-frame updates."""
    R = RUNTIME
    n = int(seconds * sr)
    blk = int(0.02 * sr)
    tt = np.arange(0, n, blk) / sr
    base = np.interp(tt, [0, seconds * 0.15, seconds * 0.55, seconds * 0.75, seconds], [0.0, 3.0, 17.0, 17.0, 5.0])
    gust = 1 + 0.35 * np.sin(tt * 0.9) * np.sin(tt * 0.37 + 1) + 0.15 * rng.standard_normal(len(tt)).cumsum() / 40
    wind = np.maximum(0, base * gust)
    motion = smoothstep(R["windStart"], R["windFull"], wind) * (0.8 + 0.4 * np.abs(np.sin(tt * 2.1)))
    drive = np.clip(R["windWeight"] * smoothstep(R["windStart"], R["windFull"], wind) + (1 - R["windWeight"]) * np.clip(motion, 0, 1), 0, 1)

    out = np.zeros(n + sr)
    cp = sp = 0.0  # read positions in the loops (varispeed)
    calm, strong = layers["calm"], layers["strong"]
    cooldown = 0.0
    for k, t0 in enumerate(range(0, n, blk)):
        d = drive[k]
        g = d ** R["gainCurve"]
        x = smoothstep(R["xfadeLo"], R["xfadeHi"], d)
        vc, vs = g * math.cos(x * math.pi / 2), g * math.sin(x * math.pi / 2)
        pc = R["calmPitch"][0] + R["calmPitch"][1] * d
        ps = R["strongPitch"][0] + R["strongPitch"][1] * d
        idx_c = (cp + np.arange(blk) * pc) % len(calm)
        idx_s = (sp + np.arange(blk) * ps) % len(strong)
        out[t0:t0 + blk] += vc * np.interp(idx_c, np.arange(len(calm)), calm)[:len(out[t0:t0 + blk])]
        out[t0:t0 + blk] += vs * np.interp(idx_s, np.arange(len(strong)), strong)[:len(out[t0:t0 + blk])]
        cp, sp = (cp + blk * pc) % len(calm), (sp + blk * ps) % len(strong)
        cooldown -= blk / sr
        if layers["snaps"] and d > 0.4 and cooldown <= 0 and rng.random() < snap_rate * (d - 0.4) / 0.6 * blk / sr * 2:
            s = resample(layers["snaps"][int(rng.integers(len(layers["snaps"])))], rng.uniform(0.92, 1.08) * (0.95 + 0.1 * d))
            s = s * (0.5 + 0.5 * d) * rng.uniform(0.8, 1.0)
            out[t0:t0 + len(s)] += s[:len(out) - t0]
            cooldown = rng.uniform(0.18, 0.5)
    return soft_limit(out[:n], -1.0)


def spectrogram_sheet(items: list[tuple[str, np.ndarray]], sr: int, path: str):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import librosa.display
    fig, axes = plt.subplots(len(items), 1, figsize=(14, 1.9 * len(items)))
    axes = np.atleast_1d(axes)
    for ax, (title, x) in zip(axes, items):
        S = librosa.amplitude_to_db(np.abs(librosa.stft(x.astype(np.float32), n_fft=2048, hop_length=512)), ref=np.max)
        librosa.display.specshow(S, sr=sr, hop_length=512, x_axis="time", y_axis="log", ax=ax, vmin=-80, vmax=0, cmap="magma")
        ax.set_title(title, fontsize=9, loc="left")
        ax.set_xlabel("")
    plt.tight_layout()
    plt.savefig(path, dpi=70)
    plt.close(fig)


def seam_report(x: np.ndarray, sr: int) -> dict:
    """How audible the wrap is, measured against the loop itself: the 50 ms level step and the sample jump across
    the seam, each as a percentile of the same measure at every other point of the loop (50 = an ordinary moment,
    99 = the most abrupt spot in the loop). Fabric is bursty, so an absolute dB step means little on its own."""
    n = int(0.05 * sr)
    xx = np.concatenate([x[-n:], x, x[:n]])  # wrapped: xx[n] is x[0], so the seam is the boundary at index n
    e = db(np.sqrt(np.maximum(moving_mean(xx ** 2, n), 1e-24)))  # e[i] = level of xx[i:i+n]
    steps = np.abs(e[n:] - e[:-n])  # steps[i] = level of the n samples after boundary n+i vs the n before it
    seam_step = float(steps[0])  # e[0] = x[-n:], e[n] = x[:n]
    jumps = np.abs(np.diff(np.concatenate([x, x[:1]])))
    pct = lambda arr, v: round(float((arr < v).mean() * 100), 1)
    return {"levelStepDb": round(seam_step, 2), "levelStepPercentile": pct(steps, seam_step),
            "sampleJumpPercentile": pct(jumps, jumps[-1])}


# ---------------------------------------------------------------------------------------------------------- main


def build_material(name: str, cfg: dict, top: dict, args, cleaned: dict, rng: np.random.Generator, report: dict):
    sr = top["sampleRate"]
    work = os.path.join(args.work, name)
    os.makedirs(work, exist_ok=True)
    rec: dict = {"notes": []}

    # 1-3: source -> cleaned mono
    dst = os.path.join(work, "clean.wav")
    if "derive" in cfg:
        base = cleaned[cfg["derive"]]
        derive(base, dst, cfg, sr)
        rec["notes"].append(f"derived from {cfg['derive']}: sox {' '.join(cfg['effects'])}")
    else:
        src = os.path.join(args.src, cfg["source"])
        if not os.path.isfile(src):
            sys.exit(f"{name}: source not found: {src}")
        raw = os.path.join(work, "ingest.wav")
        ingest(src, raw, sr)
        a = analyse(raw, cfg)
        rec["notes"] += a.notes
        rec["analysis"] = {"highpass": a.highpass, "lowpass": a.lowpass, "trim": [round(a.start, 3), round(a.end, 3)],
                           "noisePrint": [round(a.noise_at, 3), round(a.noise_len, 3)]}
        clean(raw, dst, a, cfg, work, name)
    cleaned[name] = dst
    y, _ = sf.read(dst, dtype="float64")
    rec["cleanSeconds"] = round(len(y) / sr, 2)

    # 4: segment
    seg = segment(y, sr, cfg, top["snapCount"])
    calm_r, strong_r = regions(seg, seg.calm), regions(seg, seg.strong)
    rec["segments"] = {"calmSeconds": round(sum(b - a for a, b in calm_r) / sr, 2),
                       "strongSeconds": round(sum(b - a for a, b in strong_r) / sr, 2),
                       "snaps": [[round(a / sr, 3), round((b - a) / sr, 3)] for a, b, _ in seg.snaps]}
    log(f"  {name}: {rec['cleanSeconds']}s clean, calm {rec['segments']['calmSeconds']}s, strong "
        f"{rec['segments']['strongSeconds']}s, {len(seg.snaps)} snaps")

    # snaps: shape, effects, peak-normalise
    room = cfg.get("room")
    room_fx = ["reverb", str(room["reverberance"]), str(room["hfDamping"]), str(room["roomScale"]), "0", "0",
               str(room["wetGain"])] if room else []
    off = float(cfg.get("loudnessOffset", 0.0))
    snaps = []
    for i, (a, b, _) in enumerate(seg.snaps):
        s = shape_snap(y, a, b, sr)
        s = sox_snap_effects(s, sr, ["equalizer", "2800", "1.0q", "+1.5"] + room_fx, work, f"snap{i}", 0.35 if room else 0.0)
        s = soft_limit(s / (np.abs(s).max() + 1e-12) * undb(top["loudness"]["snapPeak"] + off), -0.5)
        snaps.append(s)

    # 5: loops
    xf, grain = float(cfg.get("crossfade", 0.09)), cfg.get("grain", [0.45, 1.2])
    calm = granular_loop(y, sr, calm_r, float(cfg.get("loopSeconds", top["loopSeconds"])), grain, xf, rng, avoid=seg.transient, hop=seg.hop)
    strong = granular_loop(y, sr, strong_r, float(cfg.get("loopSeconds", top["loopSeconds"])), grain, xf, rng, hop=seg.hop, level_pull=0.35)
    raw_snaps = [shape_snap(y, a, b, sr) for a, b, _ in seg.snaps]
    strong = layer_snaps(strong, raw_snaps, float(cfg.get("snapLayer", 0.6)) * float(cfg.get("snapRate", 1.0)), sr, rng, (-9.0, -4.0))

    # 6: effects (tiled so the seam survives)
    comp = ["compand", "0.005,0.12", "-60,-60,-30,-26,-12,-14,0,-8", "0", "-90", "0.005"]
    calm = sox_loop_effects(calm, sr, comp + room_fx, work, "calm")
    strong = sox_loop_effects(strong, sr, comp + room_fx, work, "strong")

    # 7: loudness
    calm, l_c = to_loudness(calm, sr, top["loudness"]["calm"] + off, work, "calm")
    strong, l_s = to_loudness(strong, sr, top["loudness"]["strong"] + off, work, "strong")
    rec["loudness"] = {"calm": round(l_c, 1), "strong": round(l_s, 1)}
    rec["seam"] = {"calm": seam_report(calm, sr), "strong": seam_report(strong, sr)}

    # 8: export
    out_dir = args.out
    files = {}
    for lname, x in [("calm", calm), ("strong", strong)] + [(f"snap_{i + 1}", s) for i, s in enumerate(snaps)]:
        fn = f"{name}_{lname}.ogg"
        export_ogg(x, sr, top["exportRate"], top["vorbisQuality"], os.path.join(out_dir, fn), work)
        files[lname] = fn
    rec["files"] = files
    report["materials"][name] = rec
    return {"calm": calm, "strong": strong, "snaps": snaps}


def main():
    global FFMPEG, SOX
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", required=True, help="folder with the raw recordings")
    ap.add_argument("--config", default=os.path.join(HERE, "materials.json"))
    ap.add_argument("--out", default=os.path.join(REPO, "mod", "42", "media", "sound", "floppyfabrics"))
    ap.add_argument("--script", default=os.path.join(REPO, "mod", "42", "media", "scripts", "FloppyFabrics_sounds.txt"))
    ap.add_argument("--work", default=os.path.join(REPO, "build", "sound"))
    ap.add_argument("--only", default="", help="comma-separated materials (derived ones pull their base in)")
    ap.add_argument("--no-preview", action="store_true")
    args = ap.parse_args()
    FFMPEG, SOX = find_tool("ffmpeg"), find_tool("sox")

    with open(args.config, encoding="utf-8") as fh:
        top = json.load(fh)
    mats = top["materials"]
    wanted = [m for m in mats if not args.only or m in args.only.split(",")]
    for m in list(wanted):  # a derived material needs its base built first
        b = mats[m].get("derive")
        if b and b not in wanted:
            wanted.insert(0, b)
    order = [m for m in mats if m in wanted and "derive" not in mats[m]] + [m for m in mats if m in wanted and "derive" in mats[m]]

    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.work, exist_ok=True)
    rng = np.random.default_rng(top.get("seed", 0))
    report = {"tools": {"ffmpeg": FFMPEG, "sox": SOX, "librosa": librosa.__version__}, "materials": {}}
    cleaned: dict = {}
    built = {}
    log(f"ffmpeg: {FFMPEG}\nsox:    {SOX}\nlibrosa {librosa.__version__}")
    for m in order:
        log(f"[{m}]")
        built[m] = build_material(m, mats[m], top, args, cleaned, rng, report)

    # sound script: all materials currently on disk (a partial --only run keeps the others' entries)
    script = SCRIPT_HEAD
    for m in mats:
        calm = os.path.join(args.out, f"{m}_calm.ogg")
        if not os.path.isfile(calm):
            continue
        rel = "media/sound/floppyfabrics/"
        snaps = sorted(glob.glob(os.path.join(args.out, f"{m}_snap_*.ogg")), key=lambda p: int(re.findall(r"(\d+)\.ogg$", p)[0]))
        reverb = 0.25 if mats[m].get("room") else 0.1
        script += sound_block(f"FloppyFabrics_{m}_calm", [rel + f"{m}_calm.ogg"], True, 2, 22, reverb)
        script += sound_block(f"FloppyFabrics_{m}_strong", [rel + f"{m}_strong.ogg"], True, 2, 26, reverb)
        if snaps:
            script += sound_block(f"FloppyFabrics_{m}_snap", [rel + os.path.basename(s) for s in snaps], False, 2, 26, reverb)
    script += "}\n"
    os.makedirs(os.path.dirname(args.script), exist_ok=True)
    with open(args.script, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(script)

    # report: spectrograms + previews
    sr = top["sampleRate"]
    sheet = []
    for m, L in built.items():
        sheet += [(f"{m} calm loop (x2, seam in the middle)", np.tile(L["calm"], 2)),
                  (f"{m} strong loop (x2)", np.tile(L["strong"], 2)),
                  (f"{m} snaps", np.concatenate([np.concatenate([s, np.zeros(int(0.15 * sr))]) for s in L["snaps"]]) if L["snaps"] else np.zeros(sr))]
    if sheet:
        spectrogram_sheet(sheet, sr, os.path.join(args.work, "layers.png"))
    if not args.no_preview:
        for m, L in built.items():
            p = render_preview(m, L, sr, 40.0, np.random.default_rng(7), float(mats[m].get("snapRate", 1.0)))
            export_ogg(p, sr, top["exportRate"], top["vorbisQuality"], os.path.join(args.work, f"preview_{m}.ogg"), args.work)
        log(f"previews: {os.path.join(args.work, 'preview_*.ogg')} (40 s wind sweep: calm -> gale -> breeze)")
    with open(os.path.join(args.work, "report.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    for m, r in report["materials"].items():
        log(f"  {m}: loudness {r['loudness']}, seam {r['seam']}")
        for n in r["notes"]:
            log(f"      {n}")
    log(f"wrote {args.out}, {args.script}, report in {args.work}")


if __name__ == "__main__":
    main()
