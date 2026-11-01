# Map configuration and generation

`mapconfig.json` is the map registry, analogous to the source registry in
`dsconfig.json`. Its schema is `specs/mapconfig.schema.json` (JSON Schema
2020-12). The generator also validates references and key/ID consistency, which
JSON Schema cannot express across these files.

```json
{
  "$schema": "./specs/mapconfig.schema.json",
  "geoJsonBaseUrl": "https://example.org/public-objects",
  "maps": {
    "NIM": {
      "id": "NIM",
      "name": "Comptage véhicules - Nîmes",
      "description": "Carte basée sur les données de Nîmes Métropole et du Gard.",
      "center": [4.3601, 43.8367],
      "zoom": 12,
      "dataSources": [
        {"id": "NIM", "types": ["VEHICULES"]},
        {"id": "DPT30", "types": ["VEHICULES"]}
      ]
    }
  }
}
```

Each map key must match its `id`. IDs contain letters, digits, underscores and
hyphens, beginning with a letter or digit. The ID determines the output path
`map-templates/<id>.umap`; it need not correspond to any data source. `center`
is `[longitude, latitude]` in both the uMap geometry and map properties.
`description` introduces the shared usage instructions. `zoom` is an integer
from 0 to 24; the shared tile provider currently supports up to 18.

`dataSources` is a nonempty, ordered list of objects with `id` and `types`.
Source IDs must be distinct and reference `dsconfig.json`. Each `types` array is
nonempty and contains distinct `VEHICULES` and/or `BICYCLES` values. Only selected
types generate layers. Source order and type order define the displayed order: `{"id": "MMM", "types": ["VEHICULES", "BICYCLES"]}` before
`{"id": "DPT34", "types": ["VEHICULES"]}` produces MMM vehicles, MMM bicycles,
then DPT34 vehicles.

uMap imports layers in array order, assigns increasing ranks, and displays the
highest rank first. The generator therefore serializes the layers in reverse
of the configured display order, with consecutive zero-based ranks. Both the
imported and saved map display the configured order.

Layer names begin with `Comptages véhicules - ` or `Comptages vélos - `,
followed by the source title after its first ` - `. The description links to the active dataset's
`datasetInfoUrl`, and its update cadence comes from `schedule` when recognized.
Sources explicitly disabling GeoJSON are rejected.

Remote layer URLs follow the existing publisher contract:
`<geoJsonBaseUrl>/<dataSourceId>_TraficInfo.geojson` for vehicles and
`<geoJsonBaseUrl>/<dataSourceId>_TraficInfo_BICYCLES.geojson` for bicycles.
Both files are published even if the population is empty. Vehicle layers inherit
the default map popup. Bicycle layers apply `templates/bicycle-layer.json`,
which shows counts and history without speed fields and sets the bicycle icon
URL to `/uploads/pictogram/bicycle.svg`. The default map icon is
`/uploads/pictogram/car.svg`. A trailing slash on the base is optional. The base must be an HTTP(S) URL without a query or fragment.

Optional map fields:

- `uri`: the published uMap URL, retained for MMM and NIM; omit for new maps.
- `properties`: uMap map property overrides, such as `tilelayer`, `filters`, or
  `popupContentTemplate`. Overriding `description` replaces the entire combined
  description. Dedicated `name`, `center` and `zoom` fields take precedence.
- `layerProperties`: layer property overrides keyed by IDs in `dataSources`,
  for example `"layerProperties": {"NIM": {"displayOnLoad": false}}`.
  These overrides apply to all selected population layers before bicycle
  template overrides. Layer names and descriptions can
  be customized here. `remoteData` is always
  generated from the source ID and configured base URL.

Overrides merge objects recursively; arrays and scalar values replace their
defaults. Keep normal uMap feature placeholders such as `{name}` and
`{data_source}` intact in popup templates. The shared popup retains MMM's
existing public Object Storage logo URLs; if changing the hosting location,
also update `popupContentTemplate` in the shared template or map properties.

The generator owns the full configured output files. Store persistent changes
in the registry or shared templates. It never edits `dsconfig.json`, fetches
remote data, updates a hosted uMap instance, or deletes unconfigured files.

Import the generated `.umap` file manually into uMap. Imported map-level filter
changes may revert or disappear after saving and reopening due to
[uMap bug #3531](https://github.com/umap-project/umap/issues/3531). Work around
this in the map's **Manage filters**: temporarily change the affected filter's
label and save, then restore its original label and save again. Reopen the map
from the dashboard to verify that the imported filter persists.
