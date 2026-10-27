# Shared datasource storage contract, version 1

This contract applies to all datasource IDs in `dsconfig.json`. At this stage
it defines the manifest format and the source retrieval configuration. Yearly
processing and manifest publication are not implemented yet.

Data is stored in Object Storage, with one bucket per data source. In this
specification, Object refers to an item stored in that bucket.

## Objects

Object names are relative to the datasource's configured input bucket. Use the
configured datasource ID, including MMM despite its legacy bucket/function names.

| Object | Contents |
| --- | --- |
| `<datasourceId>_Manifest.json` | Current complete publication |
| `data/<revision>/<datasourceId>_Comptage_<year>.json` | Yearly array of Comptage records |

Revisions are unique path components. Published yearly Objects are immutable:
a later run writes a new path instead of overwriting an Object referenced by the
current manifest. Unchanged years retain their existing paths. Only the current
manifest is replaced.

JSON uses UTF-8 without a BOM. Checksums are SHA-256, represented as 64 lowercase
hexadecimal characters, over the exact uncompressed payload bytes (including
whitespace). Optional `Content-Encoding: gzip` does not change these checksums.
Publication timestamps are ISO 8601 UTC strings ending in `Z`.

## Manifest (`DataManifest`)

- `manifestVersion`: manifest format version, currently 1.
- `dataSourceId`, `revision`, `publishedAt`: publication identity.
- `activeResourceId`: the one resource group currently monitored.
- `resources`: upstream resource groups, retained after monitoring switches.
- `years`: exact Object references, checksums, record counts and source/parser
  information for every year available to GeoJSON generation.

Each resource has a `datasetUrl`, explicit `years` scope and named `inputs`.
An input has a retrieval `url`, `format` (CSV or GEOJSON in the examples), and
`headers`: the ordered parsed CSV column names, or null for non-CSV input.
MMM can therefore have counts and companion geometry within one resource group.
Headers live directly in the manifest; no separate headers Object is needed.

Each year entry has `object`, `sha256`, `recordCount`, `resourceId` and
`parserVersion`. For example, NIM's 2022 Object can remain at
`data/r0001/NIM_Comptage_2022.json` while 2023 and 2024 refer to `data/r0002/`.
Switching the active resource never removes years from inactive resources.

## Yearly arrays

Each yearly Object contains an array of Comptage records for its named year.
Serialization of the existing source-specific Comptage implementations will be
addressed when implementing yearly Objects. No additional count model or separate retrieval
provenance Object is introduced in this step.

## Source retrieval configuration

The datasource function downloads the active source inputs into its bucket. It
neither reads nor writes the manifest. Deployment reads the datasource's `source`
entry in `dsconfig.json` and passes `dataSourceId`, `resourceId` and ordered
`inputs` through the `DATA_SOURCE_SETTINGS` function configuration. The function
reads these settings through `RuntimeContext`; it does not read the repository
file at runtime.

Function configuration carries only active retrieval settings; CSV headers and
manifest history remain in Object Storage. Deployment enforces OCI's 4096-byte
UTF-8 key/value budget, including inherited application settings, before changing
any function. Function-level values override application values with the same key.

Each input specifies `url`, `contentType` and an optional `objectName`. CSV names
default to `<datasourceId>_Comptage.csv` (or the configured `csvObject` override).
Companion inputs specify their Object names explicitly. Inputs are published in
list order, so MMM's geometry JSON precedes its counts CSV. Source Objects retain
the existing names and archive convention. A failure stops later inputs; this
stage does not create an atomic snapshot of multiple inputs.

Successful retrieval stores the datasource ID, resource ID, source URL and UTC
download time in Object metadata. Conditional downloads reuse validators only
for the same source URL, resource and datasource. Older Objects without these
fields are refreshed once. Switching resources therefore cannot reuse the old
resource's validator. All datasource handlers require `DATA_SOURCE_SETTINGS`
and `BUCKETNAME`; missing settings fail before any clients are created. Retrieval
URLs and resource IDs are configured only in `dsconfig.json`, with no Java
fallbacks. Each handler supplies only its datasource ID to the shared retrieval
superclass. `CsvDataSource` retains only GeoJSON parser selection and naming.

The optional `source.timeoutSeconds` sets the download function's synchronous
runtime timeout (1–300 seconds). Deployment applies it on creation and updates;
invocation allows an additional 60 seconds when it exceeds the default 30. NIM
uses 300 seconds for its 2022 export. `geoJsonEnabled: false` marks a datasource
as download-only: event setup skips it and GeoJSON invocation/deployment is
disabled until processing is implemented. Other sources remain enabled by default.

## Processing sequence

1. **Datasource function:** retrieve the configured inputs and store them as
   Objects in the datasource bucket.
2. **Preprocessing function (later):** read the source Objects and check CSV
   headers against the manifest for the same resource/input. Parsing by name may tolerate reordering; missing, renamed or duplicate required columns
   fail processing. Extra columns follow the source parser's documented policy.
   A new resource gets its own headers after successful parsing. On the first
   run, check the parser's required columns because no manifest exists yet.
3. Parse and validate, then write yearly Objects under a new revision. Missing
   expected inputs/years must fail validation rather than erase published data.
   Only years within the resource's scope change.
4. Verify the complete referenced Object set, then publish the manifest last.
5. **GeoJSON function (later):** trigger from that manifest. Read it once and
   follow its exact Object references instead of listing Objects by wildcard.

Serialize publication per datasource and make retries idempotent. A failed run
leaves the previous manifest usable. Each source's aggregation rules must address
hourly completeness and counting campaigns before its first run. Header validation,
yearly serialization and manifest publication will be implemented in the preprocessing stage. Deployment will also supply its source
settings; this configuration wiring is still to be implemented for preprocessing.

## Compatibility and examples

Missing or unsupported manifest versions are rejected. The model uses Jackson's
default strict handling of unknown fields and the existing Jackson dependency.

JSON examples live in `parsers/src/test/resources/storage`. The NIM manifest
shows a resource switch preserving 2022; the MMM example shows companion inputs.
URLs, headers, record counts and checksums are illustrative. Tests retrieve no
remote data and do not verify these example checksums against actual Objects.
