"""Marking plans (Neumarkierungspläne, LH Hannover FB Tiefbau) -> draft lane_overrides.json entries.

Workflow and rules: docs/MARKING_PLANS.md. Everything this tool writes is a *draft* for a person to review; drafts carry
"draft": true, which the app always rejects. Nothing is copied into lane_overrides.json automatically.

    python -I marking_plans.py inspect PLAN.pdf            legend, dimension labels, scale, overview with grid
    python -I marking_plans.py extract PLAN.pdf            markings by legend role (page coordinates)
    python -I marking_plans.py georef  PLAN.pdf CPS.json   fit page -> ETRS89/UTM32 from control points
    python -I marking_plans.py draft   PLAN.pdf            approaches -> OSM ways -> draft entries + GeoJSON
    python -I marking_plans.py review  PLAN.pdf            HTML: plan crop | DOP20 crop | street-photo links

Output in tools/marking_plans/work/<pdf name>/ (git-ignored: plans and the city base map are never committed).
Needs PyMuPDF and Pillow (`pip install -r requirements.txt` in tools/marking_plans/.venv).
"""
from __future__ import annotations

import argparse
import datetime as dt
import io
import json
import math
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

import pymupdf
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")
UA = "LaneWise-marking-plans (personal project)"
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]
WMS = ("https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap"
       "&LAYERS=ni_dop20&STYLES=&CRS=EPSG:25832&BBOX={x0},{y0},{x1},{y1}&WIDTH={w}&HEIGHT={h}&FORMAT=image/jpeg")

# Legend label keywords -> feature kind (first match wins; order matters: "Demarkierung Schutzstreifen" is a strip).
KINDS = [("radfahrerfurt", "bike_crossing"), ("schutzstreifen", "bike_strip"), ("radfahrstreifen", "bike_lane"),
         ("mittellinie", "centre_line"), ("fahrbahnmarkierung", "lane_line"), ("haltlinie", "stop_line"),
         ("rotmarkierung", "red_surface"), ("sperrfl", "hatched_area"), ("leitbake", "beacon"),
         ("piktogramm", "pictogram"), ("pfeil", "arrow"), ("zufahrt", "driveway"), ("park", "parking")]
NUM = re.compile(r"^~?\d{1,3},\d{2}$")


# ---------------------------------------------------------------------------------------------------------- geometry
def utm32(lat: float, lon: float) -> tuple[float, float]:
    """WGS84 -> ETRS89 / UTM zone 32N (EPSG:25832), Krüger series (mm accuracy in Germany)."""
    a, f = 6378137.0, 1 / 298.257222101
    n = f / (2 - f)
    A = a / (1 + n) * (1 + n * n / 4 + n ** 4 / 64)
    al = [n / 2 - 2 * n * n / 3 + 5 * n ** 3 / 16, 13 * n * n / 48 - 3 * n ** 3 / 5, 61 * n ** 3 / 240]
    phi, lam = math.radians(lat), math.radians(lon - 9.0)
    e = 2 * math.sqrt(n) / (1 + n)
    t = math.sinh(math.atanh(math.sin(phi)) - e * math.atanh(e * math.sin(phi)))
    xi, eta = math.atan2(t, math.cos(lam)), math.atanh(math.sin(lam) / math.sqrt(1 + t * t))
    x = eta + sum(al[j] * math.cos(2 * (j + 1) * xi) * math.sinh(2 * (j + 1) * eta) for j in range(3))
    y = xi + sum(al[j] * math.sin(2 * (j + 1) * xi) * math.cosh(2 * (j + 1) * eta) for j in range(3))
    return 500000 + 0.9996 * A * x, 0.9996 * A * y


def wgs84(e: float, nn: float) -> tuple[float, float]:
    """ETRS89 / UTM 32N -> (lat, lon)."""
    a, f = 6378137.0, 1 / 298.257222101
    n = f / (2 - f)
    A = a / (1 + n) * (1 + n * n / 4 + n ** 4 / 64)
    be = [n / 2 - 2 * n * n / 3 + 37 * n ** 3 / 96, n * n / 48 + n ** 3 / 15, 17 * n ** 3 / 480]
    de = [2 * n - 2 * n * n / 3 - 2 * n ** 3, 7 * n * n / 3 - 8 * n ** 3 / 5, 56 * n ** 3 / 15]
    xi, eta = nn / (0.9996 * A), (e - 500000) / (0.9996 * A)
    xi1 = xi - sum(be[j] * math.sin(2 * (j + 1) * xi) * math.cosh(2 * (j + 1) * eta) for j in range(3))
    eta1 = eta - sum(be[j] * math.cos(2 * (j + 1) * xi) * math.sinh(2 * (j + 1) * eta) for j in range(3))
    chi = math.asin(math.sin(xi1) / math.cosh(eta1))
    phi = chi + sum(de[j] * math.sin(2 * (j + 1) * chi) for j in range(3))
    return math.degrees(phi), 9.0 + math.degrees(math.atan2(math.sinh(eta1), math.cos(xi1)))


def fit_similarity(src: list[tuple[float, float]], dst: list[tuple[float, float]]):
    """Least-squares x' = a*x - b*y + tx, y' = b*x + a*y + ty. Returns (a, b, tx, ty, residuals)."""
    n = len(src)
    mx, my = sum(p[0] for p in src) / n, sum(p[1] for p in src) / n
    ux, uy = sum(p[0] for p in dst) / n, sum(p[1] for p in dst) / n
    sxx = sum((x - mx) ** 2 + (y - my) ** 2 for x, y in src)
    a = sum((x - mx) * (u - ux) + (y - my) * (v - uy) for (x, y), (u, v) in zip(src, dst)) / sxx
    b = sum((x - mx) * (v - uy) - (y - my) * (u - ux) for (x, y), (u, v) in zip(src, dst)) / sxx
    tx, ty = ux - a * mx + b * my, uy - b * mx - a * my
    res = [math.hypot(a * x - b * y + tx - u, b * x + a * y + ty - v) for (x, y), (u, v) in zip(src, dst)]
    return a, b, tx, ty, res


