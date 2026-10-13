"""Generate LaneWise MapLibre styles (light + dark) for OpenFreeMap / OpenMapTiles vector tiles.

Usage:  python tools/gen_map_styles.py
Writes: app/src/main/assets/styles/lanewise_light.json, lanewise_dark.json
"""
import json
import os

SOURCE = {"type": "vector", "url": "https://tiles.openfreemap.org/planet"}
GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
FONT_REG = ["Noto Sans Regular"]
FONT_BOLD = ["Noto Sans Bold"]

PALETTES = {
    "light": dict(
        bg="#EEF0F4", water="#B9D3F0", park="#D5EBC9", forest="#C3E0B4", residential="#E6E8EE",
        industrial="#E3E0EC", building="#D9DCE5", building_top="#E4E6EE",
        motorway="#FFFFFF", motorway_case="#8E95A8", trunk="#FFFFFF", trunk_case="#9AA1B3",
        primary="#FFFFFF", primary_case="#A9AFBF", minor="#FFFFFF", minor_case="#C9CDD8",
        rail="#B7BCC9", boundary="#9C8FD6", label="#2A2E38", label_halo="#FFFFFF",
        road_label="#3A3F4B", motorway_shield="#6C4CF5",
    ),
    "dark": dict(
        bg="#121419", water="#1B2A3F", park="#18261C", forest="#162418", residential="#171A21",
        industrial="#1A1A24", building="#262A34", building_top="#2E3340",
        motorway="#3A3F4D", motorway_case="#5A6175", trunk="#363B48", trunk_case="#4D5466",
        primary="#323743", primary_case="#454C5C", minor="#2A2E38", minor_case="#363B47",
        rail="#3A3F4B", boundary="#6C5BC2", label="#E6E8EF", label_halo="#0E1015",
        road_label="#C9CDD8", motorway_shield="#9A82FF",
    ),
}


