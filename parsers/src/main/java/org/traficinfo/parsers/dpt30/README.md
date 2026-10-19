# Gard departmental traffic counts (DPT30)

Département du Gard publishes
[Les Trafics Moyens Journaliers Annuels mesurés sur le réseau routier départemental du Gard](https://www.data.gouv.fr/datasets/les-trafics-moyens-journaliers-annuels-mesures-sur-le-reseau-routier-departemental-du-gard)
on data.gouv.fr under Licence Ouverte / Open Licence v2.0.
These are annual average daily traffic counts (TMJA), from permanent and
rotating counting sites, for the five latest available years.

## Download and updates

Use the stable [CSV resource URL](https://www.data.gouv.fr/api/1/datasets/r/69584212-8ff0-40d3-b82d-2d5d2890bff7).
It redirects to the current dated CSV on static.data.gouv.fr; follow redirects
rather than pinning a dated file. The parser reads comma-separated UTF-8 CSV,
with or without a BOM, and accepts quoted fields.

On October 19, 2026, the file returned an `ETag`. A conditional GET to the
stable resource URL, following its redirect and sending `If-None-Match`,
returned `304 Not Modified`. A future downloader can retain the returned ETag
after a successful download and use it for subsequent requests. This package
only parses CSV; it does not perform HTTP requests or schedule downloads.

## Identifiers and snapshot checks

The CSV inspected on October 19, 2026 contains 842 records, 14 fields and
194 sites over 2021–2025. Sites do not necessarily have a row for every year.

- `id_tmja` is an annual record ID, for example `1_0_3_0_2021`.
- Every record in this snapshot ends in `_<annee>`. Removing that exact
  trailing suffix yields the site ID `1_0_3_0`.
- `DPT30ComptageParser` preserves the full record ID as `idTmja`, stores the
  year-free value as `siteId`, and exposes `DPT30_1_0_3_0` as `codeCentrale`.
  The prefix prevents collisions with other data sources. All other identifier
  components and leading zeros are preserved without interpreting them.
- Every `(siteId, annee)` pair is unique in the inspected snapshot, and each
  site consistently maps to the same `numero_route`, `pr` and `abs` across years.
- This derivation is based on the observed export; the publisher does not
  document long-term identifier stability. Missing IDs, missing/nonpositive
  years and IDs without the matching year suffix are rejected with `IOException`.

## Parser mapping

`DPT30Comptage` implements `Comptage`, and `DPT30ComptageParser` implements
`ComptageParser` in `org.traficinfo.parsers.dpt30`.

| CSV field | Java property / meaning |
| --- | --- |
| `id_tmja` | `idTmja`; source annual record ID, used to derive `siteId` and `codeCentrale`. |
| `annee` | `annee`; observation year. |
| `numero_route` | `routeId`, for example `D6086`. |
| `pr_abs`, `pr`, `abs` | Ignored. |
| `communes` | `cityName`; may describe more than one commune. |
| `type_comptage` | `typeCompteur`; `Permanent` or `Tournant`. |
| `latitude`, `longitude` | Nullable decimal coordinates; `getGeometry()` creates a GeoJSON point in longitude, latitude order. |
| `tmja_tous_vehicules` | Nullable `mjaTvTcj`, exposed through `getMoyenneJourAnnuelle()`. |
| `tmja_poids_lourds` | Nullable integer `tmjaPoidsLourds`. |
| `pourcentage_tmja_poids_lourds` | Nullable decimal `pourcentageTmjaPoidsLourds`, in percent, not a fraction. |
| `geom` | Ignored; point geometry is derived from latitude/longitude rather than the source WKT. |

The default display name is `D6086 - Pont-Saint-Esprit`.
A missing commune is omitted; missing route text falls back to
`codeCentrale`. Commune names use `StringUtils.toCamelCase`, following the
existing parsers. The properties argument overrides display names using
the prefixed `codeCentrale` as the key.

## Missing values and scope

Blank counts, heavy-vehicle percentages and coordinates remain null. The
shared interface requires an integer daily average, so `getMoyenneJourAnnuelle()`
returns 0 when `getMjaTvTcj()` is null. Use the nullable getter to distinguish
missing data from a measured zero. Geometry is null if either coordinate is
missing. Heavy-vehicle fields are retained on the model; the shared GeoJSON
output currently uses the all-vehicle daily average.

Records have type `Comptage.Type.VEHICULES`. The source does not provide OSM
way IDs, hourly peaks, V85, mean speed or speed limits. OSM IDs and peaks default
to null, speeds to 0 and speed limits to -1, matching the existing conventions.

The CSV has no explicit direction field. By project choice, the parser assumes
counts combine both directions and sets `bidirectionnelle` to true, displaying
the ⇄ marker. This is an assumption, not a documented source-field mapping.

The parser is registered as `GeoJsonParser.DataSource.DPT30`. The shared
GeoJSON parser can generate one feature per site from CSV coordinates and
attach annual history. Deployment configuration and OCI functions are outside
this package's scope.

## Tests

`DPT30Sample.csv` retains three source rows: two years for one permanent site
and a rotating site with missing heavy-vehicle values. Tests verify station
grouping, traffic fields, coordinate order, BOM and UTF-8 handling, quoted
commas, display overrides, missing values versus zero, invalid identifiers,
and CSV-to-GeoJSON generation with annual history.

Run `mvn -o -pl parsers test` from the repository root.
