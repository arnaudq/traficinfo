# ONISR accident characteristics

Source: [annual BAAC accident files, 2005–2025](https://www.data.gouv.fr/datasets/bases-de-donnees-annuelles-des-accidents-corporels-de-la-circulation-routiere-annees-de-2005-a-2025),
published by the Ministry of the Interior and administered by ONISR. The
[BAAC data dictionary](https://www.data.gouv.fr/api/1/datasets/r/21e46f4e-fa29-40bb-9ec8-905e41e041aa)
describes characteristics, locations, vehicles and users, joined by `Num_Acc`.
The characteristics parser reads accident records. The participant parser joins
the modern usagers files for involved people and their injury severity. The vehicle
parser joins the modern vehicles files for vehicle categories and motorization.
The manual function currently selects the 2024 and 2025 publications through
`ONISRYearInputs.configured()`.

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
  `getStreetName()` exposes the original `adr` separately. OSM requests carry it
  as `sourceStreetName`; the hourly OSM function normalizes names such as
  `JEAN JAURES (AVENUE)` to compare with `Avenue Jean Jaurès`.
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

## Participants from modern usagers files

`AccidentParticipant` has an immutable `ONISRAccidentParticipant` implementation.
Each accident exposes a `participants` list; an uninjured involved person is also
a participant. The parser uses `Num_Acc` only as the join key, without duplicating
it inside nested participant JSON. Only these six fields are retained:

| Source column | Model field | Representation |
| --- | --- | --- |
| `id_usager` | `participantId` | Required string ID |
| `id_vehicule` | `vehicleId` | Nullable string ID; for pedestrians, references the striking vehicle |
| `catu` | `role` | `DRIVER`, `PASSENGER`, `PEDESTRIAN`, `UNKNOWN` |
| `grav` | `injurySeverity` | `UNINJURED`, `KILLED`, `HOSPITALIZED`, `LIGHTLY_INJURED`, `UNKNOWN` |
| `an_nais` | `birthYear` | Nullable integer |
| `sexe` | `sex` | `MALE`, `FEMALE`, `UNKNOWN` |

The participant model has no `sourceFields` map. `num_veh`, `place`, `trajet`,
`secu1`, `secu2`, `secu3`, `locp`, `actp` and `etatp` are discarded. The existing
characteristics `sourceFields` map contains no usagers data.

`ONISRAccidentParticipantParser.parseParticipants(reader, accidentIds, year)`
reads semicolon-separated UTF-8 usagers data and returns participant lists keyed
by accident ID. Membership in the filtered accident-ID set is checked before
validating row width or parsing any participant field. Unselected rows are never
retained, including their IDs, codes and metadata. An empty selection retains
nothing. CSV syntax and the shared header still need to be readable. The caller
owns the reader.

Participant and vehicle IDs are strings. Whitespace, including nonbreaking and
narrow nonbreaking digit separators, is removed consistently; leading zeroes are
preserved. Duplicate normalized participant IDs in the selected set fail parsing.
Missing or unrecognized numeric role, injury and sex codes map to `UNKNOWN`;
malformed nonnumeric values fail. Blank, dot, `N/A`, zero and `-1` birth years map
to null; other birth years must be positive and no later than the accident year.
Severity code `2` means killed, `3` hospitalized, `4` lightly injured, and `1`
uninjured: numeric codes and enum ordering must not be used as a severity ranking.

Participants are sorted by ID for deterministic storage. Importing characteristics
without a usagers companion yields empty participant lists, as does reading older
stored JSON without a `participants` property. Refresh reads the stored lists and
does not require the raw usagers file.

## Vehicles from modern vehicles files

`AccidentVehicle` has an immutable `ONISRAccidentVehicle` implementation. Vehicles
are stored in an accident's `vehicles` array, alongside `participants`. Retained
source columns are:

| Source column | Model field | Representation |
| --- | --- | --- |
| `id_vehicule` | `vehicleId` | Required normalized string, using the same normalization as participants |
| `catv` | `categoryCode` | Nullable exact BAAC numeric category |
| `motor` | `motorization` | `COMBUSTION`, `HYBRID`, `ELECTRIC`, `HYDROGEN`, `HUMAN`, `OTHER`, `UNKNOWN` |

`type` is derived from the category for convenient filtering. For example, `1`
means `BICYCLE`, `80` `ELECTRIC_BICYCLE`, `7` `CAR`, `31`/`33` `MOTORCYCLE`, and
`30`/`32`/`34` `SCOOTER`. Codes `50` and `60` distinguish motorized and nonmotorized
personal mobility. Remaining documented categories cover light commercial and
heavy goods vehicles, buses, coaches, trains, trams, quadricycles, three-wheelers,
agricultural and special vehicles, and other vehicles. Unknown positive category
codes are retained with type `UNKNOWN`; missing, zero and `-1` categories become
null with type `UNKNOWN`. Motorization codes `1` through `6` map in the listed
order, with missing or unrecognized numeric values represented as `UNKNOWN`.

`Num_Acc` is used only as the join key. `num_veh`, `senc`, `obs`, `obsm`, `choc`,
`manv` and `occutc` are discarded. There is no vehicle `sourceFields` map. No
vehicle information is duplicated into each participant.

`ONISRAccidentVehicleParser.parseVehicles(reader, accidentIds, year)` filters
rows by the same retained accident-ID set before parsing or validating vehicle
fields. It handles the published quoted `id_vehicule` header with an embedded
newline, optional UTF-8 BOM and Unicode whitespace in IDs. Duplicate selected
vehicle IDs and malformed retained numeric fields fail parsing. Unselected row
fields are not processed or retained. The caller owns the reader.

When a vehicle companion is imported, every nonnull participant vehicle reference
must resolve to a vehicle in the same accident, including pedestrian references
to the vehicle that struck them. Parsing and join validation finish before any
yearly record is replaced. Reading older JSON without `vehicles` yields an empty
list; older characteristics/participant-only import overloads remain available.
Reimport replaces vehicle lists, and refresh retains stored vehicles without the
raw file.

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
`sourceFields` for inspection. The parser does not snap coordinates or match streets; candidate-name comparison
is performed later by the hourly OSM function.

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
