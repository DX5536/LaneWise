"""Shared helpers for the aerial-lane pipeline (LGLN DOP20 -> road-marking model -> lanes per OSM way).

Everything heavy (imagery, masks, model) lives in the git-ignored data directory:
  tools/aerial_lanes/work/   (override with env LANEWISE_AERIAL_DATA)

Coordinates are ETRS89 / UTM zone 32N (EPSG:25832, metres) - the native CRS of DOP20, so one pixel is
exactly 0.2 m and geometry is plain Euclidean maths.
"""
from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from pyproj import Transformer

GSD = 0.2                      # metres per pixel (DOP20)
TILE_PX = 1024                 # tile edge in pixels
TILE_M = TILE_PX * GSD         # 204.8 m
WMS = "https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms"
OVERPASS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]

# Named areas (south, west, north, east) in WGS84.
AREAS = {
    "hannover": (52.300, 9.600, 52.460, 9.920),          # Landeshauptstadt + inner ring of the Region
    "test": (52.378, 9.735, 52.392, 9.760),              # Celler Str. / Arndtstr. / Hbf north - quick runs
}

# Road classes that carry lane markings often enough to be worth scanning.
ROAD_CLASSES = ("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
                "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified")

_to_utm = Transformer.from_crs("EPSG:4326", "EPSG:25832", always_xy=True)
_to_wgs = Transformer.from_crs("EPSG:25832", "EPSG:4326", always_xy=True)


def to_utm(lon: float, lat: float) -> tuple[float, float]:
    return _to_utm.transform(lon, lat)


def to_wgs(e: float, n: float) -> tuple[float, float]:
    """Returns (lon, lat)."""
    return _to_wgs.transform(e, n)


def data_dir() -> Path:
    base = os.environ.get("LANEWISE_AERIAL_DATA")
    p = Path(base) if base else Path(__file__).resolve().parent / "work"     # git-ignored
    p.mkdir(parents=True, exist_ok=True)
    return p


def sub(name: str) -> Path:
    p = data_dir() / name
    p.mkdir(parents=True, exist_ok=True)
    return p


def area_utm(area: str) -> tuple[float, float, float, float]:
    s, w, n, e = AREAS[area]
    x0, y0 = to_utm(w, s)
    x1, y1 = to_utm(e, n)
    return min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)


# --- tile grid --------------------------------------------------------------------------------------

@dataclass(frozen=True, order=True)
class TileId:
    i: int   # column: easting  = i * TILE_M
    j: int   # row:    northing = j * TILE_M (south edge)

    @property
    def name(self) -> str:
        return f"{self.i}_{self.j}"

    @property
    def bbox(self) -> tuple[float, float, float, float]:
        return self.i * TILE_M, self.j * TILE_M, (self.i + 1) * TILE_M, (self.j + 1) * TILE_M

    @staticmethod
    def parse(name: str) -> "TileId":
        a, b = name.split("_")
        return TileId(int(a), int(b))


def tile_of(e: float, n: float) -> TileId:
    return TileId(math.floor(e / TILE_M), math.floor(n / TILE_M))


def tiles_along(coords: list[tuple[float, float]], buffer_m: float) -> set[TileId]:
    """All tiles within buffer_m of a polyline (sampled every 10 m)."""
    out: set[TileId] = set()
    for (x0, y0), (x1, y1) in zip(coords, coords[1:]):
        d = math.hypot(x1 - x0, y1 - y0)
        k = max(1, int(d / 10))
        for t in range(k + 1):
            x = x0 + (x1 - x0) * t / k
            y = y0 + (y1 - y0) * t / k
            for dx in (-buffer_m, 0, buffer_m):
                for dy in (-buffer_m, 0, buffer_m):
                    out.add(tile_of(x + dx, y + dy))
    return out


# --- roads ------------------------------------------------------------------------------------------

def load_roads(area: str) -> list[dict]:
    p = data_dir() / f"roads_{area}.json"
    if not p.exists():
        raise SystemExit(f"{p} missing - run 01_osm_roads.py --area {area} first")
    return json.loads(p.read_text(encoding="utf-8"))


