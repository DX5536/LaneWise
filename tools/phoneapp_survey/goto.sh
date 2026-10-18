#!/bin/bash
# goto.sh LAT0 LON0 LAT1 LON1 M_PER_SHOTPX : pan from the screen centre (LAT0,LON0) to (LAT1,LON1).
# M_PER_SHOTPX = scale bar metres / scale bar length in px on the 40 % shot (e.g. 500 m / 105 px = 4.76).
# Pans in swipes of at most 1200 phone px; the map must be north-up.
D="$(dirname "$0")"
OUT=$(python -c "
import math,sys
a0,o0,a1,o1,m=map(float,sys.argv[1:6])
dy=(a1-a0)*111320; dx=(o1-o0)*111320*math.cos(math.radians(a0))
print(int(-dx/m*2.5), int(dy/m*2.5))" "$@" | tr -d '\r')
read DX DY <<< "$OUT"
while [ "$DX" -ne 0 ] || [ "$DY" -ne 0 ]; do
  sx=$DX; sy=$DY
  [ $sx -gt 1200 ] && sx=1200; [ $sx -lt -1200 ] && sx=-1200
  [ $sy -gt 1200 ] && sy=1200; [ $sy -lt -1200 ] && sy=-1200
  "$D/pan.sh" $sx $sy; DX=$((DX-sx)); DY=$((DY-sy))
done
