#!/usr/bin/env python3
"""Generate importable uMap files from repository configuration (no network calls)."""

import argparse
from copy import deepcopy
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import urlsplit


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"{path}: duplicate JSON key {key}")
            result[key] = value
        return result

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_keys)


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def http_url(value):
    if not nonempty(value) or any(c.isspace() for c in value):
        return False
    parsed = urlsplit(value)
    return parsed.scheme in ("http", "https") and bool(parsed.netloc)


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def validate(config, sources):
    require(isinstance(config, dict), "mapconfig must be an object")
    require(not (config.keys() - {"$schema", "geoJsonBaseUrl", "maps", "boundaries"}),
            "Unknown mapconfig field")
    boundaries = config.get("boundaries")
    require(isinstance(boundaries, dict) and boundaries,
            "boundaries must be a nonempty object")
    for boundary_id, boundary in boundaries.items():
        require(isinstance(boundary, dict)
                and not (boundary.keys() - {"boundaryId", "type", "name", "parentId", "geoJsonPath"}),
                f"{boundary_id}: invalid boundary fields")
        require(nonempty(boundary_id) and boundary.get("boundaryId") == boundary_id,
                f"{boundary_id}: key must match boundaryId")
        kind = boundary.get("type")
        require(kind in ("region", "city"), f"{boundary_id}: invalid boundary type")
        require(nonempty(boundary.get("name")), f"{boundary_id}: missing name")
        path = boundary.get("geoJsonPath")
        require(nonempty(path) and path.startswith("boundaries/")
                and path.endswith(".geojson") and ".." not in path.split("/")
                and "?" not in path and "#" not in path,
                f"{boundary_id}: invalid geoJsonPath")
        if boundary_id.startswith("departement:"):
            require(kind == "region", f"{boundary_id}: departement must have region type")
        if boundary_id.startswith("epci:"):
            require(kind == "city", f"{boundary_id}: EPCI must have city type")
        if kind == "city":
            parent_id = boundary.get("parentId")
            parent = boundaries.get(parent_id) if isinstance(parent_id, str) else None
            require(isinstance(parent, dict) and parent.get("type") == "region",
                    f"{boundary_id}: parentId must reference a Region boundary")
        else:
            require("parentId" not in boundary, f"{boundary_id}: Region must not have parentId")
    url = config.get("geoJsonBaseUrl")
    require(http_url(url) and not urlsplit(url).query and not urlsplit(url).fragment,
            "geoJsonBaseUrl must be an HTTP(S) URL without query or fragment")
    maps = config.get("maps")
    require(isinstance(maps, dict) and maps, "maps must be a nonempty object")
    require(isinstance(sources, dict), "dsconfig must be an object")
    allowed = {"id", "name", "description", "center", "zoom", "dataSources",
               "uri", "properties", "layerProperties", "boundaryId"}
    for map_id, entry in maps.items():
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", map_id),
                f"Invalid map ID: {map_id}")
        require(isinstance(entry, dict) and not (entry.keys() - allowed),
                f"{map_id}: invalid map fields")
        require(entry.get("id") == map_id, f"{map_id}: key must match id")
        boundary_id = entry.get("boundaryId")
        require(isinstance(boundary_id, str) and boundary_id in boundaries,
                f"{map_id}: boundaryId must reference an existing boundary")
        require(all(nonempty(entry.get(k)) for k in ("name", "description")),
                f"{map_id}: name and description are required")
        center = entry.get("center")
        require(isinstance(center, list) and len(center) == 2
                and all(number(v) for v in center)
                and -180 <= center[0] <= 180 and -90 <= center[1] <= 90,
                f"{map_id}: center must be [longitude, latitude]")
        zoom = entry.get("zoom")
        require(type(zoom) is int and 0 <= zoom <= 24,
                f"{map_id}: zoom must be an integer from 0 to 24")
        selections = entry.get("dataSources")
        require(isinstance(selections, list) and selections,
                f"{map_id}: dataSources must be a nonempty list")
        ids = []
        for selection in selections:
            require(isinstance(selection, dict) and set(selection) == {"id", "types"},
                    f"{map_id}: each data source must contain id and types")
            source_id = selection["id"]
            require(nonempty(source_id), f"{map_id}: missing data source ID")
            types = selection["types"]
            require(isinstance(types, list) and types
                    and all(isinstance(t, str) and t in ("VEHICULES", "BICYCLES") for t in types)
                    and len(types) == len(set(types)),
                    f"{map_id}: {source_id} types must be distinct VEHICULES or BICYCLES values")
            ids.append(source_id)
        require(len(ids) == len(set(ids)), f"{map_id}: dataSources must contain distinct source IDs")
        if "uri" in entry:
            require(http_url(entry["uri"]), f"{map_id}: invalid uri")
        require(isinstance(entry.get("properties", {}), dict),
                f"{map_id}: properties must be an object")
        overrides = entry.get("layerProperties", {})
        require(isinstance(overrides, dict) and not (overrides.keys() - set(ids))
                and all(isinstance(v, dict) for v in overrides.values()),
                f"{map_id}: layerProperties must reference this map's source IDs")
        for source_id in ids:
            require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", source_id),
                    f"{map_id}: invalid data source ID {source_id}")
            source = sources.get(source_id)
            require(isinstance(source, dict) and source.get("id") == source_id,
                    f"{map_id}: unknown or mismatched data source {source_id}")
            require(source.get("geoJsonEnabled") is not False,
                    f"{source_id}: GeoJSON is disabled")
            require(nonempty(source.get("name")), f"{source_id}: missing name")
            datasets = source.get("datasets")
            active_id = source.get("activeDatasetId")
            require(isinstance(datasets, dict) and isinstance(active_id, str)
                    and isinstance(datasets.get(active_id), dict),
                    f"{source_id}: missing active dataset")
            require(http_url(datasets[active_id].get("datasetInfoUrl")),
                    f"{source_id}: missing active datasetInfoUrl")


