"""Step 2: generate a few candidate voices per pack so the owner can pick one by ear.

    python 02_candidates.py --pack de-omnivoice [--n 4]
    python 02_candidates.py --pack de-omnivoice --pick 2      # keep candidate 2 as the pack's voice
Output: work/candidates/<pack>_<k>.wav; --pick copies it to refs/<pack>.wav + refs/<pack>.txt (committed).

English packs use OmniVoice voice design (gender/age/pitch + american/british accent). Voice design is trained on
English and Chinese only, so German and Vietnamese candidates use the automatic voice; the picked sample is then
used as the cloning reference for every clip, which keeps one consistent voice across the whole pack.
"""
import argparse
import shutil

import soundfile as sf

from common import PACKS, REFS, SAMPLE_RATE, WORK

DESIGNS = ["female, young adult, moderate pitch", "male, middle-aged, low pitch",
           "female, middle-aged, low pitch", "male, young adult, moderate pitch"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True, choices=sorted(PACKS))
    ap.add_argument("--n", type=int, default=4)
    ap.add_argument("--pick", type=int)
    a = ap.parse_args()
    cfg = PACKS[a.pack]
    cand = WORK / "candidates"
    cand.mkdir(parents=True, exist_ok=True)

    if a.pick is not None:
        src = cand / f"{a.pack}_{a.pick}.wav"
        REFS.mkdir(exist_ok=True)
        shutil.copy(src, REFS / f"{a.pack}.wav")
        (REFS / f"{a.pack}.txt").write_text(cfg["sample"] + "\n", encoding="utf-8")
        print(f"{src.name} is now the voice of {a.pack} -> {REFS}")
        return

    import torch
    from omnivoice import OmniVoice
    model = OmniVoice.from_pretrained("k2-fsa/OmniVoice", device_map="cuda:0", dtype=torch.float16)
    for k in range(1, a.n + 1):
        kwargs = {}
        if "accent" in cfg:
            kwargs["instruct"] = f"{DESIGNS[(k - 1) % len(DESIGNS)]}, {cfg['accent']}"
        audio = model.generate(text=cfg["sample"], language=cfg["lang"], **kwargs)
        out = cand / f"{a.pack}_{k}.wav"
        sf.write(out, audio[0], SAMPLE_RATE)
        print(f"{out}  {kwargs.get('instruct', 'automatic voice')}")
    print(f"Listen, then: python 02_candidates.py --pack {a.pack} --pick <k>")


if __name__ == "__main__":
    main()
