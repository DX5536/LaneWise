#!/bin/bash
# pan.sh dx dy : drag the map content by (dx,dy) phone px. dy > 0 moves the content down = the view goes north.
# To bring a point seen at (sx,sy) on a 40 % shot to the centre: dx = 720 - sx*2.5, dy = 1500 - sy*2.5.
. "$(dirname "$0")/_env.sh"
DX=${1:-0}; DY=${2:-0}
[ "$DX" -eq 0 ] && [ "$DY" -eq 0 ] && exit 0   # a zero swipe is a long-press (drops a pin)
X0=$((720 - DX/2)); Y0=$((1500 - DY/2))
"$ADB" shell "input swipe $X0 $Y0 $((X0+DX)) $((Y0+DY)) 800"
sleep 0.5
