# Map configuration and generation

This specification defines how TraficInfo describes its maps and makes them
available through generated uMap templates and the webapp. A shared map registry
connects each map to its traffic-count datasources and geographic boundary.
Region and City boundaries organize the webapp overview, allowing users to
discover available maps and open the corresponding uMap.

The sections below define the registry fields, uMap generation rules, boundary
data and public webapp configuration. The initial webapp interaction displays
boundary contours and map links; a subsequent release uses the same relationships
to zoom to a selected map and load its count data on demand.

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
webapp behavior. The initial release must display the three City boundaries
and their containing Region boundaries listed below, with popups providing
access to published uMaps. Displaying count layers in the webapp is a subsequent
release, specified separately below.

### Geographic model and relationships

Region and City are application concepts, independent of a country's
administrative terminology. A Region boundary groups City boundaries; a City
boundary defines the territory of a city map. In France, Region maps to a
**département** and City maps to an **EPCI**, including métropoles and
communautés d'agglomération. Region does not mean a French administrative
région in this specification.

`mapconfig.json` must contain separate top-level `boundaries` and `maps`
registries. The relationships are:

- A datasource is defined in `dsconfig.json` and may be referenced by several maps.
- Each map references one boundary through `boundaryId` and declares its own
  `dataSources`, with the existing ordered source/type selection contract.
- Each City boundary references its containing Region through `parentId`.
  A Region can therefore contain multiple city maps through its child boundaries.
- A Region does not require a map of its own. If one is provided, it is an
  ordinary map referencing the Region boundary, with the same fields and
  behavior as a city map. No separate regional-map entity is required.

Boundaries must not contain datasource selections or uMap links. Those belong
exclusively to maps. A map's boundary defines its view territory; datasource
coverage may extend beyond that boundary, as with sources from départements included
in a city map. The client must not infer datasource membership from containment.

### Configuration

Configuration example for Montpellier:

```json
{
  "$schema": "./specs/mapconfig.schema.json",
  "geoJsonBaseUrl": "https://objectstorage.eu-frankfurt-1.oraclecloud.com/n/frivwhhfdcjn/b/traficinfo/o",
  "boundaries": {
    "departement:34": {
      "boundaryId": "departement:34",
      "type": "region",
      "name": "Hérault",
      "geoJsonPath": "boundaries/departements/34.geojson"
    },
    "epci:243400017": {
      "boundaryId": "epci:243400017",
      "type": "city",
      "name": "Montpellier Méditerranée Métropole",
      "parentId": "departement:34",
      "geoJsonPath": "boundaries/epcis/243400017.geojson"
    }
  },
  "maps": {
    "MMM": {
      "id": "MMM",
      "name": "Comptage véhicules - Montpellier",
      "description": "Carte basée sur les données de la métropole de Montpellier et du département de l'Hérault.",
      "boundaryId": "epci:243400017",
      "center": [3.88401, 43.606499],
      "zoom": 14,
      "dataSources": [
        {"id": "MMM", "types": ["VEHICULES", "BICYCLES"]},
        {"id": "DPT34", "types": ["VEHICULES"]}
      ],
      "uri": "http://umap.openstreetmap.fr/en/map/comptage-vehicules-montpellier_754880"
    }
  }
}
```

`boundaries` must be a nonempty object keyed by boundary identifier. Unknown
boundary fields must be rejected.

Boundary fields:

| Field | Required | Meaning and constraints |
| --- | --- | --- |
| `boundaryId` | Yes | Nonempty URI-like identifier matching the registry key, independent of map and datasource IDs. The scheme identifies the geographic reference system, such as `departement:34` or `epci:243400017`. Preserve leading zeros in the official identifier. |
| `type` | Yes | Application role: exactly `region` or `city`. This determines presentation and interaction independently of the identifier scheme. For France, `departement:` boundaries have type `region`, and `epci:` boundaries have type `city`. |
| `name` | Yes | Nonempty display name for the territory. |
| `parentId` | City only | Required for City boundaries and absent for Region boundaries. Identifies the containing Region boundary and expresses geographic containment. |
| `geoJsonPath` | Yes | Object path relative to `geoJsonBaseUrl`. It must begin with `boundaries/`, end with `.geojson`, and contain no parent directory segments, query or fragment. Join the base and path with one slash, including when the base has no trailing slash. |

Map fields relevant to boundary navigation:

