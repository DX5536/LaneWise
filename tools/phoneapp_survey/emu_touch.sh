#!/bin/bash
# emu_touch.sh dtap X Y | pinch X Y IN|OUT : raw multitouch on the emulator's screen (AVD LaneWise_S24).
# `adb shell input tap` is too slow on the emulator for a map app's double-tap zoom (~300 ms between taps), and adb
# has no pinch; this writes kernel touch events to /dev/input/event2 (virtio_input_multi_touch_1, display 0) in one
# shell call. Coordinates are phone px (1440 x 3120), scaled to the device's 0..32767 range.
. "$(dirname "$0")/_env.sh"
export MSYS_NO_PATHCONV=1
DEV=/dev/input/event2
sx() { echo $(( $1 * 32767 / 1440 )); }
sy() { echo $(( $1 * 32767 / 3120 )); }
# EV_ABS=3, EV_SYN=0, ABS_MT_SLOT=47, ABS_MT_TRACKING_ID=57, ABS_MT_POSITION_X=53, _Y=54, ABS_MT_PRESSURE=58,
# ABS_MT_TOUCH_MAJOR=48 (the device has no BTN_TOUCH; a contact needs pressure). Needs `adb root` (emulator only).
down() { echo "sendevent $DEV 3 47 $1; sendevent $DEV 3 57 $2; sendevent $DEV 3 48 5; sendevent $DEV 3 58 200; sendevent $DEV 3 53 $(sx $3); sendevent $DEV 3 54 $(sy $4);"; }
move() { echo "sendevent $DEV 3 47 $1; sendevent $DEV 3 53 $(sx $2); sendevent $DEV 3 54 $(sy $3);"; }
up() { echo "sendevent $DEV 3 47 $1; sendevent $DEV 3 57 -1;"; }
syn() { echo "sendevent $DEV 0 0 0;"; }
case "$1" in
  dtap)
    X=$2; Y=$3
    "$ADB" shell "$(down 0 1 $X $Y) $(syn) $(up 0) $(syn) \
      $(down 0 2 $X $Y) $(syn) $(up 0) $(syn)" ;;
  pinch)
    X=$2; Y=$3; D=${4:-IN}
    if [ "$D" = "IN" ]; then A=60; B=500; else A=500; B=60; fi   # IN = fingers apart = zoom in
    cmd="$(down 0 11 $X $((Y - A))) $(down 1 12 $X $((Y + A))) $(syn)"
    for s in 1 2 3 4 5 6 7 8; do
      d=$(( A + (B - A) * s / 8 ))
      cmd="$cmd $(move 0 $X $((Y - d))) $(move 1 $X $((Y + d))) $(syn)"
    done
    cmd="$cmd $(up 0) $(up 1) $(syn)"
    "$ADB" shell "$cmd" ;;
esac
sleep 0.6
