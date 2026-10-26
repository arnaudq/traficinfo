# Shared datasource storage contract, version 1

This contract applies to all datasource IDs in `dsconfig.json`. Its Java models
live in `org.traficinfo.parsers.storage`, shared by parsers and functions.
Only formats and examples are defined at this stage; deployed behaviour is unchanged.

## Objects

Object names are relative to the datasource's configured input bucket. Use the
configured datasource ID, including MMM despite its legacy bucket/function names.

| Object | Contents |
| --- | --- |
| `<id>_Manifest.json` | Current complete publication |
| `data/<revision>/<id>_Comptage_<year>.json` | Normalized yearly count array |
| `raw/<importId>/<inputId>.<extension>` | Retrieved source payload |
| `imports/<importId>.json` | Retrieval provenance |

Revisions and import IDs are unique, opaque path components. Published yearly
objects are immutable: a later import writes a new path instead of overwriting
an object referenced by the current manifest. Unchanged years retain their
existing paths. Only the current manifest is replaced.

JSON uses UTF-8 without a BOM. Checksums are SHA-256, represented as 64 lowercase
hexadecimal characters, over the exact uncompressed payload bytes (including
whitespace). Optional `Content-Encoding: gzip` does not change these checksums.
Timestamps are ISO 8601 UTC strings ending in `Z`.

## Manifest (`DataManifest`)

- `manifestVersion`: envelope format version, currently 1.
- `dataSourceId`, `revision`, `publishedAt`: publication identity.
- `comptageSchemaVersion`: yearly record format version, currently 1.
- `activeResourceId`: the one resource group currently monitored.
- `resources`: upstream resource groups, retained after monitoring switches.
- `years`: exact object references, checksums, record counts and provenance for
  every year available to GeoJSON generation.

Each resource has a `datasetUrl`, explicit `years` scope and named `inputs`.
An input has a retrieval `url`, `format` (CSV or GEOJSON in the examples), and
`headers`: the ordered parsed CSV column names, or null for non-CSV input.
MMM can therefore have counts and companion geometry within one resource group.
Headers live directly in the manifest; no separate headers file is needed.

Each year entry has `object`, `sha256`, `recordCount`, `resourceId`, `parserVersion`
and `importId`. For example, NIM's 2022 object can remain at
`data/r0001/NIM_Comptage_2022.json` while 2023 and 2024 refer to `data/r0002/`.
Switching the active resource never removes years from inactive resources.

## Yearly arrays (`NormalizedComptage`)

Each file contains only records for its named year. The manifest supplies its
schema version. Fields follow the current `Comptage` model:

- Identity: `codeCentrale`, `annee`, `discriminator`.
- Coverage: `directionType` and `type`, using the existing enums.
- Source location: `displayName`, `cityName`, `roadReference`, GeoJSON `geometry`.
- Measurements: `moyenneJourAnnuelle`, `moyenneHeureDePointeMatin`,
  `moyenneHeureDePointeSoir`, `v85Speed`, `meanSpeed`.

The discriminator is a stable source-defined string: normally the direction
name, or MMM's channel code. It must include any additional distinction needed
to make station/year/discriminator unique, such as traffic type. Duplicate keys
must fail preprocessing. Unknown numeric values are null; measured zero stays
zero. Source adapters must explicitly translate missing-value conventions.
Peak-hour strings retain existing parser conventions; speeds use km/h.

OSM-derived way IDs and maximum speeds remain outside these source records.
Conversion to/from mutable `Comptage` objects and deterministic serialization
will be implemented separately. Each source's aggregation rules must address
hourly completeness and counting campaigns before its first import.

## Import provenance (`ImportRecord`)

`importVersion` is 1. An import records its datasource, resource, import ID,
retrieval time, parser version and explicit year scope. Each named input records
its actual source URL, raw object path, checksum, optional HTTP validator and
last-modified string, and observed CSV headers (null for non-CSV inputs).
Companion inputs have independent provenance. A completed import requires all
inputs to have been retrieved successfully.

## Processing sequence for later implementation

1. Retrieve all required inputs and record the completed import.
2. Check headers against the manifest for the same resource/input. Parsing by
   name may tolerate reordering; missing, renamed or duplicate required columns
   fail processing. Extra columns follow the source parser's documented policy.
   A new resource gets its own headers after successful parsing.
3. Parse and validate the import, then write normalized yearly objects under a
   new revision. Missing expected inputs/years must fail validation rather than
   erase published data. Only years within the imported resource's scope change.
4. Verify the complete referenced object set, then publish the manifest last.
5. Trigger GeoJSON from that manifest. Read it once and follow its exact object
   references instead of listing objects by wildcard.

Serialize publication per datasource and make retries idempotent. A failed import
leaves the previous manifest usable. Header checks and successful processing
control publication automatically. This contract does not implement storage I/O,
checksum verification, completeness checks or publication orchestration yet.

## Compatibility and examples

Missing or unsupported envelope/schema versions are rejected. Breaking changes
require explicit version support. Models use Jackson's default strict handling
of unknown fields and work with the project's existing Jackson dependency.

JSON examples live in `parsers/src/test/resources/storage`. The NIM manifest
shows a resource switch preserving 2022; the MMM example shows companion inputs.
Records, URLs, headers and raw-input hashes are synthetic. Yearly file checksums
are real and verified by `StorageContractTest`. Tests retrieve no remote data.

Run `mvn -o -pl parsers test` for contract and parser tests.
