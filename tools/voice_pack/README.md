# OmniVoice voice packs

Natural-sounding voice guidance in **German, English (US / UK) and Vietnamese**, rendered once on the PC
with [OmniVoice](https://github.com/k2-fsa/OmniVoice) (k2-fsa, Apache-2.0) and played back on the phone as
clips — instant, offline, no model on the phone. OmniVoice itself has no Android runtime (0.6 B parameters,
32 steps per sentence), so this is the practical way to use it for navigation.

## How it fits together

- The app speaks every announcement as **segments**: `In 300 Metern` + `rechts abbiegen auf` + `L 190` +
  `Celler Straße` (`Phrases.spokenParts`). `VoiceGuide` chains the clips of a pack gaplessly.
- `inventory/<lang>.txt` lists every fixed segment (~300 per language). It is written by the app's unit test
  `VoiceInventoryTest`, so the PC can never render stale phrases. After changing a phrase in `Phrases.kt`:
  `gradlew testDebugUnitTest -PupdateVoiceInventory`, then re-render.
- Street names and road refs of the Region Hannover (~11 k) come from OSM and are rendered in the same voice.
- If any segment of a sentence is missing (street outside the region, a motorway destination), the **whole
  sentence** is spoken by Android TTS — never two voices in one sentence.

## Setup (once)

```bash
cd tools/voice_pack
py -3.11 -m venv .venv
.venv/Scripts/python -m pip install torch==2.8.0+cu128 torchaudio==2.8.0+cu128 --extra-index-url https://download.pytorch.org/whl/cu128
.venv/Scripts/python -m pip install omnivoice soundfile requests
```

## Make a pack

```bash
P=.venv/Scripts/python
$P 01_street_names.py                                  # OSM names/refs -> work/streets.txt
$P 02_candidates.py --pack de-omnivoice --n 4          # listen to work/candidates/de-omnivoice_*.wav
$P 02_candidates.py --pack de-omnivoice --pick 2       # keep the one you like -> refs/ (commit it)
$P 03_render.py --pack de-omnivoice --limit 40         # quick check of a few clips
$P 03_render.py --pack de-omnivoice                    # everything (~11.5 k clips, resumable)
$P 04_push.py --pack de-omnivoice                      # test on the phone over USB
```

Packs: `de-omnivoice`, `en-us-omnivoice`, `en-gb-omnivoice`, `vi-omnivoice`. English voices are designed with
OmniVoice's `american accent` / `british accent`; German and Vietnamese use OmniVoice's automatic voice
(voice design is trained on English/Chinese only) and the picked sample is cloned for every clip.

## Publish

Upload `work/packs/<id>.zip` as assets of a GitHub release tagged **`voices-v1`** in DX5536/LaneWise. The app
downloads from `https://github.com/DX5536/LaneWise/releases/download/voices-v1/<id>.zip`
(Settings → Sprachansagen → Herunterladen) and selects the pack automatically.

## Format

`pack.json` (id, language, name), `index.tsv` (normalised segment → clip file), Ogg Opus clips (~25 kbps,
24 kHz mono; Android 10+ — older phones keep the Android voice), ~45 MB per pack,
leading/trailing silence trimmed, peak-normalised; a longer pause after segments ending in a comma
(Vietnamese distance phrases). Normalisation (lower case, single spaces, no trailing `.`/`!`) is the same in
`common.normalize` and `VoiceInventory.normalize`.