| Field | Required | Meaning and constraints |
| --- | --- | --- |
| `boundaryId` | Yes | Reference to an existing boundary defining the map's view territory. Multiple maps may reference the same boundary; the client must offer them individually. |
| `name`, `description` | Yes | Map-specific popup content, retaining the existing map configuration meanings. |
| `dataSources` | Yes | Ordered datasource/type selections using the existing contract. |
| `center`, `zoom` | Yes | Preferred opening view for the generated uMap. |
| `uri` | No | Published uMap link. Omit for unpublished maps. |

The schema must validate field structure and the two supported types.
Generation must validate key/ID consistency, boundary and parent references,
City-to-Region parent types, and existing datasource reference rules. Invalid
configuration must fail generation.

### Initial territories and authoritative data

For the initial French coverage, the application concepts map as follows:

| Map | City boundary (EPCI) | City boundaryId | Region boundary (département) | Region boundaryId |
| --- | --- | --- | --- | --- |
| MMM | Montpellier Méditerranée Métropole | `epci:243400017` | Hérault | `departement:34` |
| NIM | Communauté d'agglomération de Nîmes Métropole | `epci:243000643` | Gard | `departement:30` |
| MRN | Métropole Rouen Normandie | `epci:200023414` | Seine-Maritime | `departement:76` |

An administrative outline does not imply that every road inside has counts.

