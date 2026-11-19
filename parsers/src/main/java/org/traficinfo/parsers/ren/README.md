# Rennes Métropole road counts (REN)

Publisher: Rennes Métropole, [Comptages routiers à la station](https://data.rennesmetropole.fr/explore/dataset/comptages-routiers-station/),
under ODbL 1.0. The source ID is `REN`; the active dataset ID is `current`.

Retrieval uses the UTF-8 [semicolon CSV export](https://data.rennesmetropole.fr/api/explore/v2.1/catalog/datasets/comptages-routiers-station/exports/csv?where=annee%3E%3D2026&use_labels=false&delimiter=%3B).
The export endpoint returns all matching records, rather than one page, with
original field names (`use_labels=false`). The active `current` dataset requests
`annee >= 2026` and processes the explicit `[2026]` scope. Add subsequent years
to that scope as they are published.

Historical observations are split into five-year datasets. Each URL filters both
the first and last year, and each input names the corresponding uploaded CSV:

| Dataset ID | Years | Input object in `dsren` |
| --- | --- | --- |
| `ren-2021-2025` | 2021–2025 | `REN_Comptage_2021-2025.csv` |
| `ren-2016-2020` | 2016–2020 | `REN_Comptage_2016-2020.csv` |
| `ren-2011-2015` | 2011–2015 | `REN_Comptage_2011-2015.csv` |
| `current` | 2026 onward | `REN_Comptage.csv` |

Only `current` is retrieved on schedule. Historical files use the existing
one-off archive preprocessing path without switching the active dataset.
Observations before 2011 are outside every configured scope. The parser reads
each record's `annee`; it does not infer years from filenames or timestamps.

## Inspected publication

On 19 November 2026, the unfiltered GeoJSON export contained 15,264 features, matching
the catalogue's `records_count`, covering 1995–2026. It contains 2,323 distinct
`code_rm` values and 7,085 station/year combinations. All station/year/direction
triples are unique and have nonblank identity fields. Sixteen records have no
geometry; no station code has differing geometry among its records in this
snapshot. Missing measures and coordinates remain null, distinct from measured
zeroes. These observations describe this publication, not guarantees about
future updates.

Both CSV and GeoJSON exports since 2011 contain 12,210 records, matching the
filtered records API count. All 16 years from 2011 through 2026 are represented.

The separate [station inventory](https://data.rennesmetropole.fr/explore/dataset/stations_comptages_routiers/)
contains 2,578 stations, station UIDs, municipality codes and descriptions. It is
not needed to locate counts: the CSV already contains WGS84 `geo_point_2d` values in **latitude, longitude**
order. The parser splits this field and constructs Point geometry in longitude,
latitude order for shared output. There is no companion-file join or
projection conversion. Blank `geo_point_2d` fields are retained as missing geometry; the redundant
`geo_shape` field is not used as a fallback. The count export does not provide road references or
street/city names, so these are not inferred from station codes.

## Identity and directional coverage

A counter/site ID is `REN_<code_rm>`, with the trimmed publisher code preserved
verbatim, for example `REN_RM435.W`. It identifies the station across years and
measurement directions, and is the shared GeoJSON grouping and OSM cache key.
`stationCode` retains the original `code_rm`; `sens` remains a separate source
field. A measurement is uniquely identified by `(codeCentrale, annee, sens)`.
Duplicate measurements fail parsing. Several measurements with the same counter
ID and year are valid when their `sens` differs. Source-aware snapshot comparison
uses `sens` as its discriminator, like MMM uses `codeCanal`.

`Sens cumulés` maps to BIDIRECTIONAL; `Nord-Sud / Ouest-Est` and
`Sud-Nord / Est-Ouest` map to UNIDIRECTIONAL. The observed `Voie 1` and `Voie 2`
labels and future unknown labels map to UNKNOWN because their coverage is not
documented precisely enough to infer it. `directionType` describes coverage,
while `sens` identifies the particular measurement direction or channel.

Preprocessing preserves all annual/directional records. GeoJSON uses the shared
selection rules used by MMM and MRN:

- Group by `codeCentrale`, producing one point and history per counter/site.
- Select the latest eligible year and the row with the highest daily average
  for that year. Do not sum directions, and do not require or explicitly prefer
  `Sens cumulés`. A directional-only station remains eligible.
- Take the maximum positive V85 and mean speed independently across the eligible
  rows in that selected year, even when another row supplies the daily count.
  These are channel maxima, rather than recalculated combined-traffic speeds.
- Select one daily count per year for history using the same descending count
  ordering. Existing population, date, count-range and geometry rules apply.

Combined and directional records therefore share a map feature without losing
measurement identity in storage or comparisons. The default display description
is the station code plus the selected row's `sens`; a display override keyed by
station ID applies to every measurement for that station.

## Measures

| Source field | Stored meaning / shared output |
| --- | --- |
| `annee` | Observation year |
| `tmja_tv` | All-vehicle daily average; rounded to nearest integer for shared output |
| `tmja_pl` | Heavy-vehicle daily average, not a percentage |
| `v85_tv`, `v_moyenne_tv` | All-vehicle V85 and mean speed; rounded for shared output |
| `v85_pl`, `v_moyenne_pl` | Heavy-vehicle V85 and mean speed |
| `debit_hpm_tv`, `debit_hps_tv` | Morning 08:00–09:00 and evening 17:00–18:00 flows outside weekends |
| `debit_hpm_pl`, `debit_hps_pl` | Corresponding heavy-vehicle peak-hour flows |
| `nb_semaines` | Measurement duration in the publisher's units; 0.1 means one day |
| `fiabilite` | Raw publisher indicator (observed colour string), without inferred quality thresholds |

All numeric source measures retain nullable decimal values in stored yearly
records. Missing daily averages and speeds use the shared interface's zero
fallback without changing the nullable source fields. Peak-hour flows preserve
their decimal precision as shared strings. Publisher-computed TMJA is kept as
published even for short measurement campaigns; it is not recalculated or
presented as a full year of continuous observation. Presentation fields ending
in `_formate` or `_carto`, `wms_time` and redundant `geo_shape` are ignored.

No source speed limit, bicycle counts or OSM assignment is invented. Shared
output filtering/history and cached OSM enrichment apply normally. The parser
accepts Reader and UTF-8 file inputs, an optional BOM and display overrides;
missing/duplicate required headers, malformed identity, duplicate records,
negative/non-finite measures, invalid coordinates and malformed rows fail before
publication. Shared preprocessing retains CSV header validation. Null source measures and null geometry are
retained for the existing shared publication filters.