# --- raster mosaic (lazy, cached) -------------------------------------------------------------------

class Mosaic:
    """Reads single-band uint8 tiles (masks) or RGB tiles from a directory as one big raster in UTM."""

    def __init__(self, folder: Path, ext: str, cache: int = 64):
        self.folder, self.ext, self.cache_size = folder, ext, cache
        self.cache: dict[TileId, np.ndarray | None] = {}

    def tile(self, t: TileId) -> np.ndarray | None:
        if t in self.cache:
            return self.cache[t]
        p = self.folder / f"{t.name}.{self.ext}"
        arr = None
        if p.exists():
            import cv2
            arr = cv2.imread(str(p), cv2.IMREAD_UNCHANGED)
        if len(self.cache) >= self.cache_size:
            self.cache.pop(next(iter(self.cache)))
        self.cache[t] = arr
        return arr

    def sample(self, xs: np.ndarray, ys: np.ndarray, band: int = 0) -> np.ndarray:
        """Bilinear sample of one band at UTM points; NaN where no tile."""
        out = np.full(xs.shape, np.nan, dtype=np.float32)
        tiles = np.floor(xs / TILE_M).astype(int), np.floor(ys / TILE_M).astype(int)
        for ti, tj in set(zip(tiles[0].ravel().tolist(), tiles[1].ravel().tolist())):
            arr = self.tile(TileId(ti, tj))
            if arr is None:
                continue
            if arr.ndim == 3:
                arr = arr[..., band]
            m = (tiles[0] == ti) & (tiles[1] == tj)
            px = (xs[m] - ti * TILE_M) / GSD - 0.5
            py = ((tj + 1) * TILE_M - ys[m]) / GSD - 0.5          # image rows go north -> south
            x0 = np.clip(np.floor(px).astype(int), 0, TILE_PX - 2)
            y0 = np.clip(np.floor(py).astype(int), 0, TILE_PX - 2)
            fx = np.clip(px - x0, 0, 1)
            fy = np.clip(py - y0, 0, 1)
            a = arr.astype(np.float32)
            v = (a[y0, x0] * (1 - fx) * (1 - fy) + a[y0, x0 + 1] * fx * (1 - fy)
                 + a[y0 + 1, x0] * (1 - fx) * fy + a[y0 + 1, x0 + 1] * fx * fy)
            out[m] = v
        return out


def world_to_px(t: TileId, e: float, n: float) -> tuple[float, float]:
    return (e - t.i * TILE_M) / GSD, ((t.j + 1) * TILE_M - n) / GSD


def road_mask(t: TileId, roads: list[dict], pad_m: float = 1.0) -> np.ndarray:
    """Rasterised carriageway estimate for a tile (OSM centre lines buffered by an assumed width)."""
    import cv2
    mask = np.zeros((TILE_PX, TILE_PX), np.uint8)
    x0, y0, x1, y1 = t.bbox
    for r in roads:
        bx0, by0, bx1, by1 = r["bbox"]
        if bx1 < x0 - 30 or bx0 > x1 + 30 or by1 < y0 - 30 or by0 > y1 + 30:
            continue
        half = assumed_half_width(r["tags"]) + pad_m
        pts = np.array([world_to_px(t, e, n) for e, n in r["utm"]], np.float32)
        cv2.polylines(mask, [np.round(pts).astype(np.int32)], False, 255, thickness=max(1, int(2 * half / GSD)))
    return mask


def assumed_half_width(tags: dict) -> float:
    """Generous half width (m) of the carriageway around the OSM centre line."""
    hw = tags.get("highway", "")
    lanes = _int(tags.get("lanes"))
    oneway = tags.get("oneway") == "yes" or hw.startswith("motorway")
    if lanes is None:
        lanes = {"motorway": 3, "trunk": 2, "primary": 4, "secondary": 2}.get(hw, 2)
        if oneway and not hw.startswith("motorway"):
            lanes = max(1, lanes // 2)
    return lanes * 3.5 / 2 + 3.0     # lanes = whole carriageway (one direction for oneway ways)


def _int(v) -> int | None:
    try:
        return int(str(v).split(";")[0])
    except (TypeError, ValueError):
        return None
