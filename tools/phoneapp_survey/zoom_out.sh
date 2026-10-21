#!/bin/bash
# zoom_out.sh [n] : n x (double-tap + drag down), roughly 2 zoom levels each (1 m -> 200 m scale bar in 4 steps).
# (double-tap + drag UP zooms in strongly instead.)
. "$(dirname "$0")/_env.sh"
for i in $(seq ${1:-1}); do
  "$ADB" shell "input tap 720 800; input motionevent DOWN 720 800; input motionevent MOVE 720 1300; input motionevent MOVE 720 1900; input motionevent MOVE 720 2400; input motionevent UP 720 2400"
  sleep 0.6
done
