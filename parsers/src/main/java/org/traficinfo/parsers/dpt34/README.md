# Hérault departmental traffic counts (DPT34)

The data is published by the Conseil Départemental de l'Hérault on
[Hérault Data](https://www.herault-data.fr/explore/dataset/trafic-de-l-herault-donnees-de-comptage/),
under the Licence Ouverte v2.0 (Etalab).

## Download and updates

Use the complete [CSV export](https://www.herault-data.fr/api/explore/v2.1/catalog/datasets/trafic-de-l-herault-donnees-de-comptage/exports/csv?use_labels=true&delimiter=%3B).
This URL uses the dataset identifier, without a date. Keep `use_labels=true`
for the French column headings expected by the parser. The file is UTF-8,
optionally with a BOM, and uses semicolons as separators.

The [dataset metadata](https://www.herault-data.fr/api/explore/v2.1/catalog/datasets/trafic-de-l-herault-donnees-de-comptage)
reported no update frequency (`update_frequency: null`) when checked on
October 16, 2026. The declared modification date was September 8, 2026 and
the last data processing date was October 1, 2026. Processing does not
necessarily mean that measurements changed. A weekly content comparison
is a suggested retrieval policy, not a publisher commitment.

## Identifiers and snapshot checks

The CSV examined on October 16, 2026 contains 2,940 records and 73 fields:
245 counters, each with one row per year from 2014 through 2025.
The latest year comes from the CSV's `Année` column, not from metadata.

- `Identifiant du compteur` groups a counter across years.
- `DPT34ComptageParser` retains it as `siteId` and exposes
  `DPT34_<siteId>` as `codeCentrale`, for example
  `DPT34_Per.034.2005.00.3`.
- The pair `(Identifiant du compteur, Année)` is unique in this snapshot.
- `OBJECTID` identifies an annual record, not a counter.
- The source identifier combines type, department, section, index and direction,
  for example `Per.034.2005.00.3`. Preserve the complete string and its zeros.
- Long-term stability across future exports is not documented.

For every counter, `Libellé`, `Localisation`, `Commune`
and counter type remain unchanged across these years. The 245 labels are
distinct. Coordinates are identical over 2014–2024; in 2025 they are rounded
to six decimal places, with a maximum difference of approximately 6.6 cm.
All 245 coordinate pairs remain distinct after rounding to six decimal places.

Temporal consistency does not establish that each point lies inside the
named commune.

## Parser mapping

`DPT34Comptage` implements `Comptage`, and `DPT34ComptageParser` implements
`ComptageParser` in `org.traficinfo.parsers.dpt34`.

| CSV field | Java property / meaning |
| --- | --- |
| `Identifiant du compteur` | `siteId`; used to build `codeCentrale`. |
| `Année` | `annee`. |
| `Localisation` | `streetName`; preferred display text. |
| `Libellé` | `altStreetName`; section description and display fallback. |
| `Commune` | `cityName`. |
| `route_id` | `routeId`, for example `34D0011`. |
| `Type de compteur` | `typeCompteur`; observed codes are `Per` (95 counters), `Tou` (147), and `CuP` (3). |
| `Sens de la route` | `sensRoute`; all records in this snapshot have code 3. The parser interprets 3 as combined directions, setting `bidirectionnelle`. |
| `Latitude`, `Longitude` | Nullable decimal coordinates, retained without rounding. `getGeometry()` derives a GeoJSON `Point` in longitude, latitude order, or returns null if either value is missing. |
| `mja_tv_tcj` | `mjaTvTcj`: annual daily average, all vehicles and all day types. Exposed through `getMoyenneJourAnnuelle()`. |

The default display name is `Localisation - Commune`, falling back to
`Libellé` when localisation is missing. City names are normalized with
`StringUtils.toCamelCase`, preserving spaces and hyphens, for example
`SAINT-CLEMENT-DE-RIVIERE` becomes `Saint-Clement-De-Riviere`.
As with M3M, the properties argument
can override display names, keyed here by the prefixed `codeCentrale`.
Records represent motor vehicles (`Comptage.Type.VEHICULES`).

## Missing values and scope

Blank annual traffic values remain null in `getMjaTvTcj()`. The shared
`Comptage` interface requires an integer, so `getMoyenneJourAnnuelle()`
returns 0 for a missing value, matching the existing parser convention.
Use the nullable getter when distinguishing missing data from a real zero.
Coordinates also preserve blanks as null.

The source does not supply OSM way IDs, V85, mean speed, speed limits or
morning/evening hourly peaks. OSM IDs and peaks default to null, speeds to 0,
and the speed limit to -1, following the shared interface's conventions.
The summer (`mje_*`) and monthly (`mjm_*`) measures are not hourly peaks.
PR, abscissa, INSEE code and other traffic columns are ignored.

The shared `Comptage.getGeometry()` getter returns `GeoJsonObject`, allowing
other parsers to supply lines or polygons. The interface has no geometry setter
and defaults to null for sources without geometry.

This package parses CSV and exposes point geometry. Its parser is registered as
`GeoJsonParser.DataSource.DPT34`. When no GeoJSON features are supplied,
`GeoJsonParser` builds one feature per counter from the filtered CSV records,
using the latest available non-null geometry. Counters without geometry are
skipped. Existing merge logic supplies traffic values, history and styling.
The `dsfunctions` module supplies `dpt34.DataSourceFunction` for retrieval and
`dpt34.GeoJsonOutFunction` for publishing the generated GeoJSON.

## Tests

`DPT34Sample.csv` contains five unmodified source rows spanning permanent,
rotating and CuP counters, two years for the same counter, and a missing
annual average. Tests also cover BOM handling, UTF-8 file reading, quoted
semicolons, display overrides, direction handling and missing identifiers.
