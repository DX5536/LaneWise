"""Step 1: all street names and road refs of the Region Hannover from OSM (Overpass).

    python 01_street_names.py
Output: work/streets.txt (one name per line, e.g. "Celler Straße", "L 190", "A 2").
"""
import time

import requests

from common import STREET_BBOX, WORK

OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]
CLASSES = ("motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|"
           "tertiary|tertiary_link|unclassified|residential|living_street")


def main():
    s, w, n, e = STREET_BBOX
    q = f'[out:json][timeout:300];way({s},{w},{n},{e})["highway"~"^({CLASSES})$"];out tags;'
    data = None
    for attempt in range(8):
        url = OVERPASS[attempt % len(OVERPASS)]
        try:
            r = requests.post(url, data={"data": q}, timeout=360, headers={"User-Agent": "LaneWise-voice-pack/0.1"})
            if r.status_code == 200:
                data = r.json()
                break
            print(f"{url}: HTTP {r.status_code}, retrying")
        except requests.RequestException as ex:
            print(f"{url}: {ex}, retrying")
        time.sleep(10 * (attempt + 1))
    if data is None:
        raise SystemExit("Overpass failed")
    names = set()
    for el in data["elements"]:
        t = el.get("tags", {})
        if t.get("name"):
            names.add(t["name"].strip())
        for ref in (t.get("ref") or "").split(";"):
            if ref.strip():
                names.add(ref.strip())
    WORK.mkdir(exist_ok=True)
    out = WORK / "streets.txt"
    out.write_text("\n".join(sorted(names)) + "\n", encoding="utf-8")
    print(f"{len(names)} names/refs -> {out}")


if __name__ == "__main__":
    main()
