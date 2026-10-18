---
name: phoneapp-survey
description: Read the reference map app's lane arrows on the owner's phone over adb (screenshot, zoom, pan helpers in tools/phoneapp_survey) for the cross-check of big junctions. Use when continuing the phone-app junction survey.
---

# Phone-app survey over adb

Rules first: `docs/DATA_SOURCES.md` (AMAP section) and the "AMAP survey" prompt in `CLAUDE.md`. Never sign in to AMAP,
never save screenshots outside `tools/phoneapp_survey/work/` (git-ignored), never write the owner's location; only text
readings go into `tools/streetview/work/amap_check.json`.

## Tools (Git Bash, from the repo root)
- `tools/phoneapp_survey/shot.sh a` → `tools/phoneapp_survey/work/a.png` (40 % size; then Read it). Shot px × 2.5 = phone px.
- `tools/phoneapp_survey/zoom_in.sh N X Y` - N double-taps at phone px (1 level each; tap on the approach to keep it in view).
- `tools/phoneapp_survey/zoom_out.sh N` - double-tap + drag down, ≈ 2 levels each.
- `tools/phoneapp_survey/pan.sh DX DY` - centre a point seen at (sx, sy) on the shot: `pan.sh $((720-sx*5/2)) $((1500-sy*5/2))`.
- Check AMAP is in front: `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" shell dumpsys window | grep mCurrentFocus`
  (package `com.autonavi.minimap`).

## Way of working
1. Plan: `tools/streetview/work/amap_plan.json` (junction `n`, `center`, approaches with OSM way, heading, `osm` arrows);
   print the open ones with Python. OSM lane counts per way: `tools/streetview/work/osm_lanes.json`.
2. Navigate by panning at the 200 m scale (zoom out 4 steps from max), using street names / anchors from the plan.
   Search only if needed; it may ask for a login - then pan instead. Leave a search result with the app's upper-left back
   button, not the system back.
3. Zoom to 5 m or closer at the stop line of each approach; arrows often appear only at 2 m / 1 m, a street label can
   hide one. "No arrows" only after max zoom along the whole approach.
4. Note lanes per direction (`amap_lanes`: "this direction N | opposite M"; AMAP's "4 lanes" can be 3 + 1), the arrows
   left→right in `turn:lanes` notation, and compare with `osm` (`left` ≈ `sharp_left`; slip lanes count separately).
5. Append to `amap_check.json`: `junction, approach, amap, amap_lanes, osm_lanes, verdict (match|equivalent|differs|
   no_arrows|not_found), note, date`. Differences go to DOP20 / Mapillary first; AMAP into `lane_overrides.json` only
   as last resort (`"source": "AMAP screenshot <date> (unconfirmed)"`).
6. Update `docs/ROADMAP.md` (v0.4.4 part 7, AMAP rechecks, "Still open" list).
