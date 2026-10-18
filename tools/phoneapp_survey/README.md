# Phone-app survey over adb (helpers)

Small Git-Bash helpers to read the reference map app's lane arrows on the owner's phone (USB, adb) for the cross-check of big
junctions (`docs/DATA_SOURCES.md`, AMAP rules; `CLAUDE.md`, survey prompt). Screenshots land in `work/` (git-ignored)
and are **never committed or uploaded** - only text readings go into `tools/streetview/work/amap_check.json`.

| Script | What it does |
|---|---|
| `shot.sh [name]` | Screenshot → `work/<name>.png`, 40 % size (multiply coordinates by 2.5 for phone px) |
| `zoom_in.sh [n] [x] [y]` | n double-taps at phone px (one zoom level each) |
| `zoom_out.sh [n]` | n × double-tap + drag down (≈ 2 levels each) |
| `pan.sh dx dy` | drag the map by phone px (`dy > 0` = look further north) |

Needs adb (`%LOCALAPPDATA%\Android\Sdk\platform-tools`) and a Python with Pillow (default: `tools/aerial_lanes/.venv`;
override with `PY=`). Phone screen 1440 × 3120. Arrows usually appear only at the 5 m scale bar or closer (2 m / 1 m).
