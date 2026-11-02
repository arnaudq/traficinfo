# Shared datasource storage contract, version 1

This contract applies to all datasource IDs in `dsconfig.json`. It defines source retrieval, yearly preprocessing and manifest publication.
GeoJSON consumption of the manifest remains a separate step.

Data is stored in Object Storage, with one bucket per data source. In this
specification, Object refers to an item stored in that bucket.

A datasource such as NIM or DPT34 can contain multiple datasets. Each dataset
has a `datasetId`, such as `nim-2022` or `nim-2023-2024`; one dataset is actively
monitored at a time. Datasource branding uses the top-level `dataSourceLogoUrl`
and optional `dataSourceFaviconUrl`. The `datasets` map is keyed by dataset ID;
each entry holds its `datasetInfoUrl`, `inputs`, and `years`. The datasource's
`activeDatasetId` selects the dataset currently monitored.

## Operating assumptions

These assumptions govern the normal retrieval-to-preprocessing workflow:

1. Retrieval is scheduled at most once a day per datasource. Consecutive
   retrievals for the same datasource never overlap.
2. Input events arrive in order and are not misrouted.
3. Retrieval samples upstream input files at an arbitrary rate. Input versions
   are snapshots, not a sequential changelog; missing intermediate versions is
   acceptable and there is no requirement to process every version.
4. If retrieval fails at any point, it is retried at the next scheduled
   occurrence. Immediate retry or recovery of every missed input version is
   unnecessary.
5. All files of a multi-file datasource change together. Companion-only updates
   are not a scenario the design needs to handle.
6. Preprocessing's existing comparison of yearly data and the final manifest
   detects unchanged output and avoids replacing the manifest. Repeating parsing
   is acceptable; no up-front processed-revision check or checkpoint is required.
7. Multi-file transfers are not atomic and can temporarily expose mixed input
   versions. This is acceptable given the nature of the service; preventing it
   does not require an all-input completion barrier or snapshot protocol.

Lost events, downstream failures, cross-stage deployment drift and overlaps
involving preprocessing are outside the scope of this trigger change. No new
mechanisms are required for those scenarios. Preserve existing preprocessing
validation, concurrency handling and manifest publication behavior.

### Event wiring

Use create/update events on an existing raw input Object to invoke preprocessing.
Watch the last configured retrieval input; for MMM this is the counts CSV,
already written after its companion JSON. Retrieval already skips unchanged
inputs, so no new change-detection or notification write is needed in that stage.

The preprocessing entry point accepts the Object Storage event and resolves the
datasource and settings from the bundled configuration. Its existing publisher
parses, validates and compares output, retaining an unchanged manifest. A second
event rule watches `<id>_Manifest.json` and invokes GeoJSON generation.

## Objects

Object names are relative to the datasource's configured input bucket. Use the
configured datasource ID, including MMM despite its legacy bucket/function names.

| Object | Contents |
| --- | --- |
| `<datasourceId>_Manifest.json` | Current complete yearly publication |
| `<datasourceId>_Preprocessing.lock` | Exclusive publication lock |
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
- `activeDatasetId`: the dataset currently monitored.
- `datasets`: upstream datasets, retained after monitoring switches.
- `years`: exact Object references, checksums, record counts and source/parser
  information for every year available to GeoJSON generation.

Each dataset has a `datasetUrl`, explicit `years` scope and named `inputs`.
An input has a retrieval `url`, `format` (CSV or GEOJSON in the examples), and
`headers`: the ordered parsed CSV column names, or null for non-CSV input.
MMM can therefore have counts and companion geometry within one dataset.
Headers live directly in the manifest; no separate headers Object is needed.

Each year entry has `object`, `sha256`, `recordCount`, `datasetId` and
`parserVersion`. For example, NIM's 2022 Object can remain at
`data/r0001/NIM_Comptage_2022.json` while 2023 and 2024 refer to `data/r0002/`.
Switching the active dataset never removes years from inactive datasets.

## Yearly arrays

Each yearly Object contains an array of Comptage records for its named year.
`ComptageJson` serializes the existing source-specific implementations by stored
field name, including null values. Derived getter aliases such as NIM's
`moyenneJourAnnuelle` are excluded, preserving the distinction between a null
`dailyAverage` and zero. MMM records include geometry from the companion input.
Before storage, a LineString becomes a Point halfway along its total horizontal
geographic length: sum haversine segment lengths, then interpolate longitude and
latitude within the segment containing half that length. This keeps the Point on
the original segment; vertices are not averaged. Repeated positions are skipped,
and a zero-length line becomes its repeated position. Lines with fewer than two
positions or invalid longitude/latitude fail preprocessing. Other geometry types
are preserved.
MMM companion exports can repeat a station's geometry for several years or
channels. Identical geometries for the same identifier are accepted; missing
identifiers/geometries and conflicting geometries for one identifier are rejected.
There is no additional count model or separate retrieval provenance Object.

