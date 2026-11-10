# ONISR accident characteristics

Source: [annual BAAC accident files, 2005–2025](https://www.data.gouv.fr/datasets/bases-de-donnees-annuelles-des-accidents-corporels-de-la-circulation-routiere-annees-de-2005-a-2025),
published by the Ministry of the Interior and administered by ONISR. The
[BAAC data dictionary](https://www.data.gouv.fr/api/1/datasets/r/21e46f4e-fa29-40bb-9ec8-905e41e041aa)
describes characteristics, locations, vehicles and users, joined by `Num_Acc`.
This parser currently reads characteristics only; vehicle types and casualty
counts are not present in that file and are not inferred.

## API and model

`ONISRAccidentParser` implements `AccidentParser`. Its no-argument constructor
reads all departments. Pass `Set.of("30", "34", "76")` to filter the departments
currently used by the project before constructing records. Do not confuse these
departments with French administrative regions. The reader overload leaves the
reader open. The file overload closes its own reader.

`ONISRAccident` implements `Accident`, sharing `GeographicRecord` with `Comptage`:

- `accidentId` is the original twelve-digit `Num_Acc`, retained for companion
  joins. Dates come from `an`, `mois` and `jour`, rather than filenames.
- `date` and `time` use `LocalDate` and nullable `LocalTime`. No timezone is
  inferred. `00:00`/`0` is midnight, not an absent time.
- `regionalId` identifies the source administrative area. For France, it maps
  to the normalized INSEE department code, including leading zeroes and Corsican
  letters. It does not mean a French administrative région.
- `municipalityId` maps to the full normalized INSEE commune code for France.
  It identifies the municipality, not its name or postal code. Postal codes serve
  mail delivery and are not interchangeable with commune codes.
- `address` comes from `adr`. It is not used as an official road reference:
  characteristics does not provide one, so `getRoadReference()` returns null.
- `latitude` and `longitude` are nullable WGS84 decimal degrees. Geometry is a
  point in longitude, latitude order, or null if either coordinate is unavailable.
- `osmWayId` starts null and can be assigned later by the existing cache workflow.
- `lighting`, `builtUpArea`, `intersection`, `atmosphericConditions` and
  `collisionType` expose BAAC numeric codes. Blank, dot, `N/A`, zero and `-1`
  produce null for these optional codes. Meanings follow the publisher's dictionary;
  no vehicle population or casualty severity is derived from collision type.
- `sourceFields` is an immutable map of original column names and cell text,
  including unknown columns, missing-value codes and unconverted coordinates.

The accident model is separate from count registries and count manifest storage.
No cloud access or reverse geocoding happens during parsing. Future departmental
caches use `ONISR_DPT30`, `ONISR_DPT34` and `ONISR_DPT76` prefixes in `osminfo`.

`ONISRAccidentJson` round-trips lists of these source records for local processing
and writes OSM-way-to-accident maps. It stores model fields and original source
columns, with ISO local date/time strings, nullable values and no derived getter
aliases such as `geometry` or `collisionType`. Object properties and source-field
keys have deterministic ordering. Reading rejects malformed/trailing JSON; the
processing layer additionally validates stored region/year scope and identities.
The reader retains ownership of its input stream. See the
[local processor usage](../../../../../../../../dsfunctions/README.md#local-onisr-accident-processing)
for year replacement, output naming and OSM cache handling.

## Source formats

The parser accepts comma- and semicolon-separated headers, quoted fields, an
optional UTF-8 BOM, decimal commas or points, and whitespace around values.
Column matching is case-insensitive. File encoding defaults to Latin-1 for
comma-separated historical files and UTF-8 for semicolon-separated files. Use
`parseAccidents(file, charset)` or the reader overload for re-encoded files.

Historical records before 2019 use:

- A short year such as `5` or `18`, normalized to 2005 or 2018.
- Integer HHMM times, including values with omitted leading zeroes.
- Department codes such as `300`, `340`, `760`, normalized to `30`, `34`, `76`;
  Corsica uses `201`/`202`, normalized to `2A`/`2B`. Overseas codes are retained.
- Local commune numbers, expanded to full INSEE codes. Historical overseas
  commune numbers include the department's final digit before the two local digits.
- Coordinates scaled by 100,000. The historical `gps` area establishes negative
  longitude for Antilles/Guyana (`A`/`G`) and negative latitude for Réunion/Mayotte
  (`R`/`Y`); metropolitan signed longitudes are retained.

Modern records use full years, HH:MM times, INSEE department/commune codes and
decimal-degree coordinates. The 2025 inspected file uses semicolons, UTF-8 and
decimal commas.

Missing, malformed, non-finite or out-of-range coordinates do not discard the
accident. An all-zero coordinate pair is treated as unlocated; a longitude of
zero with a valid nonzero latitude remains usable. Original values remain in
`sourceFields` for inspection. No coordinate snapping or street matching is done.

## Validation and observed snapshots

Required columns are `Num_Acc`, `an`, `mois`, `jour`, `hrmn`, `dep`, `com`, `lat`
and `long`. Empty input, missing/duplicate headers, incorrect row widths, duplicate
selected accident IDs, inconsistent ID/year, invalid dates/times and nonnumeric
optional circumstance codes fail with `IOException`. Blank optional values remain
missing. A valid header with no rows returns an empty list. An unselected
department is skipped before validating its other values.

Complete local reads on 2026-11-10 used the publisher's 2005/2018 files and a local
copy of `caract-2025.csv`:

| Year | All records | DPT30 | DPT34 | DPT76 | Selected records with geometry |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2005 | 87,026 | 1,145 | 1,443 | 1,041 | 646 |
| 2018 | 57,783 | 472 | 770 | 703 | 1,943 |
| 2025 | 55,093 | 426 | 787 | 716 | 1,929 |

`Num_Acc` was unique in each inspected snapshot. These are observations of the
inspected publications, not expected future record counts. Other years have not
yet been checked end to end.
