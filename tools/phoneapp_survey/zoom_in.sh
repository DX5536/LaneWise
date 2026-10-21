#!/bin/bash
# zoom_in.sh [n] [x] [y] : n double-taps at phone px (x,y) (default centre); one double-tap = one zoom level.
. "$(dirname "$0")/_env.sh"
X=${2:-720}; Y=${3:-1500}
for i in $(seq ${1:-1}); do "$ADB" shell "input tap $X $Y; input tap $X $Y"; sleep 0.8; done
