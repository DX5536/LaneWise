# Lanes from aerial imagery (offline, on the PC)

Recognises road markings in the official 20 cm aerial photos of Niedersachsen (LGLN **DOP20**, CC BY 4.0)
with a segmentation network, measures the number of lanes of each OSM way, and writes
`app/src/main/assets/aerial_lanes.json`. The app uses it **only where OSM has no lane data or disagrees**
(policy in `app/src/main/java/de/lanewise/osm/AerialLanes.kt`). This is run once per imagery update, not on
the phone.

```
01_osm_roads ─► 02_fetch_dop ─► 03_pseudo_labels ─► 04_train ─► 05_infer ─► 06_extract_lanes ─► 07_build_asset
                                     ▲  (bootstrap)        ▲                                            │
                                     └── labels_manual/ ───┘◄──────── 08_spot_check + spot_checks.csv ◄─┘
```

## Setup (once)

```bash
cd tools/aerial_lanes
py -3.11 -m venv .venv
.venv/Scripts/python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
.venv/Scripts/python -m pip install -r requirements.txt
```

Data (imagery, masks, weights) goes to `tools/aerial_lanes/work/` (git-ignored; ~1.5 GB for Hannover).
Set `LANEWISE_AERIAL_DATA` to put it elsewhere. Areas are defined in `common.py` (`test` = Celler Straße /
Hbf north, ~1.6 km²; `hannover` = city).

## Run

```bash
P=.venv/Scripts/python
$P 01_osm_roads.py --area hannover          # OSM ways via Overpass
$P 02_fetch_dop.py --area hannover          # DOP20 tiles along those roads (resumable, ~3 workers)
$P 03_pseudo_labels.py --area hannover --limit 400   # bootstrap labels (classical detector)
$P 04_train.py --epochs 30                  # U-Net / ResNet-34 on the GPU (RTX 3070 Ti: ~1 min/epoch)
$P 05_infer.py --area hannover              # marking probability per tile
$P 06_extract_lanes.py --area hannover      # lanes per OSM way + agreement with OSM
$P 08_spot_check.py --area hannover --kind disagree   # review page: work/spot/index.html
$P 07_build_asset.py --area hannover        # -> app/src/main/assets/aerial_lanes.json
```

`05_infer.py --classical` writes the bootstrap detector's output instead of the network's; useful as a
baseline and to test steps 6–8 without a GPU.

## How lanes are counted (06)

Profiles perpendicular to the OSM centre line every 2 m (12 m skipped at junctions) → painted lines
crossing each profile → lines recurring at the same lateral offset in 40 m windows become *tracks*;
their duty cycle tells solid (≥ 65 %) from dashed lane dividers. Tracks 2.4–4.6 m apart form a chain;
lanes = gaps in the chain + one lane beyond each outermost **dashed** track (a dashed line always has
a lane on both sides) unless OSM tags a bike lane on that side. The way gets the most frequent window
value; confidence = share of agreeing windows × coverage. Counts mean the same as OSM `lanes`
(whole carriageway).

## Spot checks and making the model better

1. `08_spot_check.py` shows straightened photo strips with the detected paint (lime) and OSM line.
   **Policy for now:** a value that *contradicts* OSM is only shipped after a human marked it `ok` here
   (`07_build_asset.py --trust-disagree` lifts this once the model is validated on hand labels).
   Session 1 found two confident-but-wrong overrides this way (tree shadows; a way under an elevated road).
2. Write verdicts to `spot_checks.csv` (committed): `way_id,verdict,note`, verdict `ok`, `wrong` or the
   correct lane count. `07_build_asset.py` applies them (ok → 100 %, wrong → dropped).
3. Better labels: copy a tile's `work/labels_auto/<tile>.png` to `work/labels_manual/`, fix it in any
   paint program (255 = marking, 0 = no marking, 127 = ignore) and retrain. Manual tiles are sampled
   4× and used for validation, so the reported IoU then measures agreement with a human.

## Known limits

- The network starts from *pseudo-labels*, so at first it can only be as good as the classical
  detector plus some denoising. Real gains need some hand-labelled tiles (20–50 is a good start).
- Shadows of buildings and trees (DOP20 is flown in early spring with low sun) and parked cars hide
  paint; such windows count as "no result" and lower the confidence instead of guessing.
- Only lane *counts* so far. Turn arrows (→ `turn:lanes`) and solid-line positions (→ `change:lanes`)
  are the next steps; the mask already contains the arrows.
- Imagery date: DOP20 is updated every ~3 years per area; re-run after an update.

Licence: DOP20 © LGLN 2026, CC BY 4.0 — the attribution is shown in the app (map attribution, Settings).
OSM data © OpenStreetMap contributors, ODbL.
