"""The app's sound effects and music, generated with ElevenLabs from tools/audio.json.

  ~/.local/venvs/kokoro/bin/python tools/audio.py            # generate what is missing, process everything
  ~/.local/venvs/kokoro/bin/python tools/audio.py --only dawn  # just one entry (regenerates it if --fresh)

audio.json:
  {"sfx":   {"<name>": {"prompt": "...", "seconds": 1.2, "influence": 0.6, "gain": 0}},
   "music": {"<name>": {"prompt": "...", "seconds": 75}}}

Every generation is cached in tools/audio-cache/ by prompt and settings (that folder is not in git), so a rerun
only pays for entries whose prompt changed. Effects are trimmed of silence and levelled; music is turned into a
seamless loop (the last seconds are crossfaded into the start) and levelled lower than the effects. Results go to
app/src/main/res/raw/sfx_<name>.ogg and music_<name>.ogg.

API key: $ELEVENLABS_API_KEY or ~/.config/elevenlabs/api_key (never stored in the repo).
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools/audio-cache"
RAW = ROOT / "app/src/main/res/raw"
API = "https://api.elevenlabs.io/v1"
SFX_LUFS, MUSIC_LUFS = -16.0, -20.0
CROSSFADE = 3.0


def key() -> str:
    k = os.environ.get("ELEVENLABS_API_KEY")
    f = Path.home() / ".config/elevenlabs/api_key"
    if not k and f.exists():
        k = f.read_text().strip()
    if not k:
        sys.exit("No ElevenLabs key: set ELEVENLABS_API_KEY or write it to ~/.config/elevenlabs/api_key")
    return k


def post(path: str, body: dict) -> bytes:
    req = urllib.request.Request(API + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"xi-api-key": key(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"ElevenLabs {e.code}: {e.read().decode(errors='replace')[:400]}")


def generate(kind: str, name: str, spec: dict, fresh: bool) -> Path:
    if kind == "sfx":
        body = {"text": spec["prompt"], "duration_seconds": spec.get("seconds"), "prompt_influence": spec.get("influence", 0.6),
                "model_id": "eleven_text_to_sound_v2"}
        path = "/sound-generation?output_format=mp3_44100_128"
    else:
        body = {"prompt": spec["prompt"], "music_length_ms": int(spec["seconds"] * 1000), "model_id": "music_v1", "force_instrumental": True}
        path = "/music?output_format=mp3_44100_128"
    tag = hashlib.sha1(json.dumps(body, sort_keys=True).encode()).hexdigest()[:10]
    out = CACHE / f"{kind}_{name}_{tag}.mp3"
    if out.exists() and not fresh:
        return out
    print(f"  generating {kind} {name} …", flush=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    out.write_bytes(post(path, body))
    return out


def decode(mp3: Path) -> tuple[np.ndarray, int]:
    with tempfile.NamedTemporaryFile(suffix=".wav") as t:
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(mp3), "-ar", "44100", t.name], check=True)
        a, sr = sf.read(t.name, dtype="float32", always_2d=True)
    return a, sr


def loudness(a: np.ndarray, sr: int) -> float:
    with tempfile.NamedTemporaryFile(suffix=".wav") as t:
        sf.write(t.name, a, sr)
        r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", t.name, "-af", "ebur128=peak=true", "-f", "null", "-"],
                           capture_output=True, text=True)
    for line in reversed(r.stderr.splitlines()):
        if line.strip().startswith("I:"):
            return float(line.split()[1])
    return -70.0


def level(a: np.ndarray, sr: int, target: float, gain: float = 0.0) -> np.ndarray:
    """To the target loudness (short sounds by their peak-ish RMS when too short for EBU R128), never clipping."""
    lufs = loudness(a, sr) if len(a) > sr * 0.5 else -70.0
    if lufs <= -69:
        rms = float(np.sqrt(np.mean(a ** 2)) + 1e-9)
        lufs = 20 * np.log10(rms) - 0.7
    a = a * 10 ** ((target - lufs + gain) / 20)
    peak = float(np.max(np.abs(a)) + 1e-9)
    return a * min(1.0, 0.89 / peak)


def trim(a: np.ndarray, sr: int, floor_db: float = -45) -> np.ndarray:
    """Cut silence at both ends, with short fades so nothing clicks."""
    env = np.max(np.abs(a), axis=1)
    loud = np.where(env > 10 ** (floor_db / 20))[0]
    if len(loud):
        a = a[max(0, loud[0] - int(0.005 * sr)): loud[-1] + int(0.05 * sr)]
    n_in, n_out = min(len(a) // 4, int(0.004 * sr)), min(len(a) // 3, int(0.06 * sr))
    a = a.copy()
    a[:n_in] *= np.linspace(0, 1, n_in)[:, None]
    a[len(a) - n_out:] *= np.linspace(1, 0, n_out)[:, None]
    return a


def steady(a: np.ndarray, sr: int, drop_db: float = 6.0) -> np.ndarray:
    """Without a fade-in at the start or a fade-out ending: keep from the first to the last quarter second that is
    within [drop_db] of the piece's median level."""
    w = sr // 4
    rms = np.array([20 * np.log10(np.sqrt(np.mean(a[i:i + w] ** 2)) + 1e-9) for i in range(0, len(a) - w, w)])
    ok = np.where(rms >= np.median(rms) - drop_db)[0]
    return a[ok[0] * w:(ok[-1] + 1) * w] if len(ok) else a


def make_loop(a: np.ndarray, sr: int, fade: float = CROSSFADE) -> np.ndarray:
    """A loop with no seam: the tail is crossfaded (equal power) into the head, and the head is cut off."""
    a = steady(a, sr)
    n = int(fade * sr)
    head, body, tail = a[:n], a[n:len(a) - n], a[len(a) - n:]
    t = np.linspace(0, np.pi / 2, n)[:, None]
    return np.concatenate([body, tail * np.cos(t) + head * np.sin(t)])


def encode(a: np.ndarray, sr: int, out: Path, mono: bool, quality: int):
    with tempfile.NamedTemporaryFile(suffix=".wav") as t:
        sf.write(t.name, a, sr)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", t.name, "-ac", "1" if mono else "2", "-c:a", "libvorbis", "-q:a", str(quality), str(out)], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", help="names to process")
    ap.add_argument("--fresh", action="store_true", help="generate again even if cached (paid)")
    args = ap.parse_args()
    spec = json.loads((ROOT / "tools/audio.json").read_text())
    RAW.mkdir(parents=True, exist_ok=True)
    for kind in ("sfx", "music"):
        for name, s in spec.get(kind, {}).items():
            if args.only and name not in args.only:
                continue
            mp3 = generate(kind, name, s, args.fresh)
            a, sr = decode(mp3)
            if kind == "sfx":
                a = level(trim(a, sr), sr, SFX_LUFS, s.get("gain", 0.0))
                out = RAW / f"sfx_{name}.ogg"
                encode(a, sr, out, mono=True, quality=4)
            else:
                a = level(make_loop(trim(a, sr, -60), sr), sr, MUSIC_LUFS, s.get("gain", 0.0))
                out = RAW / f"music_{name}.ogg"
                encode(a, sr, out, mono=False, quality=2)
            print(f"{out.relative_to(ROOT)}  {len(a) / sr:5.1f} s  {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