def merge(target, overrides):
    """Merge property objects recursively; arrays and scalar values replace defaults."""
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            merge(target[key], value)
        else:
            target[key] = deepcopy(value)


def generate(entry, config, sources, map_template, layer_template, bicycle_template):
    result = deepcopy(map_template)
    result.pop("uri", None)
    result["geometry"] = {"type": "Point", "coordinates": entry["center"]}
    properties = result["properties"]
    properties.update(name=entry["name"], center=entry["center"], zoom=entry["zoom"],
                      description=entry["description"] + "\n\n" + properties["description"])
    merge(properties, entry.get("properties", {}))
    # Position and title have dedicated configuration fields.
    properties.update(name=entry["name"], center=entry["center"], zoom=entry["zoom"])
    if "uri" in entry:
        result["uri"] = entry["uri"]
    result["layers"] = []
    schedules = {"Hourly": "horaire", "Daily": "quotidienne",
                 "Weekly": "hebdomadaire", "Monthly": "mensuelle"}
    for selection in entry["dataSources"]:
        source_id = selection["id"]
        source = sources[source_id]
        dataset = source["datasets"][source["activeDatasetId"]]
        description = ("Comptages basés sur les données de "
                       f"[[{dataset['datasetInfoUrl']} | {source['name']}]]")
        if source.get("schedule") in schedules:
            description += f"\n\nMise à jour {schedules[source['schedule']]}"
        for population in selection["types"]:
            suffix, label = ("_BICYCLES", "vélos") if population == "BICYCLES" else ("", "véhicules")
            layer = deepcopy(layer_template)
            source_title = source["name"].split(" - ", 1)[-1]
            layer["properties"].update(name=f"Comptages {label} - {source_title}", description=description)
            merge(layer["properties"], entry.get("layerProperties", {}).get(source_id, {}))
            if population == "BICYCLES":
                merge(layer["properties"], bicycle_template)
            layer["properties"]["remoteData"] = {
                "url": f"{config['geoJsonBaseUrl'].rstrip('/')}/{source_id}_TraficInfo{suffix}.geojson",
                "format": "geojson",
            }
            result["layers"].append(layer)
    # uMap imports in array order, assigns increasing ranks, then displays highest first.
    # Serialize bottom-to-top so the browser displays the configured source/type order.
    result["layers"].reverse()
    for rank, layer in enumerate(result["layers"]):
        layer["rank"] = rank
    return json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def write_atomic(path, content):
    # Replace only after the complete file is written.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=f".{path.name}.", delete=False) as output:
            temporary = Path(output.name)
            output.write(content)
        temporary.chmod(0o644)
        temporary.replace(path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="Show pending changes")
    mode.add_argument("--check", action="store_true", help="Fail if generated files differ")
    parser.add_argument("map_ids", nargs="*", help="Map IDs (default: all configured maps)")
    args = parser.parse_args()
    root = Path(os.environ["TRAFICINFO_ROOT"])
    config = read_json(root / "mapconfig.json")
    sources = read_json(Path(os.environ["TRAFICINFO_DSCONFIG"]))
    validate(config, sources)
    ids = list(dict.fromkeys(args.map_ids)) or list(config["maps"])
    require(all(i in config["maps"] for i in ids), "Unknown map ID requested")
    directory = root / "map-templates"
    base = read_json(directory / "templates/map.json")
    layer = read_json(directory / "templates/layer.json")
    bicycle = read_json(directory / "templates/bicycle-layer.json")
    # Render every requested map before changing any output.
    outputs = [(directory / f"{i}.umap",
                generate(config["maps"][i], config, sources, base, layer, bicycle)) for i in ids]
    changed = False
    for path, content in outputs:
        if path.exists() and path.read_text(encoding="utf-8") == content:
            print(f"Unchanged: {path.name}")
            continue
        changed = True
        action = "Update" if path.exists() else "Create"
        print(f"{action}: {path.name}")
        if not (args.dry_run or args.check):
            write_atomic(path, content)
    return 1 if args.check and changed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"Map generation failed: {error}", file=sys.stderr)
        sys.exit(1)
