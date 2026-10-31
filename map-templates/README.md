# uMap templates

`MMM.umap` and `NIM.umap` are generated, importable uMap files. MMM combines
`MMM` and `DPT34`; NIM combines `NIM` and `DPT30`. A map ID is independent of
its data source IDs, so `MMM` can name both a map and a source.

After the repository setup (`source ./scripts/setup.sh`), run:

```sh
./scripts/generate-map-templates.sh          # Create or update all maps
./scripts/generate-map-templates.sh NIM      # Only NIM
./scripts/generate-map-templates.sh --dry-run
./scripts/generate-map-templates.sh --check  # Exit 1 if files need regeneration
```

Generation uses Python 3's standard library and makes no network or OCI calls.
It reads the root `mapconfig.json` and the exported `TRAFICINFO_DSCONFIG`.
Import the resulting `.umap` file into uMap to create or update a map; the
generator does not publish anything. Its remote GeoJSON must already be public.

When updating an existing map, check new map-level filters after a full reload.
uMap's import path can apply map settings in memory without recording a map
change for saving, while the imported layers are saved. If a filter disappears
after reopening, open the map's **Manage filters**, temporarily change that
filter's label and save, then restore its label and save again. Editing the
filter records a map change and saves the imported map settings too. See
[uMap's import and map property handling](https://github.com/umap-project/umap/blob/master/umap/static/umap/js/modules/app.js)
and [filter saving](https://github.com/umap-project/umap/blob/master/umap/static/umap/js/modules/filters.js).

Edit map metadata in `mapconfig.json`, shared map settings in
`templates/map.json`, shared layer settings in `templates/layer.json`, and
bicycle layer overrides in `templates/bicycle-layer.json`.
The shared settings originate from the original MMM export, including its
background, filters, popup and instructions. The common layer fields use a
numeric year and include traffic and speed fields for all sources.
The default icon URL is `/uploads/pictogram/car.svg`; bicycle layers override
it with `/uploads/pictogram/bicycle.svg`.
The “Type de comptage” checkbox filter selects `VEHICULES` or `BICYCLES`
using its `type` property. Set each `dataSources` entry in `mapconfig.json` to
an object such as `{"id": "MMM", "types": ["VEHICULES", "BICYCLES"]}` or
`{"id": "DPT34", "types": ["VEHICULES"]}`. Only selected types generate layers,
in the listed source order and then type order on screen. The generated array
is reversed because uMap imports bottom-to-top and displays higher ranks first.
Vehicles use
`<sourceId>_TraficInfo.geojson` and inherit the map popup; bicycles use
`<sourceId>_TraficInfo_BICYCLES.geojson` and a popup showing daily counts and
history without speed fields. Publish the files referenced by the selected
layers before importing the templates. MMM includes vehicles and bicycles;
DPT34, NIM and DPT30 include only vehicles.
Layers use `"type": "Cluster"` by default, with uMap's default clustering
radius. Set `layerProperties.<sourceId>.type` to `"Default"` in a map's
configuration to disable clustering for that layer, or use
`"cluster": {"radius": 80}` to set an explicit radius.

Regeneration replaces the complete configured `.umap` files, including any
manual edits. Files for removed maps are left in place. Unchanged files are not
rewritten. Configuration and all selected maps are rendered before any writes;
each changed file is replaced atomically.

See the [configuration specification](../specs/map-templates.md) and
[JSON Schema](../specs/mapconfig.schema.json) for adding maps and overrides.
