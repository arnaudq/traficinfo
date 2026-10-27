# Nîmes Métropole traffic counts (NIM)

`NIMComptageParser` implements `ComptageParser`; `NIMComptage` implements
`Comptage`. The parser streams CSV rows into camera/day/hour aggregates and
returns one record per camera and year. Input rows may be arbitrarily ordered;
output is sorted by camera ID and year. Raw rows are not collected in memory.

## Source documentation

The [2022 dataset](https://data.nimes-metropole.fr/explore/dataset/comptages-routiers-sur-la-voirie-de-lagglomeration-2022/)
and the [subsequent dataset](https://data.nimes-metropole.fr/explore/dataset/comptages-routiers-sur-la-voirie-de-lagglomeration/)
expose the same eight fields in their metadata, checked October 27, 2026.
The publisher describes hourly vehicle counts across both directions. Both
vehicle population and bidirectional coverage come from that description.
The datasets are published under Licence Ouverte v2.0 (Etalab).

| Identifier | CSV label | Mapping |
| --- | --- | --- |
| `id_camera` | `id_camera` | Technical camera ID; `codeCentrale` is `NIM_<id_camera>`. |
| `code_insee` | `code INSEE` | Commune code, retained as text. |
| `commune` | `commune` | City name, normalized with the shared casing helper. |
| `date` | `date` | ISO calendar date; supplies the year. |
| `tranche` | `tranche` | One-hour interval, e.g. `08:00 - 09:00`. |
| `position` | `position` | Source location label, retained without interpreting road references. |
| `coordonnees_geographique` | `coordonnées géographique` | CSV latitude, longitude pair; converted to a GeoJSON longitude, latitude point. |
| `nbpassage` | `nbPassage` | Nonnegative integer hourly count. |

Both field identifiers and French labels are accepted. The CSV reader uses UTF-8,
semicolons, optional BOM and standard quoted fields. Camera ID, date, interval
and count headers are required; location columns may be absent. Duplicate
normalized headers and malformed records fail with an `IOException` rather
than silently producing incomplete output.

## Hourly and daily aggregation

The key of an observation is camera ID, date and hour. Identical hourly counts
are deduplicated. The default `EXCLUDE_HOUR` policy excludes an ambiguous slot
permanently, records
all distinct values, and logs them after all rows have been read. A later
repetition cannot restore an excluded hour. An explicit `REJECT` policy is
available to fail on conflicting counts. Count values may be blank, which
leaves that hour unmeasured. Explicit zero is a measurement and remains zero.
`23:00 - 24:00` and `23:00 - 00:00` both mean the last hour of the stated date.
Other intervals must cover exactly one hour.

A complete day has all 24 distinct hourly slots with valid counts. Daily totals
are summed over complete days; the rounded mean of those totals becomes
`dailyAverage`, exposed through `getMoyenneJourAnnuelle()` for the shared
interface. This is a mean over observed complete days, **not a publisher-supplied
or extrapolated full-year TMJA**. Dates with absent hours do not contribute to
its numerator or denominator. No daylight-saving adjustment is guessed from
these date/hour labels: a 23-slot day is incomplete and conflicting repeated
slots cannot be resolved without further source information.

If there are no complete days, `getDailyAverage()` is null. The shared integer
getter returns 0 for compatibility. The camera/year record and its partial
coverage are retained. The source API returned no explicit zero counts in the
2022 dataset when checked; this does not establish that absent rows mean zero.

Each result retains:

- `observedDays`: distinct dates represented in the CSV, including blank counts.
- `completeDays` and derived `incompleteDays`: represented dates with/without
  all 24 measured hours.
- `observedHours`: distinct usable slots with valid, unambiguous counts.
- `conflictingHours`: distinct ambiguous slots excluded from aggregation.
- `totalPassages`: sum of usable slots, including incomplete days.
- `completeDayPassages`: numerator used for the daily average.
- `firstDate`, `lastDate`: represented date range within the camera/year.
- `daysWithoutRecords`: calendar gaps strictly inside that range.

Missing dates before/after that range are not inferred: the source does not
establish that every camera operated throughout the calendar year. A truncated
file cannot establish full-dataset coverage, and a parser cannot detect cameras
or years entirely absent from its input.

## Location and remaining fields

The latest dated location within each camera/year supplies the position label,
commune, INSEE code and coordinates. Conflicting location metadata for the same
camera/date fails independently of row order. The default display name is
`position - commune`, falling back to the available label or prefixed camera ID.
The properties argument can override it by prefixed ID, e.g. `NIM_52`.

The source does not provide a road reference, OSM way ID, speed statistics or
annual peak-hour averages. These remain null/0 as required by the existing
interface, with maximum speed initially -1. No peak-hour window is invented.
Results define value equality for comparisons between separately parsed inputs.

## Diagnostics

After all rows have been read, diagnostics follow sorted camera/date order:

- A warning for each consecutive run of dates without any records inside the
  observed range, giving camera ID, first/last missing date and number of days.
- A warning for each excluded conflicting hour, identifying its camera/date/hour
  and every distinct observed value.
- A summary for every camera/year and every camera, reporting observed days
  and usable hours. Per-camera coverage combines all retained years.
- A file summary including distinct camera IDs encountered, distinct cameras
  retained, excluded conflicting hours, input rows and ignored 1970 rows.
- Average observed hours per day, per camera and overall: usable hours divided
  by represented camera/dates. Entirely absent dates are excluded from this average.

Records dated 1970 are ignored before hourly values and location are processed.
Individual incomplete days are not logged.

These are records absent from the supplied input, not proof of sensor downtime.
A shuffled or truncated sample is useful for examining these diagnostics.

## Integration and tests

The library parser is registered as `GeoJsonParser.DataSource.NIM`. The cloud
source remains download-only (`geoJsonEnabled: false`); this change does not
introduce a GeoJSON function or manifest publication.

`NIMSample.csv` contains five records fetched through a bounded JSON API query
and converted to CSV using documented field labels. It is not a downloaded CSV
export. Its dates are partial and correctly produce no daily average.
Synthetic tests cover complete days, partial days, missing dates, arbitrary row
order, duplicates, missing/zero values, coordinates, BOM, quoted fields, file
reading, value equality, diagnostics and GeoJSON integration. No large export
has been downloaded by the parser. Locally supplied files can be checked using
the manual program below. A full local `NIM.csv` run processed 470,434 rows,
ignored 24 rows dated 1970, and produced 67 camera/year records for 2022.
It retained 469,257 usable hours across 21,344 represented camera/dates
(21.99 hours/day), with 11,374 complete days, 9,970 incomplete days and 576
excluded conflicting hours. Per-camera observed days ranged from 29 to 363.

## Manual sample program

Run `org.traficinfo.parsers.nim.NIMParserProgram` from the test sources. With no
arguments it reads `~/Downloads/NIM-truncated.csv` and writes the parsed yearly
array to `~/Downloads/NIM-truncated.json`. Optional arguments are an input CSV
path and an output JSON path; with only an input path, JSON is written beside
that CSV using the same basename. The input cannot also be the output.

Parser diagnostics go through the existing logger: conflicting hours,
whole-day gaps, camera/year and camera coverage summaries, and the file summary. Parsing finishes
before the JSON output is opened, so a rejected source leaves an existing output
untouched if parsing fails. The program uses the default `EXCLUDE_HOUR` policy: conflicting
counts are logged, excluded from totals and counted in `conflictingHours`.
Days containing them are incomplete.