def road(id_, classes, color, width_stops, minzoom=5, casing=False, extra_filter=None):
    flt = ["all", ["in", ["get", "class"], ["literal", classes]]]
    if extra_filter:
        flt.append(extra_filter)
    return {
        "id": id_, "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": minzoom,
        "filter": flt,
        "layout": {"line-cap": "butt" if casing else "round", "line-join": "round"},
        "paint": {"line-color": color, "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], *width_stops]},
    }


def style(name, p):
    layers = [
        {"id": "background", "type": "background", "paint": {"background-color": p["bg"]}},
        {"id": "landuse-res", "type": "fill", "source": "omt", "source-layer": "landuse",
         "filter": ["in", ["get", "class"], ["literal", ["residential", "suburb", "neighbourhood"]]],
         "paint": {"fill-color": p["residential"]}},
        {"id": "landuse-ind", "type": "fill", "source": "omt", "source-layer": "landuse",
         "filter": ["in", ["get", "class"], ["literal", ["industrial", "commercial", "retail"]]],
         "paint": {"fill-color": p["industrial"]}},
        {"id": "park", "type": "fill", "source": "omt", "source-layer": "park", "paint": {"fill-color": p["park"]}},
        {"id": "landcover-wood", "type": "fill", "source": "omt", "source-layer": "landcover",
         "filter": ["in", ["get", "class"], ["literal", ["wood", "forest", "grass"]]],
         "paint": {"fill-color": p["forest"], "fill-opacity": 0.8}},
        {"id": "water", "type": "fill", "source": "omt", "source-layer": "water", "paint": {"fill-color": p["water"]}},
        {"id": "waterway", "type": "line", "source": "omt", "source-layer": "waterway",
         "paint": {"line-color": p["water"], "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.5, 16, 3]}},
        {"id": "boundary-state", "type": "line", "source": "omt", "source-layer": "boundary",
         "filter": ["<=", ["get", "admin_level"], 4],
         "paint": {"line-color": p["boundary"], "line-width": 1, "line-dasharray": [3, 2]}},
        {"id": "rail", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 11,
         "filter": ["==", ["get", "class"], "rail"],
         "paint": {"line-color": p["rail"], "line-width": 1.2, "line-dasharray": [4, 3]}},
        # casings
        road("minor-case", ["minor", "service"], p["minor_case"], [12, 0.5, 18, 14, 22, 120], 12, True),
        road("primary-case", ["primary", "secondary", "tertiary"], p["primary_case"], [7, 0.6, 18, 22, 22, 180], 7, True),
        road("trunk-case", ["trunk"], p["trunk_case"], [5, 0.6, 18, 26, 22, 220], 5, True),
        road("motorway-case", ["motorway"], p["motorway_case"], [5, 0.8, 18, 30, 22, 260], 5, True),
        # fills
        road("minor", ["minor", "service"], p["minor"], [12, 0.3, 18, 11, 22, 110], 12),
        road("primary", ["primary", "secondary", "tertiary"], p["primary"], [7, 0.4, 18, 18, 22, 165], 7),
        road("trunk", ["trunk"], p["trunk"], [5, 0.4, 18, 22, 22, 200], 5),
        road("motorway", ["motorway"], p["motorway"], [5, 0.5, 18, 26, 22, 240], 5),
        # 3D buildings
        {"id": "building-3d", "type": "fill-extrusion", "source": "omt", "source-layer": "building", "minzoom": 14,
         "paint": {
             "fill-extrusion-color": ["interpolate", ["linear"], ["coalesce", ["get", "render_height"], 8], 0, p["building"], 60, p["building_top"]],
             "fill-extrusion-height": ["interpolate", ["linear"], ["zoom"], 14, 0, 15.5, ["coalesce", ["get", "render_height"], 8]],
             "fill-extrusion-base": ["coalesce", ["get", "render_min_height"], 0],
             "fill-extrusion-opacity": 0.85,
         }},
        # labels
        {"id": "road-label", "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": 13,
         "layout": {"symbol-placement": "line", "text-field": ["get", "name"], "text-font": FONT_REG,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 13, 11, 18, 15]},
         "paint": {"text-color": p["road_label"], "text-halo-color": p["label_halo"], "text-halo-width": 1.5}},
        {"id": "motorway-ref", "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": 8,
         "filter": ["all", ["==", ["get", "class"], "motorway"], ["has", "ref"]],
         "layout": {"symbol-placement": "line", "symbol-spacing": 400, "text-field": ["get", "ref"],
                    "text-font": FONT_BOLD, "text-size": 11, "text-rotation-alignment": "viewport"},
         "paint": {"text-color": "#FFFFFF", "text-halo-color": p["motorway_shield"], "text-halo-width": 4}},
        {"id": "place-city", "type": "symbol", "source": "omt", "source-layer": "place",
         "filter": ["in", ["get", "class"], ["literal", ["city", "town"]]],
         "layout": {"text-field": ["coalesce", ["get", "name:de"], ["get", "name"]], "text-font": FONT_BOLD,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 6, 12, 14, 20]},
         "paint": {"text-color": p["label"], "text-halo-color": p["label_halo"], "text-halo-width": 2}},
        {"id": "place-suburb", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 11,
         "filter": ["in", ["get", "class"], ["literal", ["suburb", "village", "quarter", "neighbourhood"]]],
         "layout": {"text-field": ["coalesce", ["get", "name:de"], ["get", "name"]], "text-font": FONT_REG,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 11, 11, 16, 15]},
         "paint": {"text-color": p["label"], "text-halo-color": p["label_halo"], "text-halo-width": 1.5}},
    ]
    return {
        "version": 8, "name": f"LaneWise {name}", "glyphs": GLYPHS,
        "sources": {"omt": SOURCE}, "layers": layers,
        "light": {"anchor": "viewport", "color": "white", "intensity": 0.35 if name == "light" else 0.2},
    }


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "styles")
    os.makedirs(out, exist_ok=True)
    for n, pal in PALETTES.items():
        with open(os.path.join(out, f"lanewise_{n}.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(style(n, pal), f, ensure_ascii=False, indent=1)
            f.write("\n")
    print("written to", os.path.abspath(out))
