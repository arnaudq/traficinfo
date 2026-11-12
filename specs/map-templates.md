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

## Webapp territory boundaries

This section specifies the required boundary configuration, publication and
webapp behavior. The initial release must display the three EPCI territories
and their associated departments listed below, with territory-specific popups
linking to the published uMaps. Displaying count layers in the webapp is a
subsequent release, specified separately below.

`mapconfig.json` must be the source for the public zone catalogue. Each map
may declare a `boundaries` array describing the administrative territories
available on the webapp overview. A map may group several territories and
datasources; each boundary must have its own popup content and source selection.
A boundary must not implicitly select all sources of its parent map.

### Configuration

Configuration example for Montpellier:

```json
{
  "$schema": "./specs/mapconfig.schema.json",
  "geoJsonBaseUrl": "https://objectstorage.eu-frankfurt-1.oraclecloud.com/n/frivwhhfdcjn/b/traficinfo/o",
  "maps": {
    "MMM": {
      "id": "MMM",
      "name": "Comptage véhicules - Montpellier",
      "description": "Carte basée sur les données de la métropole de Montpellier et du département de l'Hérault.",
      "center": [3.88401, 43.606499],
      "zoom": 14,
      "boundaries": [
        {
          "type": "epci",
          "code": "243400017",
          "name": "Montpellier Méditerranée Métropole",
          "geoJsonPath": "boundaries/epci/243400017.geojson",
          "description": "Comptages de véhicules et de vélos publiés par Montpellier Méditerranée Métropole.",
          "dataSources": [
            {"id": "MMM", "types": ["VEHICULES", "BICYCLES"]}
          ],
          "uri": "http://umap.openstreetmap.fr/en/map/comptage-vehicules-montpellier_754880"
        },
        {
          "type": "department",
          "code": "34",
          "name": "Hérault",
          "geoJsonPath": "boundaries/departments/34.geojson",
          "description": "Comptages routiers publiés par le département de l'Hérault.",
          "dataSources": [
            {"id": "DPT34", "types": ["VEHICULES"]}
          ],
          "uri": "http://umap.openstreetmap.fr/en/map/comptage-vehicules-montpellier_754880"
        }
      ],
      "dataSources": [
        {"id": "MMM", "types": ["VEHICULES", "BICYCLES"]},
        {"id": "DPT34", "types": ["VEHICULES"]}
      ],
      "uri": "http://umap.openstreetmap.fr/en/map/comptage-vehicules-montpellier_754880"
    }
  }
}
```

`boundaries` is optional; when present it must be a nonempty array. A map
without boundaries contributes no territories to the webapp overview. Each
boundary must contain `type`, `code`, `name`, `description`, `geoJsonPath` and
`dataSources`. `uri` is optional. Unknown boundary fields must be rejected.

Boundary fields:

- `type`: administrative level; the initial supported values are `epci` and
  `department`. Administrative regions are outside the initial release.
- `code`: official identifier as a string, preserving leading zeros. Use the
  EPCI SIREN or the department/region code. The pair `(type, code)` identifies
  a territory independently of its parent map and datasource IDs.
- `name` and `description`: minimal territory-specific popup content. An
  administrative outline does not imply that every road inside has counts.
- `geoJsonPath`: object path relative to `geoJsonBaseUrl`, resolved by joining
  the base and path with one slash, including when the base has no trailing slash.
  It must begin with `boundaries/`, end with `.geojson`, and contain no parent
  directory segments, query or fragment.
- `dataSources`: the source/type selection for this territory, using the same
  structure and nonempty/distinctness rules as the map-level field. Each source
  and each selected type must be included in the parent map's `dataSources` and
  reference a GeoJSON-enabled source in `dsconfig.json`.
- `uri`: the published uMap link for this territory; it may point to the same
  combined uMap as another boundary. Omit for an unpublished map and do not
  render a link when absent.

`code`, `name` and `description` must be nonempty strings. Boundary identifiers
must be distinct within a map. The schema must validate field structure and
supported types; generation must validate source references, selection subsets
and identifier uniqueness. Invalid configuration must fail generation.

Keep map-level `dataSources`, `center`, `zoom` and `uri` for the combined uMap.
Do not store CSS, colors, line weights, fill opacity or dash patterns in the
boundary configuration. The webapp derives presentation and hit behavior from
`type`, consistently across maps.

### Territory scope and boundary data

The initial three map areas are Montpellier, Nîmes and Rouen, rather than three
administrative regions. Their EPCI and associated departmental references are:

| Map | EPCI | EPCI code | Department | Department code |
| --- | --- | --- | --- | --- |
| MMM | Montpellier Méditerranée Métropole | `243400017` | Hérault | `34` |
| NIM | Communauté d'agglomération de Nîmes Métropole | `243000643` | Gard | `30` |
| MRN | Métropole Rouen Normandie | `200023414` | Seine-Maritime | `76` |