Properties and array records use deterministic ordering. Header order is retained
in the manifest; reordering data rows alone does not change yearly checksums.
The datasource ID selects the concrete count class when reading. `parserVersion`
is maintained in code and must change when the stored interpretation changes.

## Source retrieval configuration

The shared retrieval, preprocessing and GeoJSON functions load bundled `dsconfig.json`.
The invocation selects a datasource by `datasourceId`; caller-supplied URLs,
buckets, inputs and year scopes are not accepted. Input buckets come only from the bundle. All three stages receive `NAMESPACE`
and `USE_RESOURCE_PRINCIPAL` through
runtime configuration. This keeps the full source registry outside OCI's 4096-byte
runtime environment budget. Settings changes require redeploying the shared JAR.

Each input specifies `url`, `contentType` and optional `objectName`. CSV names
default to `<id>_Comptage.csv` or `csvObject`; companion inputs require explicit
names. Inputs are checked in order and preserve existing archival/gzip behavior.
Retrieval writes datasource ID, source URL, dataset ID and download time into
Object metadata. The downloaded dataset ID is informational; validators belong
to the datasource and URL. Changing the active dataset can reuse an unchanged input.

Retrieval replaces only changed inputs. The last configured input's create/update
event invokes shared preprocessing; for MMM this is the counts CSV, written after
its companion JSON. No additional notification Object is written. Normal active
inputs retain the downloader identity metadata used by preprocessing validation.

Each source can specify a runtime timeout of 1–300 seconds; deployment uses the
maximum active timeout for the shared retrieval function. Preprocessing uses 300
seconds. `geoJsonEnabled: false` suppresses only manifest-to-GeoJSON event wiring,
not preprocessing events.

## Processing sequence

1. **Datasource function:** retrieve the configured inputs and store them as
   Objects in the datasource bucket only when changed.
2. **Preprocessing function:** receive the last input Object event, read the current
   source Objects and check CSV
   headers against the manifest for the same dataset/input. Preprocessing permits
   column additions and reordering, but rejects blank or
   duplicate headers and removal/renaming of any previously recorded column.
   Added columns are logged at warning level with datasource ID, dataset ID,
   input Object name and new column names. First publications have no previous
   headers to compare. Extra columns are ignored by the current source parsers.
   A new dataset gets its own headers after successful parsing. On the first
   run, check the parser's required columns because no manifest exists yet.
3. Parse and validate, then write yearly Objects under a new revision. Missing
   inputs must fail validation rather than erase published data. Years outside
   the configured scope are skipped and logged as a distinct set at INFO level.
   Configured years without records are also logged at INFO level; their existing
   yearly Objects remain referenced. Only years within the dataset's scope change.
4. Verify the complete referenced Object set, then publish the manifest last.
5. **GeoJSON function:** trigger from that manifest. Read it once and
   follow its exact Object references instead of listing Objects by wildcard.

## Workflow diagram

Create/update events on the last configured raw input and on the published
manifest drive the pipeline. Archives do not match the input rule. Manual
preprocessing requests use the same publication path.

```mermaid
flowchart LR
    schedule[Schedule or manual request] --> retrieval[Shared retrieval]
    retrieval --> inputs[Write changed raw inputs in order]
    inputs -->|Last input create/update event| preprocess[Shared preprocessing]
    preprocess --> parse[Read inputs, parse and validate]
    parse --> years[Write changed immutable yearly Objects]
    years --> manifest[Verify and publish manifest only if changed]
    manifest -->|Manifest create/update event| geojson[GeoJSON: read exact manifest references]
```

A preprocessing error stops publication and releases the acquired lock during
normal cleanup. The previous manifest is retained; yearly Objects written before
the failure can remain unreferenced. A terminated invocation can leave a lock,
as described below.

## Preprocessing configuration and execution

One shared `PreprocessingFunction` resolves its active settings from the bundle.
Normal requests are `{"datasourceId":"NIM"}`. Object Storage events resolve the
datasource using the configured bucket and last input Object name.
Events read current inputs; every intermediate version
need not be processed. Manual invocation also applies configuration or parser
changes without requiring an input update.

Each invocation parses the inputs and uses the existing yearly-data and manifest
comparison to avoid republishing unchanged output. There is no processed-revision
checkpoint or separate forced-reprocessing mode.

The shared `PreprocessingFunction` supports all current datasource IDs. NIM’s
active dataset covers `[2023, 2024, 2025]`; DPT30 uses `[2021, 2022, 2023, 2024, 2025]`.
Every configured dataset declares its own expected years before invoking this
stage. The scope may expand. Reducing the scope or changing input Object names for
an existing dataset requires a new `datasetId`. Records outside the configured
scope are skipped, with their distinct years logged at INFO level. Configured
years with no records are also logged as a set at INFO level and do not prevent
publication. Such years retain their previous Object references, if any; no empty
yearly Objects are written. A first publication with no in-scope records can have
an empty manifest `years` map, while the dataset retains its configured scope.
NIM's established hourly aggregation, 1970 exclusion and conflicting-hour policy
apply. Other sources retain their existing parser's annual/campaign semantics.
No OSM enrichment or GeoJSON filtering occurs during preprocessing.