Use [IGN ADMIN EXPRESS](https://cartes.gouv.fr/rechercher-une-donnee/dataset/IGNF_ADMIN-EXPRESS)
as the geographic reference for France. The government-published
[Contours administratifs dataset](https://www.data.gouv.fr/datasets/contours-administratifs)
provides IGN-derived GeoJSON. Extract EPCI contours for City boundaries and
département contours for Region boundaries. The on-demand function uses the
official 100 m département contours and the government API's delivered EPCI
contours without additional simplification.
The [government administrative API](https://geo.api.gouv.fr/decoupage-administratif/epcis)
also exposes individual contours, for example
[Montpellier's GeoJSON](https://geo.api.gouv.fr/epcis/243400017?format=geojson&geometry=contour).

Host prepared boundary files under `boundaries/` in the existing public
`traficinfo` bucket, rather than fetching the government service on each client
visit. Each file must be a WGS84 GeoJSON Feature containing one territory's
Polygon or MultiPolygon geometry and its `boundaryId` in feature properties.
The initial published object layout is:

```text
boundaries/epcis/243400017.geojson
boundaries/epcis/243000643.geojson
boundaries/epcis/200023414.geojson
boundaries/departements/34.geojson
boundaries/departements/30.geojson
boundaries/departements/76.geojson
boundaries/attribution.json
```

`attribution.json` records the source URL, release date, simplification level,
licence and attribution for the published files. The Contours administratifs
dataset is marked ODbL; preserve its applicable attribution and licence metadata.

### On-demand boundary publication

`BoundaryFunction` must read the bundled root `mapconfig.json` and publish all
departements plus the configured EPCI boundaries on each manual invocation.
Select the source by identifier
scheme:

| Scheme | Source and selection |
| --- | --- |
| `departement:` | Download the official [100 m département FeatureCollection](https://etalab-datasets.geo.data.gouv.fr/contours-administratifs/latest/geojson/departements-100m.geojson) once per invocation and publish every feature separately, using `properties.code` as the identifier suffix. |
| `epci:` | Download `https://geo.api.gouv.fr/epcis/<id>?format=geojson&geometry=contour`, using the identifier suffix as `<id>`. |

Upload each validated Feature to its configured `geoJsonPath`, or to
`boundaries/departements/<id>.geojson` for departements absent from the registry,
in the bucket identified by `geoJsonBaseUrl`, adding
`properties.boundaryId` while retaining the source geometry and properties.

The function must overwrite existing objects without checking whether the
origin has changed. It must not have a schedule or event trigger. A failure must
stop the invocation without immediate retries; earlier successful uploads may
remain published. Publish attribution after all boundary uploads. Publication
must not add entries to `mapconfig.json`: its configured boundaries continue to
determine which territories the webapp displays.
Record source URLs and download time, plus 100 m simplification on département
file entries; use null for unavailable release dates and EPCI simplification.

The handler, deployment and invocation commands are documented in the
[function module README](../dsfunctions/README.md#boundary-refresh).

### Build output and zone catalogue

Keep the authoritative `mapconfig.json` at the repository root. During the
webapp build, generate `webapp/dist/mapconfig.json` from it and the prepared
local boundary GeoJSON. The source file must not be moved. The public output
must contain the `geoJsonBaseUrl`, `boundaries` and `maps` collections needed by
the client, preserving identifiers, relationships and configured map selections.
The repository-local `$schema` reference and uMap-only `properties` and
`layerProperties` overrides must not be included in the public output.

This public configuration is the **zone catalogue**: the client index of
available territories and map views. Do not generate a separate
`boundaries/catalogue.json` or duplicate maps/datasources into boundary entries.
The client must derive maps directly associated with a boundary from map
`boundaryId` references, and contained city maps from the `parentId` hierarchy.

The build must add the following field to each public boundary entry:

| Field | Source | Meaning and constraints |
| --- | --- | --- |
| `bounds` | Calculated from the prepared geometry | Four numbers in `[west, south, east, north]` order defining the full territory extent. Must not be manually maintained in the source registry. |

Bounds support navigation and viewport candidate selection before geometry is
fetched, not exact containment.

Boundary preparation and public configuration generation must be separate from
the uMap template generator, which retains its no-fetch behavior. Public
configuration generation must fail for missing files, empty or invalid geometry,
or a feature `boundaryId` inconsistent with its registry entry. Publish referenced
boundary objects before deploying the webapp build that references them.

### Initial client discovery and interaction

The client must load `mapconfig.json` from the built webapp and then fetch the
configured overview boundary files using `geoJsonBaseUrl` and `geoJsonPath`.
It must not list bucket objects or download count GeoJSON to discover zones.
Boundary objects and public configuration must be available through anonymous
browser GET requests.

The webapp must render City boundaries with a strong outline and light fill,
and Region boundaries with a thin dashed outline and no fill. Region interaction
must target only the outline, leaving City interiors clickable. City layers must
be above Region layers and take precedence where outlines overlap.

Clicking a City boundary must open its name and the directly associated maps,
including each map's name, description and available uMap link. Clicking a
Region outline must open its name and the maps attached to its child City
boundaries, plus any map directly attached to the Region itself. A Region with
no directly attached map must still offer its contained city maps. When only
one map is available, the popup may display that map directly. A missing `uri`
must not produce a broken link. Initial interaction must not load count layers
or transition to a count view.

### Subsequent release: count layers on demand

The initial public configuration must retain the map-specific source/type
selections and boundary bounds needed for this interaction. In the subsequent
release, selecting a map must transition and zoom to its referenced boundary's
bounds, then load only that map's declared datasource/type GeoJSON using the
existing public layer URL contract. A Region outline with several available
maps must offer a selection before loading a map; geographic containment must
not implicitly combine their datasource selections.

Switching maps must remove the previous map's count layers; downloaded data may
remain cached for reuse. Overview boundary layers and count layers must have
separate lifecycles so counts can be loaded and unloaded without losing the
zone selector. A map attached directly to a Region follows this same behavior.

If automatic selection while panning is added later, boundary bounds can identify
candidate territories before loading their geometry or counts.

### Initial release acceptance criteria

1. The registry declares all six boundaries in the scope table, with the correct
   `region`/`city` types, matching keys and `boundaryId` fields, City-to-Region
   parent references, and map-to-boundary references.
2. Schema and generation validation accept the example and reject unsupported
   fields/types, key/ID mismatches, invalid parents, missing boundary references
   and unknown datasources. Two maps may reference the same datasource without
   duplicating its definition in `dsconfig.json`.
3. All six boundary files and their attribution are publicly readable. Building
   the webapp generates `webapp/dist/mapconfig.json` with bounds enclosing each
   geometry, while retaining the root source registry. No separate zone catalogue
   is required.
4. The overview displays all six contours with presentation inferred from
   `type`; no boundary-specific style properties occur in the registry.
5. Clicking a City interior opens its associated maps. Clicking a Region outline
   offers its contained city maps and any directly attached map, except where a
   City outline takes precedence. Region interiors do not intercept City clicks.
6. Popup links use the selected map's `uri`; unpublished maps display no link.
   Initial loading and popup interaction make no count-GeoJSON requests.
7. Datasource selections and uMap links occur only on map records. A Region
   without its own map remains navigable, and a map attached to a Region uses
   the same representation and behavior as any other map.
8. uMap template generation continues to use the map-level source selections
   and does not fetch or add webapp boundary layers to generated `.umap` files.
