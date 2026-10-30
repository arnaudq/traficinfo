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

Edit map metadata in `mapconfig.json`, shared map settings in
`templates/map.json`, and shared layer settings in `templates/layer.json`.
The shared settings originate from the original MMM export, including its
background, filters, popup and instructions. The common layer fields use a
numeric year and include traffic and speed fields for all sources.
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