Normal active-dataset inputs must carry matching downloader metadata (datasource ID and source
URL) and a download time. Dataset ID metadata is not checked: the same downloaded
CSV can be processed under multiple datasets with different year scopes. Legacy
inputs in this normal mode must be refreshed by the datasource function first. Inputs are staged locally and their metadata is checked again
before publication; a concurrent replacement aborts publication. This detects
changes during a run, but does not turn multi-input retrieval into an atomic
upstream snapshot.

### One-off archive requests

Archive requests select an inactive dataset from the bundle:

```json
{"datasourceId":"MMM","datasetId":"mmm-2022-2024"}
```

`scripts/invoke.sh preprocess <datasourceId> <archiveDatasetId>` validates the
local entry and sends these identifiers. It does not change the active selection.
The function rejects the active dataset ID and any archive input name shared
with active inputs. Archive Objects require explicit names. Unknown fields and
malformed requests fail; bucket, namespace, URLs and years come only from the
bundle.

In archive mode only, missing downloader identity and download-time metadata are
allowed; present datasource IDs and source URLs must match. The explicitly
selected settings assert provenance where legacy metadata is absent. Raw archive
Objects and their metadata are never rewritten. The existing manifest records
the imported dataset, original URLs and exact named inputs; input hashes are
logged, and yearly output checksums remain unchanged in format. No manifest
schema extension is needed.

Archives must contain records for every configured year. CSV/header, parsing,
geometry and yearly readback checks remain in force. Before publication the
publisher rechecks source metadata and compares SHA-256 hashes of each staged
uncompressed source body with a fresh read, detecting content replacement even
when legacy metadata stays empty. This adds source reads, not remote publisher
downloads, and is a validation check rather than an atomic upstream snapshot.

An archive import retains the deployed active dataset ID in the manifest. If
that dataset has not been published, its configured definition is registered
without reading its source Objects, with CSV headers initially null. Normal
preprocessing populates those headers when it first processes the active source.
If an active definition already exists, it is preserved; scope reduction still
requires a new dataset ID. Other datasets and years are retained by the same
publication rules. Retries of unchanged imports are no-ops, and both modes use
the same exclusive lock and publish the manifest last. A changed manifest also triggers GeoJSON generation after an archive import
when the manifest event rule is enabled.

Publication is serialized per datasource using atomic create-if-absent of the
lock Object. There are no ETag-based updates. Normal completion or failure
releases the lock. A terminated invocation can leave a lock: inspect its owner
and UTC start time, confirm no publisher is running, then remove it manually.
Locks never expire automatically, avoiding races with slow invocations.

Each changed year is written under a fresh UUID revision, using create-if-absent.
Unchanged years retain their paths. The function reads back every referenced
yearly Object, including inactive datasets' years, checking checksum, record
count and year before replacing the manifest last. Identical retries leave the
manifest unchanged. A failed run leaves the previous manifest usable and may
leave unreferenced yearly Objects; automatic cleanup is outside this step.

No-argument deployment includes the shared preprocessing function; `preprocess`
selects it independently. `setup-events.sh` routes each datasource's last active
input Object to that function and each `<id>_Manifest.json` to the shared
`GeoJsonFunction`, unless GeoJSON is disabled. Rules use exact names to exclude
archives, lock Objects and yearly outputs. Deploy functions and enable bucket
events before configuring rules. Existing raw inputs can be processed explicitly
after event wiring.

For the end-user procedure, including the DPT34 period-splitting example, see
[Making past periods read-only](../dsfunctions/README.md#making-past-periods-read-only).

## Shared GeoJSON invocation

Deploy `GeoJsonFunction` once. Manual requests supply `{"datasourceId":"NIM"}`;
create/update events select a datasource using `<id>_Manifest.json`, its bundled
bucket and the configured namespace. Empty requests, unknown IDs, other Object
names and sources with `geoJsonEnabled: false` are rejected before creating clients.

Input buckets come from the bundle. `NAMESPACE`, `USE_RESOURCE_PRINCIPAL`,
`BUCKETNAME_OUT` (default `traficinfo`) and `BUCKETNAME_OSM` (default `osminfo`)
are shared runtime settings. Cache and output names are derived from the datasource
ID. Clients and the OSM provider are local to each invocation; different sources
can run concurrently without sharing mutable cache state. Rendering, yearly
validation, sequential vehicle/bicycle uploads and cache refresh retain their
existing behavior. The shared function has a 300-second timeout.

## Compatibility and examples

Missing or unsupported manifest versions are rejected. The model uses Jackson's
default strict handling of unknown fields and the existing Jackson dependency.

JSON examples live in `parsers/src/test/resources/storage`. The NIM manifest
shows a dataset switch preserving 2022; the MMM example shows companion inputs.
URLs, headers, record counts and checksums are illustrative. Tests retrieve no
remote data and do not verify these example checksums against actual Objects.
