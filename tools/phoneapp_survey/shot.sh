#!/bin/bash
# shot.sh [name] -> work/<name>.png at 40 % size (1440x3120 phone -> 576x1248). Coordinates read on it x 2.5 = phone px.
. "$(dirname "$0")/_env.sh"
"$ADB" exec-out screencap -p > "$WORK/full.png"
"$PY" -I -c "
from PIL import Image
im=Image.open(r'$WORK/full.png'); im.resize((im.width*2//5,im.height*2//5)).save(r'$WORK/${1:-s}.png')"
echo "$WORK/${1:-s}.png"