# ------------------------------------------------------------------------------------------------------------ the PDF
class Plan:
    """One plan sheet in display coordinates (the page rotation applied; y grows downwards)."""

    def __init__(self, path: str):
        self.path = path
        self.name = os.path.splitext(os.path.basename(path))[0]
        self.doc = pymupdf.open(path)
        self.page = self.doc[0]
        self.m = self.page.rotation_matrix
        self.out = os.path.join(WORK, self.name)
        os.makedirs(self.out, exist_ok=True)
        self._lines = None
        self._fills = None

    def lines(self):
        """Text lines: (rect, text, (dx, dy) direction in display coordinates)."""
        if self._lines is None:
            rot = pymupdf.Matrix(self.m.a, self.m.b, self.m.c, self.m.d, 0, 0)
            out = []
            for b in self.page.get_text("dict")["blocks"]:
                for ln in b.get("lines", []):
                    t = " ".join(s["text"] for s in ln["spans"]).strip()
                    if t:
                        d = pymupdf.Point(*ln["dir"]) * rot
                        out.append((pymupdf.Rect(ln["bbox"]) * self.m, t, (d.x, d.y)))
            self._lines = out
        return self._lines

    def drawings(self):
        for g in self.page.get_drawings():
            yield g, g["rect"] * self.m

    def fills(self):
        """Filled shapes: dicts with colour, polygon points (display), rect."""
        if self._fills is None:
            out = []
            for g, r in self.drawings():
                if g.get("fill") is None or "f" not in g["type"]:
                    continue
                pts = []
                for it in g["items"]:
                    if it[0] == "l":
                        pts += [it[1] * self.m, it[2] * self.m]
                    elif it[0] == "re":
                        rr = it[1] * self.m
                        pts += [rr.tl, rr.tr, rr.br, rr.bl]
                    elif it[0] == "qu":
                        q = it[1]
                        pts += [q.ul * self.m, q.ur * self.m, q.lr * self.m, q.ll * self.m]
                    elif it[0] == "c":
                        pts += [it[1] * self.m, it[4] * self.m]
                out.append({"color": col(g["fill"]), "rect": r, "pts": [(p.x, p.y) for p in pts]})
            self._fills = out
        return self._fills

    def strokes(self):
        """Thin stroked straight segments (dimension lines, base map): ((x0, y0), (x1, y1), colour, width)."""
        for g, _ in self.drawings():
            if g.get("color") is None or "s" not in g["type"]:
                continue
            for it in g["items"]:
                if it[0] == "l":
                    a, b = it[1] * self.m, it[2] * self.m
                    yield (a.x, a.y), (b.x, b.y), col(g["color"]), g.get("width") or 0

    def save(self, name: str, data) -> str:
        p = os.path.join(self.out, name)
        with open(p, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        return p

    def load(self, name: str):
        p = os.path.join(self.out, name)
        if not os.path.exists(p):
            sys.exit(f"{p} missing - run the earlier step first")
        with open(p, encoding="utf-8") as f:
            return json.load(f)

    def render(self, clip: pymupdf.Rect | None = None, dpi: int = 72, zoom: float | None = None) -> Image.Image:
        # Display rect -> unrotated page rect for the clip.
        c = None
        if clip is not None:
            c = clip * ~self.m
            c.normalize()
        z = zoom if zoom is not None else dpi / 72
        pix = self.page.get_pixmap(matrix=pymupdf.Matrix(z, z), clip=c)
        return Image.open(io.BytesIO(pix.tobytes("png"))).convert("RGB")


def col(c) -> str:
    return "#%02x%02x%02x" % tuple(round(v * 255) for v in c[:3])


# ------------------------------------------------------------------------------------------------------------- inspect
def find_legend(plan: Plan):
    """Legend entries: label (joined lines), column (Bestand / Planung), role, kind, swatch colours, dashed?"""
    lines = plan.lines()
    head = next((r for r, t, _ in lines if t.upper() == "LEGENDE"), None)
    if head is None:
        return []
    bestand = next((r for r, t, _ in lines if t.upper() == "BESTAND" and r.y0 > head.y0), None)
    planung = next((r for r, t, _ in lines if t.upper() == "PLANUNG" and r.y0 > head.y0), None)
    x_split = planung.x0 - 5 if planung else 1e9
    # Swatch frames: thin black stroke rectangles below the column heads.
    top = (bestand or head).y1
    frames = []
    for g, r in plan.drawings():
        if "f" not in g["type"] and g.get("color") is not None and len(g["items"]) == 1 and g["items"][0][0] in ("re", "qu") \
                and r.y0 > top and r.y1 < top + 400 and r.x0 > head.x0 - 300 and 25 < r.width < 120 and 6 < r.height < 25:
            frames.append(r)
    entries = []
    for fr in frames:
        # Label: text lines starting right of the frame, overlapping it vertically (± one line).
        label = [t for r, t, _ in sorted(lines, key=lambda l: l[0].y0)
                 if 0 <= r.x0 - fr.x1 < 15 and r.y1 > fr.y0 - 4 and r.y0 < fr.y1 + 6]
        # Everything inside the frame but the frame itself (a frame-sized *fill* is a surface swatch, kept).
        inside = [g for g, r in plan.drawings() if r.x0 >= fr.x0 - 2 and r.x1 <= fr.x1 + 2 and r.y0 >= fr.y0 - 2
                  and r.y1 <= fr.y1 + 2 and not ("f" not in g["type"] and r.x0 <= fr.x0 + 1 and r.x1 >= fr.x1 - 1
                                                 and r.height >= fr.height - 1)]
        fills = [g for g in inside if g.get("fill") is not None and "f" in g["type"]]
        colours = sorted({col(g["fill"]) for g in fills} | {col(g["color"]) for g in inside if g.get("color") and "f" not in g["type"]})
        text = " ".join(label)
        low = text.lower()
        column = "planung" if fr.x0 >= x_split else "bestand"
        role = "remove" if "demarkierung" in low else ("existing" if column == "bestand" else "planned")
        kind = next((k for key, k in KINDS if key in low), "other")
        # Dashed: several separate fill pieces along the swatch.
        pieces = [g for g in fills if g["rect"].width > 2]
        entries.append({"label": text, "column": column, "role": role, "kind": kind,
                        "colours": colours, "dashed": len(pieces) >= 3,
                        "width_m": 0.25 if " b 0,25" in low or "b 0,25" in low else 0.12 if "s 0,12" in low else None,
                        "frame": [round(v, 1) for v in fr]})
    return sorted(entries, key=lambda e: (e["column"], e["frame"][1]))


def dimension_scale(plan: Plan):
    """Points per metre from the dimension labels ("3,25" next to a dimension line of that length). Median + all."""
    segs = [(a, b) for a, b, c, w in plan.strokes() if c == "#000000" and w <= 0.6 and math.dist(a, b) > 5]
    out = []
    for r, t, (dx, dy) in plan.lines():
        if not NUM.match(t):
            continue
        value = float(t.lstrip("~").replace(",", "."))
        if value <= 0.3:
            continue
        cx, cy = (r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2
        best = None
        for a, b in segs:
            ux, uy = b[0] - a[0], b[1] - a[1]
            ln = math.hypot(ux, uy)
            ux, uy = ux / ln, uy / ln
            if abs(ux * dy - uy * dx) > 0.03:          # not parallel to the text
                continue
            px, py = cx - a[0], cy - a[1]
            along = px * ux + py * uy
            perp = abs(px * uy - py * ux)
            if perp > 12 or along < -2 or along > ln + 2:
                continue
            if best is None or perp < best[0]:
                best = (perp, ln)
        if best:
            out.append({"label": t, "metres": value, "points": round(best[1], 2), "pt_per_m": round(best[1] / value, 3),
                        "at": [round(cx, 1), round(cy, 1)]})
    ratios = sorted(d["pt_per_m"] for d in out)
    med = ratios[len(ratios) // 2] if ratios else None
    for d in out:
        d["off_pct"] = round(100 * (d["pt_per_m"] / med - 1), 1) if med else None
    return med, out


def title_block(plan: Plan):
    txt = [t for _, t, _ in plan.lines()]
    info = {"file": os.path.basename(plan.path)}
    m = re.match(r"(\d{2})-(\d{4})-(\d{4})(?:_Anlage(\d+))?", plan.name)
    if m:
        info["drucksache"] = f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
        if m.group(4):
            info["anlage"] = int(m.group(4))
    for t in txt:
        if (b := re.search(r"Blatt\s*(\d+)", t)) and "blatt" not in info:
            info["blatt"] = int(b.group(1))
        if re.match(r"^(Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)\s+\d{4}$", t):
            info["date"] = t
        if t.startswith("von:"):
            info["section"] = re.sub(r"\s+", " ", t)
    for i, t in enumerate(txt):
        if t.startswith("Neumarkierung") and i > 0:
            info.setdefault("title", " / ".join(txt[max(0, i - 2):i]))
    return info


def cmd_inspect(plan: Plan, _args):
    legend = find_legend(plan)
    scale, dims = dimension_scale(plan)
    info = title_block(plan)
    info.update({"page_pt": [round(plan.page.rect.width), round(plan.page.rect.height)], "rotation": plan.page.rotation,
                 "drawings": len(plan.page.get_drawings()), "pt_per_m": scale,
                 "scale_1_to": round(1000 / (scale / 72 * 25.4)) if scale else None})
    plan.save("plan.json", info)
    plan.save("legend.json", legend)
    plan.save("dimensions.json", dims)
    # Overview with a 100-pt grid, for picking control points.
    img = plan.render(dpi=72)
    d = ImageDraw.Draw(img)
    for x in range(0, img.width, 100):
        d.line([(x, 0), (x, img.height)], fill=(0, 160, 255), width=1)
        d.text((x + 2, 2), str(x), fill=(0, 100, 200))
    for y in range(0, img.height, 100):
        d.line([(0, y), (img.width, y)], fill=(0, 160, 255), width=1)
        d.text((2, y + 2), str(y), fill=(0, 100, 200))
    img.save(os.path.join(plan.out, "overview.png"))
    print(json.dumps(info, ensure_ascii=False, indent=1))
    print(f"\nlegend ({len(legend)} entries) -> {plan.out}/legend.json  (check role / kind / colours by eye!)")
    for e in legend:
        print(f"  {e['column']:8} {e['role']:8} {e['kind']:13} {','.join(e['colours']):24} dashed={e['dashed']!s:5} {e['label']}")
    bad = [d for d in dims if d["off_pct"] is not None and abs(d["off_pct"]) > 3]
    print(f"\ndimensions: {len(dims)} labels matched, scale {scale} pt/m (1:{info['scale_1_to']}), {len(bad)} off by > 3 %")
    for d in bad:
        print(f"  {d['label']} at {d['at']}: {d['off_pct']} %")


# ------------------------------------------------------------------------------------------------------------- extract
def principal_axis(pts):
    n = len(pts)
    mx, my = sum(p[0] for p in pts) / n, sum(p[1] for p in pts) / n
    sxx = sum((p[0] - mx) ** 2 for p in pts) / n
    syy = sum((p[1] - my) ** 2 for p in pts) / n
    sxy = sum((p[0] - mx) * (p[1] - my) for p in pts) / n
    ang = 0.5 * math.atan2(2 * sxy, sxx - syy)
    ux, uy = math.cos(ang), math.sin(ang)
    along = [(p[0] - mx) * ux + (p[1] - my) * uy for p in pts]
    across = [-(p[0] - mx) * uy + (p[1] - my) * ux for p in pts]
    return (mx, my), (ux, uy), max(along) - min(along), max(across) - min(across)


def shape(f, ppm):
    """Length / width (m), centre, axis of one filled polygon."""
    c, u, ln, wd = principal_axis(f["pts"]) if len(f["pts"]) >= 3 else ((0, 0), (1, 0), 0, 0)
    return {"c": c, "u": u, "len": ln / ppm, "wid": wd / ppm}


def chain(pieces, ppm, max_gap_m=8.0):
    """Chains collinear stripe pieces (exploded dashes) into lines; returns lines with pieces, total length and gaps."""
    pieces = sorted(pieces, key=lambda s: (s["c"][0], s["c"][1]))
    used = [False] * len(pieces)
    lines = []
    for i, p in enumerate(pieces):
        if used[i]:
            continue
        used[i] = True
        group = [p]
        grown = True
        while grown:
            grown = False
            for j, q in enumerate(pieces):
                if used[j]:
                    continue
                for g in (group[0], group[-1]):
                    ux, uy = g["u"]
                    if abs(ux * q["u"][1] - uy * q["u"][0]) > 0.12:
                        continue
                    dx, dy = q["c"][0] - g["c"][0], q["c"][1] - g["c"][1]
                    perp = abs(dx * uy - dy * ux) / ppm
                    gap = math.hypot(dx, dy) / ppm - (g["len"] + q["len"]) / 2
                    if perp < 0.3 and gap < max_gap_m:
                        used[j] = True
                        group.append(q)
                        grown = True
                        break
            if grown:
                # Keep the chain ordered along its axis.
                ux, uy = group[0]["u"]
                group.sort(key=lambda s: s["c"][0] * ux + s["c"][1] * uy)
        ux, uy = group[0]["u"]
        proj = [(s["c"][0] * ux + s["c"][1] * uy) / ppm for s in group]
        gaps = [proj[k + 1] - proj[k] - (group[k]["len"] + group[k + 1]["len"]) / 2 for k in range(len(group) - 1)]
        lines.append({"pieces": group, "gaps": gaps})
    return lines


def classify_line(line):
    """solid / dashed / mixed, with the solid and dashed stretches as piece index ranges."""
    gaps = line["gaps"]
    if not gaps or all(g < 0.3 for g in gaps):
        return "solid"
    if all(g >= 0.3 for g in gaps):
        return "dashed"
    return "mixed"


def resolve(entries, style, width, line):
    """The legend entry a line belongs to when several entries share its colour (black: lane line, cycle strip and
    cycle crossing on the sample): by width (B 0,25 vs S 0,12), then by dash length (cycle strips 1 m, lane lines 3 m+).
    None if still ambiguous - the person decides."""
    if len(entries) == 1:
        return entries[0]
    broad = width >= 0.19
    cand = [e for e in entries if e["width_m"] is None or (e["width_m"] >= 0.2) == broad]
    if len(cand) > 1:
        cand = [e for e in cand if e["dashed"] == (style != "solid")] or cand
    if len(cand) > 1 and style != "solid":
        dash = sorted(p["len"] for p in line["pieces"])[len(line["pieces"]) // 2]
        want = "bike_strip" if dash < 1.8 else "lane_line"
        cand = [e for e in cand if e["kind"] == want] or cand
    if len(cand) > 1 and style == "solid":
        cand = [e for e in cand if e["kind"] in ("lane_line", "centre_line")] or cand
    return cand[0] if len(cand) == 1 else None


SURFACES = {"red_surface", "driveway", "hatched_area", "parking"}
PX_PER_M = 20        # raster for arrows and stop lines


def components(mask: Image.Image):
    """Connected components (8-neighbour) of a 1-bit / L mask: lists of (x, y) pixels."""
    w, h = mask.size
    data = mask.load()
    seen = set()
    out = []
    for y in range(h):
        for x in range(w):
            if data[x, y] and (x, y) not in seen:
                stack, comp = [(x, y)], []
                seen.add((x, y))
                while stack:
                    cx, cy = stack.pop()
                    comp.append((cx, cy))
                    for nx in (cx - 1, cx, cx + 1):
                        for ny in (cy - 1, cy, cy + 1):
                            if 0 <= nx < w and 0 <= ny < h and data[nx, ny] and (nx, ny) not in seen:
                                seen.add((nx, ny))
                                stack.append((nx, ny))
                out.append(comp)
    return out


def colour_mask(img: Image.Image, rgb, tol=60) -> Image.Image:
    r, g, b = rgb
    bands = img.split()
    m = None
    for band, v in zip(bands, (r, g, b)):
        part = band.point(lambda q, v=v: 255 if abs(q - v) <= tol else 0)
        m = part if m is None else Image.composite(part, Image.new("L", img.size, 0), m)
    return m


def raster_marks(plan: Plan, colour: str, ppm: float):
    """Arrows and stop lines of one marking colour, found on a raster: an opening of 0.35 m removes every line
    (0.12 / 0.25 m), what stays are stop lines (~0.5 m broad) and arrow heads. Page coordinates."""
    dpi = PX_PER_M / ppm * 72
    old = pymupdf.TOOLS.anti_aliasing_level() if hasattr(pymupdf.TOOLS, "anti_aliasing_level") else None
    pymupdf.TOOLS.set_aa_level(0)
    try:
        img = plan.render(zoom=PX_PER_M / ppm)
    finally:
        pymupdf.TOOLS.set_aa_level(old if old is not None else 8)
    k = PX_PER_M / ppm            # px per page point
    rgb = tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))
    full = colour_mask(img, rgb)
    # Text (house numbers, labels) and the legend column out.
    from PIL import ImageDraw as _D
    dd = _D.Draw(full)
    for r, _, _ in plan.lines():
        dd.rectangle([r.x0 * k - 2, r.y0 * k - 2, r.x1 * k + 2, r.y1 * k + 2], fill=0)
    dd.rectangle([plan.page.rect.width * 0.85 * k, 0, full.width, full.height], fill=0)
    size = int(round(0.35 * PX_PER_M)) | 1
    opened = full.filter(ImageFilter.MinFilter(size)).filter(ImageFilter.MaxFilter(size))
    # Components on a half-size copy (speed), coordinates back to full size.
    small = opened.resize((opened.width // 2, opened.height // 2), Image.NEAREST)
    fulls = full.resize((full.width // 2, full.height // 2), Image.NEAREST)
    fl = fulls.load()
    marks = []
    for comp in components(small):
        if len(comp) < 4:
            continue
        pts = [(x * 2 / k, y * 2 / k) for x, y in comp]
        c, u, ln, wd = principal_axis(pts)
        L, W = ln / ppm + 2 / PX_PER_M, wd / ppm + 2 / PX_PER_M
        if 1.5 <= L <= 12 and 0.3 <= W <= 0.8 and L / W >= 3:
            marks.append({"type": "stop_bar", "c": c, "u": u, "len_m": L, "wid_m": W})
        elif 0.5 <= L <= 3 and 0.5 <= W <= 2.5:
            # Arrow head: the shaft is the unopened marking within 6 m behind it.
            # The arrow = the unopened marking connected to the head (flood fill, at most 8 m from it).
            sx, sy = min(comp, key=lambda q: (q[0] - c[0] * k / 2) ** 2 + (q[1] - c[1] * k / 2) ** 2)
            rad2 = (8 * PX_PER_M / 2) ** 2
            seen, stack = {(sx, sy)}, [(sx, sy)]
            while stack:
                x, y = stack.pop()
                for nx in (x - 1, x, x + 1):
                    for ny in (y - 1, y, y + 1):
                        if (nx, ny) not in seen and 0 <= nx < fulls.width and 0 <= ny < fulls.height and fl[nx, ny]                                 and (nx - sx) ** 2 + (ny - sy) ** 2 <= rad2:
                            seen.add((nx, ny))
                            stack.append((nx, ny))
            shaft = [(x * 2 / k, y * 2 / k) for x, y in seen]
            marks.append({"type": "arrow_head", "c": c, "len_m": L, "wid_m": W, "near": shaft})
    return marks


def arrow_from(head, ppm):
    """Shaft direction and bend of an arrow from its head and the marking pixels around it."""
    hx, hy = head["c"]
    pts = [p for p in head["near"] if math.dist(p, (hx, hy)) / ppm > 1.2]
    if len(pts) < 10:
        return {"bent": None, "dir": None, "note": "no shaft found"}
    # The shaft: the pixels' principal axis; arrow points from the shaft's centre to the head.
    c, u, ln, wd = principal_axis(pts)
    vx, vy = hx - c[0], hy - c[1]
    if u[0] * vx + u[1] * vy < 0:
        u = (-u[0], -u[1])
    off = (-(hx - c[0]) * u[1] + (hy - c[1]) * u[0]) / ppm      # + = head right of the shaft line (page view)
    return {"dir": [round(u[0], 3), round(u[1], 3)], "shaft_len_m": round(ln / ppm, 2), "head_offset_m": round(off, 2),
            "bent": abs(off) > 0.15,
            "unclear": ln / ppm > 9}   # longer than an arrow: it touches a line, read it by eye


def cmd_extract(plan: Plan, _args):
    info = plan.load("plan.json")
    legend = plan.load("legend.json")
    ppm = info["pt_per_m"]
    if not ppm:
        sys.exit("no scale (no dimension labels matched) - set pt_per_m in plan.json by hand")
    roles = {}
    for e in legend:
        for c in e["colours"]:
            roles.setdefault(c, []).append(e)
    feats = {"stripes": [], "bars": [], "arrows": [], "unknown_colours": {}}
    shapes = []
    for f in plan.fills():
        if f["color"] not in roles:
            feats["unknown_colours"][f["color"]] = feats["unknown_colours"].get(f["color"], 0) + 1
            continue
        if f["rect"].x0 > info["page_pt"][0] * 0.85:   # legend / title block column
            continue
        if all(e["kind"] in SURFACES for e in roles[f["color"]]):
            continue
        shapes.append(f)
    # Arrows and stop lines from the raster (AutoCAD draws them as several overlapping polygons and hatch strips).
    zones = []
    for colour, entries in roles.items():
        if not any(e["kind"] in ("lane_line", "centre_line", "stop_line", "arrow") for e in entries):
            continue
        role = entries[0]["role"] if len({e["role"] for e in entries}) == 1 else "ambiguous"
        for m in raster_marks(plan, colour, ppm):
            rec = {"color": colour, "role": role, "c": [round(v, 1) for v in m["c"]], "len_m": round(m["len_m"], 2),
                   "wid_m": round(m["wid_m"], 2)}
            if m["type"] == "stop_bar":
                feats["bars"].append(rec | {"u": [round(v, 3) for v in m["u"]]})
                ux, uy = m["u"]
                h = m["len_m"] / 2 * ppm
                zones.append(((m["c"][0] - ux * h, m["c"][1] - uy * h), (m["c"][0] + ux * h, m["c"][1] + uy * h), 0.5))
            else:
                a = arrow_from(m, ppm)
                feats["arrows"].append(rec | a)
                if a.get("dir"):
                    back = (a["shaft_len_m"] + 1) * ppm
                    zones.append((m["c"], (m["c"][0] - a["dir"][0] * back, m["c"][1] - a["dir"][1] * back), 0.8))
    # Line stripes: single thin polygons that are not part of an arrow or a stop line.
    for f in shapes:
        s = shape(f, ppm)
        if not (0.08 <= s["wid"] <= 0.35 and s["len"] >= 0.5):
            continue
        # Inside an arrow's body or a stop line (within half-width of its axis segment): part of that mark.
        if any(seg_project(s["c"], z0, z1)[0] / ppm < hw for z0, z1, hw in zones):
            continue
        s["color"] = f["color"]
        feats["stripes"].append({"color": f["color"], "_s": s})
    # Lines from the stripe pieces, per colour.
    lines = []
    for color in sorted({s["color"] for s in feats["stripes"]}):
        pieces = [s["_s"] for s in feats["stripes"] if s["color"] == color]
        for ln in chain(pieces, ppm):
            pts = [p["c"] for p in ln["pieces"]]
            first, last = ln["pieces"][0], ln["pieces"][-1]
            ux, uy = first["u"]
            a = (first["c"][0] - ux * first["len"] * ppm / 2, first["c"][1] - uy * first["len"] * ppm / 2)
            b = (last["c"][0] + ux * last["len"] * ppm / 2, last["c"][1] + uy * last["len"] * ppm / 2)
            length = math.dist(a, b) / ppm
            if length < 2:
                continue
            style = classify_line(ln)
            width = sorted(p["wid"] for p in ln["pieces"])[len(ln["pieces"]) // 2]
            e = resolve(roles[color], style, width, ln)
            lines.append({"color": color, "role": e["role"] if e else "ambiguous",
                          "kind": e["kind"] if e else "ambiguous", "style": style,
                          "from": [round(v, 1) for v in a], "to": [round(v, 1) for v in b], "length_m": round(length, 2),
                          "gaps_m": [round(g, 2) for g in ln["gaps"]], "pieces": len(pts),
                          "width_m": round(sorted(p["wid"] for p in ln["pieces"])[len(ln["pieces"]) // 2], 2)})
    for s in feats["stripes"]:
        s.pop("_s")
    out = {"pt_per_m": ppm, "lines": lines, "stop_bars": feats["bars"], "arrow_candidates": feats["arrows"],
           "unknown_colours": feats["unknown_colours"]}
    plan.save("features.json", out)
    by = {}
    for ln in lines:
        k = (ln["role"], ln["kind"], ln["style"])
        by[k] = by.get(k, 0) + 1
    print(f"lines: {len(lines)}  stop bars: {len(feats['bars'])}  arrow candidates: {len(feats['arrows'])}")
    for k, v in sorted(by.items()):
        print(f"  {v:4}  {k[0]:9} {k[1]:13} {k[2]}")
    print("colours not in the legend (base map etc.):", dict(sorted(feats["unknown_colours"].items(), key=lambda kv: -kv[1])[:8]))
    # Debug picture: final state (existing + planned) drawn over the plan.
    img = plan.render(dpi=72)
    d = ImageDraw.Draw(img, "RGBA")
    pal = {"existing": (0, 160, 255, 220), "planned": (0, 200, 60, 230), "remove": (255, 0, 0, 120)}
    for ln in lines:
        d.line([tuple(ln["from"]), tuple(ln["to"])], fill=pal.get(ln["role"], (120, 120, 120, 200)),
               width=3 if ln["style"] == "solid" else 1)
    for b in feats["bars"]:
        d.ellipse([b["c"][0] - 4, b["c"][1] - 4, b["c"][0] + 4, b["c"][1] + 4], outline=(255, 120, 0), width=2)
    for a in feats["arrows"]:
        d.rectangle([a["c"][0] - 5, a["c"][1] - 5, a["c"][0] + 5, a["c"][1] + 5], outline=(200, 0, 200), width=2)
    img.save(os.path.join(plan.out, "features.png"))
    print(f"-> {plan.out}/features.json, features.png (blue existing, green planned, red removed; orange stop bars, "
          f"purple arrow candidates)")


# -------------------------------------------------------------------------------------------------------------- georef
def area_centroid(pts):
    a = cx = cy = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
        c = x0 * y1 - x1 * y0
        a += c
        cx += (x0 + x1) * c
        cy += (y0 + y1) * c
    if abs(a) < 1e-9:
        return sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)
    return cx / (3 * a), cy / (3 * a)


def inside(pt, poly):
    x, y = pt
    hit = False
    for (x0, y0), (x1, y1) in zip(poly, poly[1:] + poly[:1]):
        if (y0 > y) != (y1 > y) and x < x0 + (y - y0) * (x1 - x0) / (y1 - y0):
            hit = not hit
    return hit


def auto_points(plan: Plan):
    """Control points from buildings: a house number written inside a building on the plan <-> the OSM building with
    that number on one of the plan's streets (OSM buildings in Hannover come from the official cadastre). Pairs are
    matched by RANSAC with the scale fixed by the dimension labels, so a wrong pairing (same number, other street)
    drops out. Returns control points or exits."""
    info = plan.load("plan.json")
    ppm = info["pt_per_m"]
    streets = sorted({t for _, t, _ in plan.lines() if re.search(r"(straße|str\.|weg|platz|allee|ring|damm|kamp|kampe|berg|"
                                                                  r"gasse|ufer|tor|hof|feld)$", t, re.I) and len(t) < 40})
    labels = [(r, t) for r, t, _ in plan.lines() if re.fullmatch(r"\d{1,3}[A-Za-z]?", t)]
    # House numbers sit roughly in the middle of their building: good for a first fit (± a few m) only.
    plan_b = [(t.upper(), ((r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2)) for r, t in labels]
    if len(plan_b) < 3:
        sys.exit(f"only {len(plan_b)} house numbers on the plan - give control points by hand")
    print(f"plan: {len(plan_b)} numbered buildings; streets on the plan: {', '.join(streets)}")
    name = info.get("title", "").split(" / ")[0] or next(t for t in streets if t != "Gehweg")
    q = (f'[out:json][timeout:90];area["name"="Hannover"]["boundary"="administrative"]->.a;'
         f'way(area.a)["highway"]["name"="{name}"];out center;')
    cs = [(e["center"]["lat"], e["center"]["lon"]) for e in overpass(q)["elements"] if "center" in e]
    if not cs:
        sys.exit(f"street '{name}' not found in Hannover - give control points by hand")
    s_, w_, n_, e_ = min(c[0] for c in cs) - 0.004, min(c[1] for c in cs) - 0.006, max(c[0] for c in cs) + 0.004, max(c[1] for c in cs) + 0.006
    bb = f"{s_},{w_},{n_},{e_}"
    els = overpass(f'[out:json][timeout:120];(way({bb})["building"];nwr({bb})["addr:housenumber"];);out tags geom;')["elements"]
    osm_b = []
    for e in els:
        t = e.get("tags", {})
        if "addr:housenumber" not in t:
            continue
        if e["type"] == "node":
            c = utm32(e["lat"], e["lon"])
        elif e.get("geometry"):
            pts = [utm32(p["lat"], p["lon"]) for p in e["geometry"]]
            c = area_centroid(pts[:-1] if pts[0] == pts[-1] else pts)
        else:
            continue
        osm_b.append((t["addr:housenumber"].upper().replace(" ", ""), c, t.get("addr:street", "?")))
    print(f"OSM: {len(osm_b)} addresses around {name}")
    pairs = [(pb, ob) for pb in plan_b for ob in osm_b if pb[0] == ob[0]]
    if len(pairs) < 4:
        sys.exit("fewer than 4 house-number pairs - give control points by hand")
    best = []
    for i in range(len(pairs)):
        for j in range(i + 1, len(pairs)):
            (pa, oa), (pb, ob) = pairs[i], pairs[j]
            if pa[0] == pb[0] or math.dist(pa[1], pb[1]) < 20 * ppm:
                continue
            src = [(pa[1][0], -pa[1][1]), (pb[1][0], -pb[1][1])]
            a, b, tx, ty, _ = fit_similarity(src, [oa[1], ob[1]])
            if abs(math.hypot(a, b) * ppm - 1) > 0.03:
                continue
            inl = []
            for pp, oo in pairs:
                x, y = pp[1][0], -pp[1][1]
                if math.hypot(a * x - b * y + tx - oo[1][0], b * x + a * y + ty - oo[1][1]) < 8.0:
                    inl.append((pp, oo))
            if len({pp[0] for pp, _ in inl}) > len({pp[0] for pp, _ in best}):
                best = inl
    if len(best) < 3:
        sys.exit("no consistent set of >= 3 house-number pairs - give control points by hand")
    a, b, tx, ty, res = fit_similarity([(pp[1][0], -pp[1][1]) for pp, _ in best], [oo[1] for _, oo in best])
    print(f"coarse fit from {len(best)} house numbers: rms {math.sqrt(sum(r * r for r in res) / len(res)):.1f} m")
    # Refine: plan base-map line ends (building corners) <-> OSM building corners, nearest within 1.5 m, then 0.8 m.
    osm_pts = [pt for e in els if "building" in e.get("tags", {}) for pt in
               (utm32(g["lat"], g["lon"]) for g in e.get("geometry", []))]
    grid = {}
    for q in osm_pts:
        grid.setdefault((int(q[0] // 2), int(q[1] // 2)), []).append(q)
    plan_pts = list({(round(x, 1), round(y, 1)) for p0, p1, c, w in plan.strokes() if c == "#000000" and w >= 0.3
                     for x, y in (p0, p1)})
    pairs = []
    for tol in (1.5, 1.0, 0.8, 0.6, 0.6):
        pairs = []
        for x, y in plan_pts:
            u, v = a * x - b * -y + tx, b * x + a * -y + ty
            near = [q for i in (-1, 0, 1) for j in (-1, 0, 1) for q in grid.get((int(u // 2) + i, int(v // 2) + j), [])]
            if near:
                q = min(near, key=lambda q: (q[0] - u) ** 2 + (q[1] - v) ** 2)
                if math.dist(q, (u, v)) < tol:
                    pairs.append(((x, y), q))
        if len(pairs) < 10:
            sys.exit(f"only {len(pairs)} building corners matched - give control points by hand")
        a, b, tx, ty, res = fit_similarity([(x, -y) for (x, y), _ in pairs], [q for _, q in pairs])
    print(f"refined on {len(pairs)} building corners")
    cps = []
    for (x, y), q in pairs:
        lat, lon = wgs84(*q)
        cps.append({"pdf": [x, y], "lat": lat, "lon": lon, "what": "building corner (OSM)"})
    return cps


def cmd_georef(plan: Plan, args):
    """CPS.json: [{"pdf": [x, y], "lat": .., "lon": ..}, ...] - page points from overview.png, positions from DOP20/OSM.
    Without CPS.json: control points from numbered buildings (plan <-> OSM), see auto_points."""
    if args.points:
        with open(args.points, encoding="utf-8") as f:
            cps = json.load(f)
    else:
        cps = auto_points(plan)
    if len(cps) < 4:
        sys.exit("need at least 4 control points, well spread over the sheet")
    src = [(c["pdf"][0], -c["pdf"][1]) for c in cps]          # flip y: page y grows down, northing up
    dst = [utm32(c["lat"], c["lon"]) for c in cps]
    a, b, tx, ty, res = fit_similarity(src, dst)
    rms = math.sqrt(sum(r * r for r in res) / len(res))
    m_per_pt = math.hypot(a, b)
    info = plan.load("plan.json")
    dim = 1 / info["pt_per_m"] if info.get("pt_per_m") else None
    print(f"rms {rms:.2f} m, scale {1 / m_per_pt:.3f} pt/m (dimension labels: {info.get('pt_per_m')}), rotation "
          f"{math.degrees(math.atan2(b, a)):.2f}°")
    for c, r in zip(cps, res):
        print(f"  {c['pdf']}  residual {r:.2f} m  {c.get('what', '')}")
    ok = rms <= 0.5 and (dim is None or abs(m_per_pt / dim - 1) <= 0.03)
    if not ok:
        print("REJECTED: needs rms <= 0.5 m and the scale within 3 % of the dimension labels - check the points")
    plan.save("georef.json", {"a": a, "b": b, "tx": tx, "ty": ty, "rms_m": rms, "ok": ok, "points": cps})


def to_latlon(geo, p):
    x, y = p[0], -p[1]
    return wgs84(geo["a"] * x - geo["b"] * y + geo["tx"], geo["b"] * x + geo["a"] * y + geo["ty"])


def to_utm(geo, p):
    x, y = p[0], -p[1]
    return geo["a"] * x - geo["b"] * y + geo["tx"], geo["b"] * x + geo["a"] * y + geo["ty"]


# --------------------------------------------------------------------------------------------------------------- draft
def overpass(query: str):
    """Public mirrors are often busy (HTTP 500 / 504): every mirror, Python first, then curl (Python's certificate
    store fails on some Windows setups), three rounds."""
    import time
    for attempt in range(3):
        for url in OVERPASS:
            host = url.split('/')[2]
            try:
                req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": query}).encode(), headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=120) as r:
                    return json.load(r)
            except Exception as e:  # noqa: BLE001
                print(f"  overpass {host}: {str(e)[:80]}", file=sys.stderr)
            raw = subprocess.run(["curl", "-s", "-m", "120", "-A", UA, "--data-urlencode", f"data={query}", url],
                                 capture_output=True).stdout
            try:
                return json.loads(raw)
            except ValueError:
                print(f"  curl {host}: {raw[:60]!r}", file=sys.stderr)
        time.sleep(15 * (attempt + 1))
    raise RuntimeError("Overpass unavailable")


def seg_project(p, a, b):
    ax, ay = b[0] - a[0], b[1] - a[1]
    L2 = ax * ax + ay * ay or 1e-9
    t = max(0.0, min(1.0, ((p[0] - a[0]) * ax + (p[1] - a[1]) * ay) / L2))
    q = (a[0] + t * ax, a[1] + t * ay)
    side = ax * (p[1] - a[1]) - ay * (p[0] - a[0])
    return math.dist(p, q), t, side


def cmd_draft(plan: Plan, _args):
    geo = plan.load("georef.json")
    if not geo["ok"]:
        sys.exit("georef.json was rejected (rms / scale) - fix the control points first")
    feats = plan.load("features.json")
    info = plan.load("plan.json")
    ppm = feats["pt_per_m"]
    final = [ln for ln in feats["lines"] if ln["role"] in ("existing", "planned") and ln["kind"] in ("lane_line", "centre_line")]
    bars = [b for b in feats["stop_bars"] if b["role"] in ("existing", "planned")]
    # GeoJSON of the final state, for JOSM / iD next to OSM.
    gj = {"type": "FeatureCollection", "features": []}
    for ln in final:
        gj["features"].append({"type": "Feature", "properties": {k: ln[k] for k in ("role", "kind", "style", "length_m", "width_m")},
                               "geometry": {"type": "LineString", "coordinates": [list(reversed(to_latlon(geo, ln["from"]))),
                                                                                  list(reversed(to_latlon(geo, ln["to"])))]}})
    for b in bars:
        gj["features"].append({"type": "Feature", "properties": {"kind": "stop_line", "role": b["role"], "len_m": b["len_m"]},
                               "geometry": {"type": "Point", "coordinates": list(reversed(to_latlon(geo, b["c"])))}})
    for a in feats["arrow_candidates"]:
        if a["role"] in ("existing", "planned"):
            gj["features"].append({"type": "Feature", "properties": {"kind": "arrow_candidate", "role": a["role"]},
                                   "geometry": {"type": "Point", "coordinates": list(reversed(to_latlon(geo, a["c"])))}})
    with open(os.path.join(plan.out, "markings.geojson"), "w", encoding="utf-8") as f:
        json.dump(gj, f)
    if not bars:
        print("no stop lines found: nothing to draft (markings.geojson written)")
        return
    # OSM ways around the sheet.
    lats, lons = zip(*[to_latlon(geo, b["c"]) for b in bars])
    q = (f'[out:json][timeout:90];way["highway"]({min(lats) - 0.0015},{min(lons) - 0.0025},{max(lats) + 0.0015},'
         f'{max(lons) + 0.0025});out tags geom;')
    ways = overpass(q)["elements"]
    for w in ways:
        w["utm"] = [utm32(p["lat"], p["lon"]) for p in w.get("geometry", [])]
    # Group stop bars of one approach: within 25 m of each other and roughly in line across the road.
    # One approach = stop line pieces that are parallel and within 8 m of each other (staggered stop lines included).
    groups = []
    for b in sorted(bars, key=lambda b: b["c"][0]):
        for g in groups:
            if any(math.dist(b["c"], o["c"]) / ppm < 8 and abs(b["u"][0] * o["u"][1] - b["u"][1] * o["u"][0]) < 0.25 for o in g):
                g.append(b)
                break
        else:
            groups.append([b])
    drafts = []
    for g in groups:
        centre = (sum(b["c"][0] for b in g) / len(g), sum(b["c"][1] for b in g) / len(g))
        cu = to_utm(geo, centre)
        # The road crosses its stop line: only way segments roughly perpendicular to the stop line count.
        ux, uy = g[0]["u"]
        bu = (geo["a"] * ux + geo["b"] * uy, geo["b"] * ux - geo["a"] * uy)
        bl = math.hypot(*bu) or 1
        cand = []
        for w in ways:
            if w.get("tags", {}).get("highway") in ("footway", "cycleway", "path", "steps", "service", "track", "pedestrian"):
                continue
            pts = w["utm"]
            for i in range(len(pts) - 1):
                dist, t, side = seg_project(cu, pts[i], pts[i + 1])
                sx, sy = pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]
                sl = math.hypot(sx, sy) or 1
                if abs((sx * bu[0] + sy * bu[1]) / (sl * bl)) > 0.5:
                    continue
                if dist < 15:
                    cand.append((dist, w, i, t, side))
        if not cand:
            drafts.append({"note": f"stop line group at page {[round(v) for v in centre]}: no OSM road within 15 m"})
            continue
        dist, w, i, t, side = min(cand, key=lambda c: c[0])
        pts = w["utm"]
        # Travel direction: the approach ends at the junction just past its stop line, so towards the nearer way end.
        # Cross-check: traffic keeps right, so the stop line should lie right of the centre line. If the two disagree
        # (OSM centre line drawn off-centre, a way that continues through the junction) a person decides.
        seg_len = [math.dist(pts[k], pts[k + 1]) for k in range(len(pts) - 1)]
        pos = sum(seg_len[:i]) + t * seg_len[i]
        forward = (sum(seg_len) - pos) <= pos
        dir_ok = forward == (side < 0)
        to_end = (sum(seg_len) - pos) if forward else pos
        stops = []
        for b in sorted(g, key=lambda b: seg_project(to_utm(geo, b["c"]), pts[i], pts[i + 1])[2]):
            bu = to_utm(geo, b["c"])
            dd, tt, _ = seg_project(bu, pts[i], pts[i + 1])
            pb = sum(seg_len[:i]) + tt * seg_len[i]
            stops.append(round(((sum(seg_len) - pb) if forward else pb), 1))
        # Lane dividers that touch the stop line group: solid stretch length right behind the stop line.
        near = []
        for ln in final:
            for end in (ln["from"], ln["to"]):
                if min(math.dist(end, b["c"]) for b in g) / ppm < 6:
                    near.append(ln)
                    break
        solid = [ln for ln in near if ln["style"] == "solid" and ln["kind"] == "lane_line"]
        est_lanes = len(g) if len(g) > 1 else None
        draft = {
            "id": f"{plan.name}-{w['tags'].get('name', 'way')}-{w['id']}".lower().replace(" ", "-"),
            "draft": True,
            "osm_way": w["id"],
            "direction": ("forward" if forward else "backward") if dir_ok else
                         f"CHECK: {'forward' if forward else 'backward'} by the nearer way end, the other by the keep-right side",
            "turn:lanes": "CHECK: arrows on the plan (purple boxes in features.png), left to right",
            "stop_line:lanes": "CHECK: " + "|".join(str(s) for s in stops),
            "change_spans": [{"from_stop_m": 0, "to_stop_m": round(ln["length_m"], 1), "change:lanes": "CHECK"} for ln in solid],
            "source": f"Neumarkierungsplan LHH FB Tiefbau ({info.get('drucksache', plan.name)}) + CHECK photo",
            "source_plan": {k: info[k] for k in ("drucksache", "anlage", "blatt", "date") if k in info},
            "built_confirmed": "",
            "osm_was": {k: v for k, v in w["tags"].items() if k.startswith(("turn:lanes", "lanes", "change:lanes"))},
            "note": (f"way '{w['tags'].get('name', '?')}' {dist:.1f} m from the stop line group; stop line(s) "
                     f"{', '.join(f'{s} m' for s in stops)} before the way end in travel direction; "
                     f"{est_lanes or '?'} stop line pieces; {len(near)} lane lines touch it ({len(solid)} solid)"),
            "_page": [round(v) for v in centre],
            "_latlon": [round(v, 6) for v in to_latlon(geo, centre)],
        }
        drafts.append(draft)
    plan.save("drafts.json", drafts)
    for d in drafts:
        print(json.dumps(d, ensure_ascii=False))
    print(f"\n-> {plan.out}/drafts.json + markings.geojson. Every CHECK must be filled by a person from the plan AND a"
          f" dated photo; then remove 'draft' and copy into app/src/main/assets/lane_overrides.json (version 2).")


# -------------------------------------------------------------------------------------------------------------- review
def dop20(e0, n0, e1, n1, px_per_m=8):
    w, h = int((e1 - e0) * px_per_m), int((n1 - n0) * px_per_m)
    url = WMS.format(x0=e0, y0=n0, x1=e1, y1=n1, w=w, h=h)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        return Image.open(io.BytesIO(urllib.request.urlopen(req, timeout=60).read())).convert("RGB")
    except Exception:  # noqa: BLE001
        raw = subprocess.run(["curl", "-s", "-m", "60", "-A", UA, url], capture_output=True, check=True).stdout
        return Image.open(io.BytesIO(raw)).convert("RGB")


def cmd_review(plan: Plan, _args):
    drafts = plan.load("drafts.json")
    geo = plan.load("georef.json")
    ppm = plan.load("plan.json")["pt_per_m"]
    rows = []
    for k, d in enumerate(drafts):
        if "_page" not in d:
            rows.append(f"<p>{d.get('note')}</p>")
            continue
        cx, cy = d["_page"]
        half = 30 * ppm
        clip = pymupdf.Rect(cx - half, cy - half, cx + half, cy + half)
        plan.render(clip, dpi=150).save(os.path.join(plan.out, f"review_{k}_plan.png"))
        # DOP20: the same 60 m square around the stop line, north up (the plan may be rotated - compare by eye).
        e, n = to_utm(geo, (cx, cy))
        try:
            dop20(e - 30, n - 30, e + 30, n + 30).save(os.path.join(plan.out, f"review_{k}_dop20.jpg"))
            dop = f"<img src='review_{k}_dop20.jpg'>"
        except Exception as ex:  # noqa: BLE001
            dop = f"<p>DOP20 failed: {ex}</p>"
        lat, lon = d["_latlon"]
        links = (f"<a href='https://www.mapillary.com/app/?lat={lat}&lng={lon}&z=19'>Mapillary</a> · "
                 f"<a href='https://api.panoramax.xyz/#focus=map&map=19/{lat}/{lon}'>Panoramax</a> · "
                 f"<a href='https://www.openstreetmap.org/way/{d['osm_way']}'>OSM way {d['osm_way']}</a>")
        body = json.dumps({x: v for x, v in d.items() if not x.startswith("_")}, ensure_ascii=False, indent=1)
        rows.append(f"<h2>{d['id']}</h2><div class=row><img src='review_{k}_plan.png'>{dop}</div><p>{links}</p>"
                    f"<pre>{body}</pre>")
    html = ("<!doctype html><meta charset=utf-8><title>Marking plan review</title><style>body{font:14px sans-serif;"
            "margin:16px;background:#fff;color:#111}.row{display:flex;gap:8px;flex-wrap:wrap}.row img{max-width:48%;"
            "height:auto;border:1px solid #ccc}pre{background:#f4f4f4;padding:8px;white-space:pre-wrap}</style>"
            f"<h1>{plan.name}</h1><p>Plan crop (left, page orientation) and DOP20 (right, north up), 60 m. Check every "
            "CHECK value against a dated street photo; a plan alone is no proof the road looks like this.</p>" + "".join(rows))
    p = os.path.join(plan.out, "review.html")
    with open(p, "w", encoding="utf-8") as f:
        f.write(html)
    print("->", p)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["inspect", "extract", "georef", "draft", "review"])
    ap.add_argument("pdf")
    ap.add_argument("points", nargs="?", help="georef: control points JSON")
    args = ap.parse_args()
    plan = Plan(args.pdf)
    {"inspect": cmd_inspect, "extract": cmd_extract, "georef": cmd_georef, "draft": cmd_draft, "review": cmd_review}[args.command](plan, args)


if __name__ == "__main__":
    main()
