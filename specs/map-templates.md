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
      "dataSources": ["NIM", "DPT30"]
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

`dataSources` is a nonempty, distinct, ordered list of IDs from `dsconfig.json`.
Each generates one layer, with zero-based rank following list order. The layer
name comes from the source's `name`, its description links to the active
dataset's `datasetInfoUrl`, and its update cadence comes from `schedule` when
recognized. Sources explicitly disabling GeoJSON are rejected.

Remote layer URLs follow the existing publisher contract:
`<geoJsonBaseUrl>/<dataSourceId>_TraficInfo.geojson`. A trailing slash on the base
is optional. The base must be an HTTP(S) URL without a query or fragment.

Optional map fields:

- `uri`: the published uMap URL, retained for MMM and NIM; omit for new maps.
- `properties`: uMap map property overrides, such as `tilelayer`, `filters`, or
  `popupContentTemplate`. Overriding `description` replaces the entire combined
  description. Dedicated `name`, `center` and `zoom` fields take precedence.
- `layerProperties`: layer property overrides keyed by IDs in `dataSources`,
  for example `"layerProperties": {"NIM": {"displayOnLoad": false}}`.
  Layer names and descriptions can be customized here. `remoteData` is always
  generated from the source ID and configured base URL.

Overrides merge objects recursively; arrays and scalar values replace their
defaults. Keep normal uMap feature placeholders such as `{name}` and
`{data_source}` intact in popup templates. The shared popup retains MMM's
existing public Object Storage logo URLs; if changing the hosting location,
also update `popupContentTemplate` in the shared template or map properties.

The generator owns the full configured output files. Store persistent changes
in the registry or shared templates. It never edits `dsconfig.json`, fetches
remote data, updates a hosted uMap instance, or deletes unconfigured files.