Nîmes Métropole is a communauté d'agglomération despite its name; the EPCI
boundary type covers all three intercommunal territories. Their sources span
two administrative regions, Occitanie and Normandie. Departmental boundaries
provide context for the department-level counts outside the EPCI outlines.

Use [IGN ADMIN EXPRESS](https://cartes.gouv.fr/rechercher-une-donnee/dataset/IGNF_ADMIN-EXPRESS)
as the geographic reference. The government-published
[Contours administratifs dataset](https://www.data.gouv.fr/datasets/contours-administratifs)
provides IGN-derived GeoJSON for EPCI, departments and regions, with several
simplification levels. Use its 100 m simplified contours for the initial
overview and record the source release used.
The [government administrative API](https://geo.api.gouv.fr/decoupage-administratif/epcis)
also exposes individual contours, for example
[Montpellier's GeoJSON](https://geo.api.gouv.fr/epcis/243400017?format=geojson&geometry=contour).

Host prepared boundary files under `boundaries/` in the existing public
`traficinfo` bucket, rather than fetching the government service on each client
visit. Each file must be a WGS84 GeoJSON Feature containing one territory's
Polygon or MultiPolygon geometry. Popup text and source selection stay in the
map registry. The initial published object layout is:

```text
boundaries/epci/243400017.geojson
boundaries/epci/243000643.geojson
boundaries/epci/200023414.geojson
boundaries/departments/34.geojson
boundaries/departments/30.geojson
boundaries/departments/76.geojson
boundaries/attribution.json
boundaries/catalogue.json
```

`attribution.json` records the source URL, release date, simplification level,
licence and attribution for the published files. The Contours administratifs
dataset is marked ODbL; preserve its applicable attribution and licence metadata.

### Client discovery and interaction

Boundary preparation and catalogue generation must be separate from the uMap
template generator, which retains its no-fetch behavior. Catalogue generation
must read the prepared local geometry files and fail for missing files, empty
or invalid geometry, or a territory identifier inconsistent with the registry.

Publish `boundaries/catalogue.json` as an object with a `zones` array. Each
entry must contain `mapId`, `type`, `code`, `name`, `description`, `geoJsonUrl`,
`bounds` and `dataSources`, plus `uri` when configured. Copy the boundary fields
from the registry; resolve `geoJsonUrl` from `geoJsonBaseUrl` and `geoJsonPath`.
Calculate `bounds` from the prepared geometry in
`[west, south, east, north]` order. Do not hand-maintain approximate bounding
boxes in `mapconfig.json`. Entries follow map registry order and then boundary
array order. A zone selection is identified by `(mapId, type, code)`.

The client must load the catalogue from the configured public base URL, then
fetch the boundary files it references. It must not list bucket objects or
download count GeoJSON to discover zones. Boundary objects and the catalogue
must be available through anonymous browser GET requests. Publish referenced
boundary objects before publishing the catalogue.

In the initial release, clicking a boundary must open its name, description and
available uMap link without loading count layers or transitioning to a count
view. A missing `uri` must not produce a broken link. The webapp must render
EPCI with a strong outline and light fill, and departments with a thin dashed
outline and no fill. Department interaction must target only the outline,
leaving EPCI interiors clickable. EPCI layers must be above department layers
and take precedence where outlines overlap. Map-level popup fields must not
replace the selected boundary's content.

### Subsequent release: count layers on demand

The initial release must retain the boundary-specific source/type selections
and derived bounds needed for this interaction. In the subsequent release,
clicking a boundary must transition and zoom to that territory's bounds,
then loads only its declared datasource/type GeoJSON using the existing public
layer URL contract. Selecting Hérault therefore loads DPT34, while selecting
Montpellier Métropole loads MMM. Switching zones removes the previous zone's
count layers; downloaded data may remain cached for reuse. Overview boundary
layers and count layers must have separate lifecycles so counts can be loaded
and unloaded without losing the zone selector.

If automatic zone selection while panning is added later, catalogue bounds can
identify candidate territories before loading their geometry or counts. Bounds
are an extent for navigation and discovery, not an exact containment test.

### Initial release acceptance criteria

1. The registry declares all six territories in the scope table, with the
   correct boundary identifiers and source/type selections.
2. Schema and generation validation accept the configuration example and reject
   unsupported boundary fields/types, duplicate identifiers within a map,
   unknown sources and source/type selections outside the parent map.
3. All six boundary files, their attribution and the generated catalogue are
   publicly readable. Catalogue URLs resolve to the intended files, and each
   generated extent encloses the corresponding geometry.
4. The overview displays all six contours with presentation inferred from
   `type`; no boundary-specific style properties occur in the registry.
5. Clicking an EPCI interior opens that EPCI's popup. Clicking a department
   outline opens the department's popup, except where an EPCI outline takes
   precedence. The department interior does not intercept EPCI clicks.
6. Each popup displays the selected territory's content and configured uMap
   link. An unpublished territory displays no link. Initial map loading and
   popup interaction make no count-GeoJSON requests.
7. uMap template generation continues to use the map-level source selections
   and does not fetch or add webapp boundary layers to generated `.umap` files.
