# Métropole Rouen Normandie traffic counts (MRN)

`MRNComptage` implements `Comptage`; `MRNComptageParser` implements
`ComptageParser` and is registered as `GeoJsonParser.DataSource.MRN`.

## Source and export

The [publisher's dataset](https://data.metropole-rouen-normandie.fr/explore/assets/comptages-et-enquetes-sur-le-reseau-routier/)
contains road-section, junction, bicycle and other campaign records, under
Licence Ouverte v2.0. Use the complete
[CSV export with French labels](https://data.metropole-rouen-normandie.fr/api/explore/v2.1/catalog/datasets/comptages-et-enquetes-sur-le-reseau-routier/exports/csv?use_labels=true&delimiter=%3B).
The parser reads UTF-8 with an optional BOM, semicolon separators and quoted
fields. Coordinates use decimal points; `geopoint` is text in latitude,
longitude order. File-based parsing closes its reader; caller-owned readers
remain open.

Temporary campaigns are published daily as results arrive. Permanent road and
bicycle summaries are updated at least annually; current-year permanent counts
are only validated the following year. Temporary campaigns generally cover a
week, at most three weeks. Preserve the campaign category when interpreting
values labelled as annual averages.

## Mapping

| CSV field | Shared property / handling |
| --- | --- |
| `Id_SIG` | Trimmed `siteId`; `codeCentrale` is `MRN_` plus the complete ID, preserving zeros and direction suffixes. |
| `Année` | `annee`; must be positive and no later than the current year. |
| `Rue`, `Commune` | Trimmed street and city, preserving source capitalization. Display is `street - city`, with city-only, street-only and prefixed-ID fallbacks. Properties can override by prefixed ID. |
| `TV_TMJA` | Nullable `tvTmja`; shared daily average returns 0 when absent. No substitution with TMJO or UVP. |
| `TV_HPM`, `TV_HPM (heure)` | Morning peak, e.g. `923 (08h-09h)`. Brackets become parentheses; the source interval's capitalization is preserved. |
| `TV_HPS`, `TV_HPS (heure)` | Same formatting for evening peak. Missing/nonpositive volumes produce null; a missing interval leaves just the volume. |
| `Nombre de Sens` | `2 sens` means bidirectional; `1 sens` and `Sens unique` mean unidirectional. `getDirectionType()` returns `BIDIRECTIONAL` or `UNIDIRECTIONAL` accordingly. Blank remains null in `directionCount` and yields `UNKNOWN`. Unknown nonblank labels skip the record with a warning. |
| `X_84`, `Y_84` | Nullable longitude/latitude, supplying `Point(longitude, latitude)` without rounding. |
| `geopoint` | Fallback coordinate pair if either explicit coordinate is missing. No complete pair means null geometry. Invalid/nonfinite or out-of-range coordinates fail parsing. |
| `Type de Campagne`, `Détails de la campagne` | Population mapping below; original text retained. |

No road reference is extracted, including when the street contains a label such
as D921. `getRoadReference()` always uses the interface's null default, to avoid
incorrect road-reference matching in this urban source. OSM way IDs start null,
mean/V85 speeds start 0 and maximum speed starts -1. The shared enrichment can
later supply way IDs and speed limits using the point location.

All other source fields are retained through source-specific getters:
`Commune (Code INSEE)`, network manager, orientation, weekday daily average,
heavy-vehicle percentages, bicycle evolution indicators, UVP measures and their
peak intervals. INSEE codes remain strings. They are not added to the shared
GeoJSON output. Blank numeric measures remain null, distinct from real zero.

## Campaign populations and filtering

| Campaign | Shared type |
| --- | --- |
| Permanent or temporary road section | `VEHICULES` |
| Temporary directional junction | `UNKNOWN` |
| Permanent bicycle count | `BICYCLES` |
| Temporary soft-mode survey with details exactly `Vélos` | `BICYCLES` |
| Temporary soft-mode survey with details exactly `Piétons / Vélos` | `PIETONS_VELOS` |
| Temporary soft-mode survey with details exactly `Piétons / Vélos / EDP` | `PIETONS_VELOS` |
| Other populations, including other EDP combinations, other surveys and unknown or blank categories | `UNKNOWN`; warning logged with counter ID, campaign and details. |

The parser uses an explicit `TYPE_MAP` keyed by `Type de Campagne`, with a
population-details map for soft-mode campaigns. Missing mappings default to
`UNKNOWN`, never vehicles. Unknown populations and missing counts are retained
by the parser. The shared
GeoJSON filters exclude missing/zero averages and currently include only
vehicles and bicycles, exported separately as `MRN_TraficInfo.geojson` and
`MRN_TraficInfo_BICYCLES.geojson`. Unknown populations are excluded even if they contain
counts. Location-only junction and soft-mode surveys therefore
do not generate traffic features. Pedestrian-only and other EDP combinations remain unknown. The mixed
`Piétons / Vélos / EDP` population uses the shared `PIETONS_VELOS` type.

The shared renderer supplies the existing year window, latest geometry,
three-year displayed history and styling. No retrieval functions, OCI resources
or schedules are added by this parser implementation.

## Snapshot validation and tests

The complete export downloaded on October 22, 2026 contained 8,301 records
and 29 fields. Before unknown populations were retained, the parser retained
8,017 records and skipped 284 unsupported population/survey records. Shared
GeoJSON rendering produced 2,524 features. These are historical snapshot figures,
not validation totals for the current mapping.
All source records had coordinates. Road-section and permanent bicycle records
were unique by `(Id_SIG, Année)`; duplicate pairs occurred in location-only
surveys. Future identifier stability is not guaranteed, and no aggregation or
deduplication across campaigns is performed.

`MRNSample.csv` contains seven unmodified source rows, including two years for
one site, road and bicycle counts, and location-only surveys. Tests cover
mapping, BOM and quoted fields, file reading, display overrides, coordinate
fallback and validation, missing values, population/direction handling, malformed
numbers, GeoJSON history, peaks and OSM enrichment with a null road reference.

Run `mvn -o -pl parsers test` from the repository root.
