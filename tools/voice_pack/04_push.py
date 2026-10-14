"""Step 4 (testing): copy a rendered pack to the USB-connected phone without a GitHub release.

    python 04_push.py --pack de-omnivoice [--app de.lanewise.app.debug]
The app finds packs in Android/data/<app>/files/voices/<id>/; select it in Settings → Voice guidance.
"""
import argparse
import os
import subprocess

from common import PACKS, WORK


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True, choices=sorted(PACKS))
    ap.add_argument("--app", default="de.lanewise.app.debug")
    a = ap.parse_args()
    adb = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
    src = WORK / "packs" / a.pack
    dst = f"/sdcard/Android/data/{a.app}/files/voices/"
    subprocess.run([adb, "shell", "mkdir", "-p", dst], check=True)
    subprocess.run([adb, "push", str(src), dst], check=True)
    print(f"pushed {src.name} -> {dst}{a.pack}; restart the app and pick it in Settings")


if __name__ == "__main__":
    main()
