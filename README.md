# LaneWise

**3D lane-level navigation for Germany (focus: Region Hannover) — with lane assist that avoids
unnecessary lane changes.**

<p align="center"><img src="docs/icon.svg" width="120" alt="LaneWise icon"></p>

LaneWise is an Android driving app inspired by the 3D lane guidance of Petal Maps and AMAP, built
on fresh open German data instead of their outdated lane data.

## Features
- **Advanced lane assist** — plans your lane across the next junctions (up to 4 km) so you change
  lanes as rarely as possible. Toggle: *Einstellungen → Spurassistent → Spurwechsel so weit wie möglich vermeiden*.
  Optional keep-right rule for the Autobahn (§ 7 StVO). Tap a lane in the lane bar to tell the app which lane you're in.
- **3D lane view** — tilted 3D map with 3D buildings, the road drawn lane by lane, and a lime ribbon
  in your recommended lane that bends where you should change; plus a perspective junction close-up.
- **Speed limits** — German round sign (incl. de-restricted Autobahn), **light-red screen border**
  when too fast (pulsing) or too slow; both offsets adjustable in Settings.
- **Official traffic data** — Die Autobahn GmbH (roadworks/closures), TomTom, HERE, Mobilithek (VMZ Niedersachsen).
- **Offline / satellite-only** — download Region Hannover once; afterwards map, routing, lanes and
  speed limits work with GNSS only (GPS/Galileo/GLONASS/BeiDou), no internet.
- Light/dark mode, German/English voice, demo drive for testing at your desk.

## Install
Every push builds APKs in GitHub Actions → *Actions → Build → Artifacts → LaneWise-apks*.
Tag `v0.1.0` to publish a release with the APKs attached. Install the APK on Android 8.0+
(allow "install unknown apps").

## Build locally
Requirements: JDK 17–21 (**not** Java 25 — Android Studio's bundled JBR is too new for Gradle 8.14),
Android SDK with platform 36 (`local.properties` → `sdk.dir`, not committed).

Windows, one command (tests + debug APK → `app/build/outputs/apk/debug/app-debug.apk`):
```powershell
.\build-local.ps1
```
Add `-Release` for the minified release APK, `-Install` to install on a USB-connected phone.
The script uses its own Gradle home (`%LOCALAPPDATA%\LaneWise\gradle-home`).

Other platforms:
```bash
./gradlew testDebugUnitTest assembleDebug
```
Optional API keys in `local.properties` (never committed):
```
TOMTOM_API_KEY=...
HERE_API_KEY=...
```
Map styles are generated: `python tools/gen_map_styles.py`.

## Docs
| File | What |
| --- | --- |
| [docs/LANE_ALGORITHM.md](docs/LANE_ALGORITHM.md) | how lane choice works |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | code layout, data flow, offline mode, CI |
| [docs/DATA_SOURCES.md](docs/DATA_SOURCES.md) | APIs, keys, attribution |
| [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md) | what was learned from Petal Maps & AMAP |
| [docs/ROADMAP.md](docs/ROADMAP.md) | status and next steps |
| [CLAUDE.md](CLAUDE.md) | hand-off notes for AI-assisted sessions |

## Attribution
© OpenStreetMap contributors (ODbL) · tiles OpenFreeMap · routing FOSSGIS OSRM · traffic
Die Autobahn GmbH des Bundes, TomTom, HERE, Mobilithek. LaneWise contains no code, images or
data from Petal Maps or AMAP.
