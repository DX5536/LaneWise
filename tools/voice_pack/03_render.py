"""Step 3: render every segment of a pack with OmniVoice (cloning the picked reference voice) and zip it.

    python 03_render.py --pack de-omnivoice [--limit 50] [--zip-only]
Output: work/packs/<pack>/ (pack.json, index.tsv, *.ogg) and work/packs/<pack>.zip (upload to the GitHub release
`voices-v1`, or push to the phone with 04_push.py). Resumable: clips that exist are skipped.
"""
import argparse
import json
import time
import zipfile

import numpy as np
import soundfile as sf

from common import PACKS, REFS, SAMPLE_RATE, WORK, clip_name, ends_with_pause, normalize, segments


def trim(audio: np.ndarray, pause_after: bool) -> np.ndarray:
    """Cut leading/trailing silence, then pad 40 ms before and 90 ms (or 220 ms after a comma) after."""
    a = np.asarray(audio, dtype=np.float32).squeeze()
    if a.size == 0:
        return a
    frame = 240                                         # 10 ms
    n = len(a) // frame
    if n > 0:
        energy = np.sqrt((a[: n * frame].reshape(n, frame) ** 2).mean(axis=1))
        voiced = np.nonzero(energy > max(0.008, energy.max() * 0.03))[0]
        if voiced.size:
            a = a[max(0, voiced[0] - 2) * frame: min(n, voiced[-1] + 4) * frame]
    peak = np.abs(a).max()
    if peak > 0:
        a = a / peak * 0.89                             # consistent loudness across clips
    pre = np.zeros(int(0.04 * SAMPLE_RATE), np.float32)
    post = np.zeros(int((0.22 if pause_after else 0.09) * SAMPLE_RATE), np.float32)
    return np.concatenate([pre, a, post])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True, choices=sorted(PACKS))
    ap.add_argument("--limit", type=int, default=0, help="only the first N segments (to try things out)")
    ap.add_argument("--zip-only", action="store_true")
    ap.add_argument("--batch", type=int, default=8)
    a = ap.parse_args()
    cfg = PACKS[a.pack]
    ref_wav, ref_txt = REFS / f"{a.pack}.wav", REFS / f"{a.pack}.txt"
    if not ref_wav.exists():
        raise SystemExit(f"no voice picked yet - run 02_candidates.py --pack {a.pack}")
    out = WORK / "packs" / a.pack
    out.mkdir(parents=True, exist_ok=True)
    segs = segments(a.pack)
    if a.limit:
        segs = segs[: a.limit]

    if not a.zip_only:
        import torch
        from omnivoice import OmniVoice
        model = OmniVoice.from_pretrained("k2-fsa/OmniVoice", device_map="cuda:0", dtype=torch.float16)
        prompt = model.create_voice_clone_prompt(str(ref_wav), ref_txt.read_text(encoding="utf-8").strip())
        todo = [(s, l) for s, l in segs if not (out / clip_name(s)).exists()]
        print(f"{len(segs)} segments, {len(todo)} to render")
        t0 = time.time()
        done = 0
        for b in range(0, len(todo), a.batch):
            chunk = todo[b: b + a.batch]
            try:
                audios = model.generate(text=[s for s, _ in chunk], language=[l for _, l in chunk],
                                        voice_clone_prompt=[prompt] * len(chunk))
                for (text, _), audio in zip(chunk, audios):
                    # Ogg Opus ~25 kbps: clear speech, ~4 KB per clip (Android plays it from Android 10).
                    sf.write(out / clip_name(text), trim(audio, ends_with_pause(text)), SAMPLE_RATE,
                             format="OGG", subtype="OPUS", compression_level=0.95)
            except Exception as ex:                     # keep going; the app falls back to Android TTS
                print(f"  failed batch at {chunk[0][0]!r}: {ex}")
            done += len(chunk)
            if (b // a.batch) % 20 == 0 or done == len(todo):
                el = time.time() - t0
                print(f"{done}/{len(todo)}  {el / done:.2f} s/clip, ~{el / done * (len(todo) - done) / 60:.0f} min left")

    index = [(normalize(s), clip_name(s)) for s, _ in segs if (out / clip_name(s)).exists()]
    (out / "index.tsv").write_text("".join(f"{k}\t{f}\n" for k, f in index), encoding="utf-8")
    (out / "pack.json").write_text(json.dumps({"id": a.pack, "language": cfg["lang"], "name": cfg["name"],
                                               "engine": "OmniVoice (k2-fsa, Apache-2.0)", "clips": len(index)},
                                              ensure_ascii=False, indent=1), encoding="utf-8")
    z = WORK / "packs" / f"{a.pack}.zip"
    with zipfile.ZipFile(z, "w", zipfile.ZIP_STORED) as zf:      # Ogg is compressed already
        for p in sorted(out.iterdir()):
            zf.write(p, p.name)
    print(f"{len(index)}/{len(segs)} clips -> {z} ({z.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
