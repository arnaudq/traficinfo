# Seine-Maritime counting points (DPT76)

`DPT76ComptageParser` implements `ComptageParser` and is registered as
`GeoJsonParser.DataSource.DPT76`, with model `DPT76Comptage`.
It reads UTF-8 GeoJSON (optional BOM), not CSV.

## Source and identifiers

The source is [Localisation des points de comptages routiers CD76 2022](https://www.opendata76.fr/datasets/seine-maritime::localisation-des-points-de-comptages-routiers-cd76-2022/about),
available from the [ArcGIS GeoJSON query](https://services-eu1.arcgis.com/Nt9PzCzX9tz2k5Lq/arcgis/rest/services/Localisation_Comptage_2022/FeatureServer/0/query?outFields=*&where=1%3D1&f=geojson).
The inspected response contains 357 records for 2022, including 352 points and
five null geometries. There are 243 distinct `Code_stat_` values; repeated
station codes can describe different locations and measurements.

By project choice, `properties.FID` identifies a record, yielding `DPT76_<FID>`
as `codeCentrale`. Missing, fractional, negative and duplicate FIDs are rejected.
The top-level GeoJSON `id` and `properties.objectid` are not used as identity.
`objectid` and the nullable station code are retained separately. FID stability
across future publications has not been established; revisit identity before
combining another dataset with this one.

The intended source filename is `DPT76_Comptage_2022.geojson`, with archives
named `DPT76_Comptage_2022_yyyymmdd.geojson`. This package does not download,
archive, schedule or deploy the source; those changes are outside this parser.

## Mapping

| Source | Model / meaning |
| --- | --- |
| `FID` | `fid`; prefixed to form `codeCentrale` |
| `objectid`, `Code_stat_` | `objectId`, `stationCode` |
| `axe` | `routeId`; `getRoadReference()` removes the `76 ` prefix and whitespace |
| `cumuld`, `plod`, `absd` | Source road position; also used in the display name |
| `Annee_de_` | `annee`, taken from each record rather than the filename |
| `Type_de_c_` | `typeCompteur`; blank becomes null, codes remain uninterpreted |
| `Trafic_Mo_` | Nullable `mjaTvTcj`, the total annual average daily traffic |
| `Tmja_sens_`, `Tmja_sen_2` | Nullable `tmjaSens1`, `tmjaSens2` |
| `Pourcenta_` | Nullable `pourcentagePl`, total heavy-vehicle percentage |
| `Pourcent_0`, `Pourcent_1` | Nullable `pourcentagePlSens1`, `pourcentagePlSens2` |
| `geometry.coordinates` | Longitude, latitude in WGS84; no conversion required |

The original Lambert-93 `x` and `y` attributes are ignored. Null geometry
remains null, even if those projected attributes are populated. Non-point,
malformed or out-of-range geometry is rejected. The parser rejects malformed
collections and responses explicitly marked `exceededTransferLimit`; retrieval
must ensure completeness if the service grows beyond its response limit.
Unknown source properties are ignored.

The total TMJA covers both directions, so `directionType` is `BIDIRECTIONAL`
and `type` is `VEHICULES`. Counts are not reconstructed from directional values.
Missing counts remain null in `getMjaTvTcj()`; the shared integer getter returns
0. A measured zero remains distinguishable from null. Counts must be
nonnegative integers. There are no source speed or hourly-peak measurements:
speeds default to 0, maximum speed to -1, OSM way ID and peaks to null.

The default display name includes the road and PR/abscissa when available,
for example `76 D0925 - PR 104 + 200`. Display overrides use the prefixed FID.
The shared GeoJSON writer produces separate features for repeated station codes,
uses the total traffic, and omits unlocated records from the map.

## Tests

`DPT76_Comptage_2022.geojson` is a three-record unmodified extract from the
source response: two locations sharing station code 1235 and one null geometry.
Tests cover source mapping, identity, null versus zero, geometry validation,
BOM/file input, display overrides and shared GeoJSON generation.

Run `mvn -o -pl parsers test` from the repository root.
