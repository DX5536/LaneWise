#!/bin/bash
# Shared settings for the phone-app survey helpers. Screenshots stay in the git-ignored work/ folder (never commit them).
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && (pwd -W 2>/dev/null || pwd))"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PY="${PY:-$D/../aerial_lanes/.venv/Scripts/python.exe}"   # any Python with Pillow
WORK="${SURVEY_WORK:-$D/work}"
mkdir -p "$WORK"
