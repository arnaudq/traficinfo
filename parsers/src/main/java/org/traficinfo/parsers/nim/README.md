# Nîmes Métropole traffic counts (NIM)

`NIMComptageParser` implements `ComptageParser`; `NIMComptage` implements
`Comptage`. The parser streams CSV rows into site/day/hour aggregates and
returns one record per registered site and year. Input rows may be arbitrarily
ordered; output is sorted by `codeCentrale` and year. Raw rows are not collected in memory.

## Source documentation

The [2022 dataset](https://data.nimes-metropole.fr/explore/dataset/comptages-routiers-sur-la-voirie-de-lagglomeration-2022/)
and the [subsequent dataset](https://data.nimes-metropole.fr/explore/dataset/comptages-routiers-sur-la-voirie-de-lagglomeration/)
expose the same eight fields in their metadata, checked October 27, 2026.
The publisher describes hourly vehicle counts across both directions. Both
vehicle population and bidirectional coverage come from that description.
The datasets are published under Licence Ouverte v2.0 (Etalab).

| Identifier | CSV label | Mapping |
| --- | --- | --- |
| `id_camera` | `id_camera` | Source device ID; sites have distinct `codeCentrale` values `NIM_<id_camera>_S01`, `_S02`, etc. |
| `code_insee` | `code INSEE` | Commune code, retained as text. |
| `commune` | `commune` | City name, normalized with the shared casing helper. |
| `date` | `date` | ISO calendar date; supplies the year. |
| `tranche` | `tranche` | One-hour interval, e.g. `08:00 - 09:00`. |
| `position` | `position` | Source location label, retained without interpreting road references. |
| `coordonnees_geographique` | `coordonnées géographique` | CSV latitude, longitude pair; converted to a GeoJSON longitude, latitude point. |
| `nbpassage` | `nbPassage` | Nonnegative integer hourly count. |

Both field identifiers and French labels are accepted. The CSV reader uses UTF-8,
semicolons, optional BOM and standard quoted fields. Camera ID, date, interval
and count headers are required. Coordinates assign a site; when absent, the
camera must have exactly one registered site. Other location columns may be absent. Duplicate
normalized headers and malformed records fail with an `IOException` rather
than silently producing incomplete output.

## Hourly and daily aggregation

The key of an observation is registered site ID, date and hour. A camera that
changes sites has separate hourly and yearly aggregates at each site, including
on the same date. Partial days at different sites never complete each other. Identical hourly counts
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
getter returns 0 for compatibility. The site/year record and its partial
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
- `firstDate`, `lastDate`: represented date range within the site/year.
- `daysWithoutRecords`: calendar gaps strictly inside that range.

Missing dates before/after that range are not inferred: the source does not
establish that every camera operated throughout the calendar year. A truncated
file cannot establish full-dataset coverage, and a parser cannot detect cameras
or years entirely absent from its input.

## Location and remaining fields

The bundled `parsers/src/main/resources/nim-sites.json` is the authoritative
registry of every known camera/site pair. A row matches a site using only its
camera ID and geographic distance of at most 50 metres from that site's fixed
reference coordinates. Position names, commune names and INSEE codes are
metadata, not matching keys. References never drift as new coordinates arrive.
Returning to a known site reuses its `codeCentrale` across years. Unknown
cameras or coordinates are collected and reported together in an `IOException`
after the input is read. Missing coordinates use the sole registered site for
that camera, with a warning; zero or multiple possible sites block parsing. A point within
50 metres of multiple reference sites is rejected as ambiguous.

Output geometry uses the registered reference point. The latest observation
within each site/year supplies the position label, commune and INSEE code.
Metadata ties use date, hour, then the location's textual order, so CSV row order
does not affect the result. The default display name is `position - commune`,
falling back to the available label or site ID. Display-name overrides use the
site ID, e.g. `NIM_43_S02`.

## Updating the site registry

Run `org.traficinfo.parsers.nim.NIMSiteRegistryUpdater` from the main sources,
with these arguments from the repository root:

```text
/Users/aquillau/Downloads/NIM/NIM_Comptages-2020-2021.csv parsers/src/main/resources/nim-sites.json
```

The first argument is any local NIM CSV; the optional second argument is the
registry path shown above. A missing registry starts a new JSON array. An
existing registry is read and extended, preserving all previous site IDs,
reference points and sites absent from the new input. New sites receive the
next unused suffix for their camera. Labels and date ranges are accumulated.
For initial discovery, distinct observations are sorted by camera ID, first
observation date, coordinates and location text, making assignment independent
of CSV row order. Reimporting a CSV leaves the file unchanged.

Each registry record contains:

- `codeCentrale`, `cameraId`, `latitude`, `longitude`: stable identity and fixed
  reference point.
- `cameraMoved`: true on every site of a camera with more than one registered
  site. This flags multiple locations in the data, not a verified physical move.
- `firstSeen`, `lastSeen`: inclusive source observation dates across imports.
- `positions`, `inseeCodes`, `communes`: observed descriptive metadata for review.

The updater reads the entire CSV and validates the assignments before replacing
the registry through a temporary file. Malformed data or ambiguous assignments leave the existing file intact. All
coordinate-bearing observations are processed first; missing coordinates then
use the sole site for that camera, including one discovered in the same CSV.
These inferred assignments are logged. A camera with no coordinates anywhere
and no registered site still fails. It does not aggregate or
validate hourly counts. Rows dated 1970 are ignored; other dates are retained
as supplied, even if outside the years in the CSV filename.

Review and commit the registry, then rebuild/redeploy preprocessing to include
it as a read-only classpath resource. Production parsing never updates it.
Unknown sites prevent yearly Objects and the manifest from being published;
run the updater on the downloaded CSV, review its changes, rebuild and retry.

The initial registry was generated only from `NIM_Comptages-2020-2021.csv`:
53 sites, 50 cameras, with cameras 43, 51 and 593 marked as having multiple sites.
The 2022 export was then imported: 75 sites, 68 cameras. Its 74 rows without
coordinates were assigned to camera 68's sole site, `NIM_68_S01`. Cameras 43,
51, 59, 593, 60 and 63 have multiple registered sites after that import.
Importing `NIM_Comptages.csv` then brought the registry to 82 sites and 73 cameras,
adding cameras 2, 85, 89, 891 and 892. Cameras 2 and 57 were additionally flagged
as having multiple sites. All existing IDs and reference coordinates were preserved.

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
- A summary for every site/year and every camera, reporting observed days
  and usable hours. Per-camera coverage combines all retained years.
- A file summary including distinct camera IDs encountered, distinct cameras
  retained, site/year count, excluded conflicting hours, input rows and ignored
  1970 rows. Aggregate observed-day totals count site/dates; per-camera day
  coverage deduplicates dates when that camera measures multiple sites.
- Average observed hours per day, per camera and overall: usable hours divided
  by represented dates (site/dates for the overall summary). Entirely absent dates are excluded from this average.

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
the manual program below. Before site grouping was introduced, a full local `NIM.csv` run processed 470,434 rows,
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
whole-day gaps, site/year and camera coverage summaries, and the file summary. Parsing finishes
before the JSON output is opened, so a rejected source leaves an existing output
untouched if parsing fails. The program uses the default `EXCLUDE_HOUR` policy: conflicting
counts are logged, excluded from totals and counted in `conflictingHours`.
Days containing them are incomplete.

## Manual location audit

Run `org.traficinfo.parsers.nim.NIMLocationAuditProgram` from the test sources.
It reads `NIM_Comptages-2020-2021.csv`, `NIM_Comptages-2022.csv` and
`NIM_Comptages.csv` from `~/Downloads/NIM`. Optional arguments are an input
directory and an output report path; the default report is
`nim-location-audit.csv` in the working directory.

The program examines every distinct pair of locations for each camera across
all three files, reusing the parser's field handling and shared geographic distance calculation.
It prints distinct camera counts and IDs for accepted coordinate differences,
relocation candidates (distance over 50 metres), and other label/code or
coordinate-presence changes. These groups can overlap per camera. A relocation
candidate is a source discrepancy to investigate, not confirmation of a physical
move. Location pairs are not counted as chronological relocation events.

The CSV includes distances, both full locations, observed date ranges, source
files and exact dates when both variants appear. Pairwise same-date coordinate conflicts are reported separately from changes
between dates. This exploratory audit does not resolve registry assignments;
production parsing separates known sites, including on the same date. Label-only
changes within tolerance are informational and no longer reject a row. Repeated
hourly rows and overlapping files do not multiply camera or shared-date counts.
Rows dated 1970 are ignored, matching the parser. The audit validates camera IDs,
dates and locations; it does not validate or aggregate hourly counts. Input files
are read only, and the audit never changes the site registry.
