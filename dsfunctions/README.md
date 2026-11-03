# Data source functions

The module provides shared retrieval, preprocessing and GeoJSON functions,
and an OSM cache refresh function. Retrieval selects its datasource
from each request and loads the source settings from the bundled configuration.

Files are gzip-compressed before upload, keeping their original object names and
content types with `Content-Encoding: gzip`. Compression uses a temporary file
that is deleted after the upload, including on failure. Readers must support
gzip content decoding (or explicitly decompress the downloaded body).
Existing objects remain uncompressed until a changed source file is uploaded;
the source ETag check still skips unchanged files.

The source function checks and publishes `MMM_MMM_Comptage.json` before
`MMM_MMM_Comptage.csv`, so the companion JSON is ready when a CSV upload
triggers downstream processing. A JSON download or upload failure stops the
invocation before the CSV is published and is reported as a function failure.

## Logging

Functions emit one JSON event per line to standard output using SLF4J 2.0.20
and Logback 1.6.6. `src/main/resources/logback.xml` configures the console
appender and INFO threshold. Existing SLF4J calls, including shared parser and
storage logs, use the same encoder; messages and exceptions are JSON-escaped.
The rendered message is in `formattedMessage`, and exception details are in
`throwable`. Events retain one epoch-millisecond `timestamp`; logger-context
metadata, sequence numbers and nanosecond precision are omitted.

During retrieval, preprocessing and GeoJSON invocations, the `mdc` object
contains `datasourceId` and `stage` (`retrieval`, `preprocess`, or `geojson`).
The OSM function uses stage `osminfo` and adds `datasourceId` while processing each
source; cross-source summary logs have no datasource ID. Startup and runtime
logs outside these scopes can also lack datasource context. The context is
restored when a scope exits, including on failure, so reused invocation threads
do not retain the previous source. New background tasks must explicitly propagate
and restore MDC; it is scoped to the executing thread.

This configures Java output only. OCI application logging settings and log
destinations are managed separately.

## Source retrieval configuration

Each datasource in `dsconfig.json` selects an `activeDatasetId` from its
`datasets` map. Each dataset contains `years`, `datasetInfoUrl`, an ordered
`inputs` array, and optionally `timeoutSeconds`. Inactive datasets remain in the
configuration; deployment and normal invocation select only `datasets[activeDatasetId]`.
The selected map key becomes `datasetId` in the runtime settings. Inputs have
`url`, `contentType`, and optionally `objectName`. CSV Object names default to the datasource's `csvObject` naming
convention; MMM's companion JSON keeps its explicit legacy name.

Maven includes the repository `dsconfig.json` as `/dsconfig.json` in the JAR,
without maintaining a second copy. The shared handler
`org.traficinfo.dsfunctions.DataSourceFunction::handleRequest` loads an immutable
registry at startup and accepts only `{"datasourceId":"DPT34"}`-style requests.
Unknown IDs, malformed requests and caller-supplied settings are rejected before
creating clients. Adding a retrieval source using the existing input lifecycle
needs configuration and a new schedule, with no per-source Java wrapper.

Each request resolves the active dataset, ordered inputs and bucket from the
bundle. CSV object names default to `<id>_Comptage.csv`; non-CSV inputs require
explicit names. Input buckets default to `ds<lowercase-id>`, with `inputBucket`
for exceptions such as MMM. The root key must match `id`; IDs use uppercase
letters, digits and underscores. Registry loading validates every active source
and rejects conflicting destination objects. Inactive datasets remain bundled.

Deployment passes `NAMESPACE`. Input buckets come only from `dsconfig.json`;
bucket changes require redeploying the shared functions and updating event rules.
`USE_RESOURCE_PRINCIPAL` defaults to `true`.
Datasource URLs and datasets no longer consume the OCI 4096-byte runtime
configuration budget. That guard still applies to environment settings and
GeoJSON deployments. The shared function timeout is the
maximum active-dataset timeout (default 30 seconds per source), currently 300
seconds. Synchronous retrieval invocation allows that maximum plus 60 seconds.

Clients are created for the selected source inside its logging scope and closed
after every invocation. No mutable source selection survives between requests.
Configuration changes require rebuilding/redeploying the shared JAR; clock
changes require only schedule setup. `setup-schedules.sh` generates
`<id>Retrieval` schedules with minimal BODY payloads. See the root README for
per-source `scheduleClock` overrides and scheduler IAM requirements.

`SourceObjectPublisher` streams changed inputs to the existing current Object
names, with gzip storage and copy-before-replace archival. Each upload records
`datasource-id`, `dataset-id`, `source-url`, `downloaded-at` and the validator.
A validator is reused only when datasource ID and source URL match. Legacy
Objects without that identity are downloaded once to establish it. Changing a
URL forces a fresh download; changing only the dataset ID reuses the same input.
The stored `dataset-id` records which dataset was selected at download time and
is informational. Preprocessing does not require it or compare it to settings,
so a single downloaded CSV can serve several datasets with different year scopes.
Empty downloads and archive failures stop
the invocation before replacement. MMM still publishes its JSON before CSV;
a failure stops subsequent inputs but does not roll back earlier uploads.
Archive dates use the previous download date, including for MMM.

Retrieval does not run preprocessing inline. A create/update event on the last
configured input invokes shared preprocessing. For MMM this is the counts CSV,
written after the companion JSON. Unchanged inputs are not uploaded, so they do
not generate preprocessing events. Archives do not match the event rule.

## Shared infrastructure

`HttpClient`, `ObjectStoreClient` and `OOSResourceMetadata` implement the shared
HTTP and Object Storage lifecycle.
The HTTP client sends `If-None-Match` for real server ETags and returns no body
for HTTP 304. When the server supplies no ETag or a malformed ETag (such as an
unquoted ArcGIS validator), it stages the complete response
in a temporary file and exposes `sha256:<hex-digest>` through `getEtag()`.
It returns no body when this synthetic validator matches the previous value.
Synthetic validators are never sent in `If-None-Match`; their comparison requires
a full download on each invocation. A change of row order or formatting changes
the hash. Both real and synthetic validators use the same `current-etag` object
metadata. Last-Modified remains optional and is not inferred by the HTTP client.
Responses, body streams and temporary files are released when `EntityBody` is
closed. Unchanged downloads and failed downloads also delete temporary files.

## GeoJSON output

`org.traficinfo.dsfunctions.GeoJsonFunction::handleRequest` is deployed once for
all datasource IDs. A manual request supplies `{"datasourceId":"DPT34"}`;
Object Storage create/update events on `<id>_Manifest.json` select the datasource
by manifest name and configured bucket. The function resolves its input bucket
from bundled `dsconfig.json`; `geoJsonEnabled: false` rejects that source.
`GeoJsonDataSource` selects the parser and output/cache names, and
`GeoJsonPublisher` performs publication. Deploy and invoke it with:

```sh
./scripts/deploy-functions.sh geojson
./scripts/invoke.sh geojson DPT34
```

The handler reads `<id>_Manifest.json` and the exact yearly Objects it references,
including retained inactive datasets. It validates checksums, record counts and
years before rendering; it does not read raw retrieval inputs. Both plain and
gzip Objects are supported. Geometry, site identity and annual history come from
the stored records. See the [parser documentation](../parsers/README.md) for
rendering and source-specific interpretation.

Each invocation publishes two gzip FeatureCollections to the output bucket:
`<id>_TraficInfo.geojson` for `VEHICULES` and
`<id>_TraficInfo_BICYCLES.geojson` for `BICYCLES`. Empty populations are published
as empty collections to clear removed counters. Features retain their `type`.
Uploads are sequential: failure of the second output can leave the first updated.
OSM cache refresh runs after both uploads. Manifest, output and cache-write
failures fail the invocation; temporary output files are always removed.

| Runtime setting | Default |
| --- | --- |
| `NAMESPACE` | OCI Object Storage namespace supplied by deployment |
| `BUCKETNAME_OUT` | `traficinfo` |
| `USE_RESOURCE_PRINCIPAL` | `true` |
| `BUCKETNAME_OSM` | `osminfo` |

GeoJSON publishes final feature counts and yearly record counts before/after
filtering through OCI Monitoring. See the
[processing metrics specification](../specs/monitoring.md#traficinfo-metrics) for metric
definitions, runtime settings, IAM permissions and the monitoring abstraction.

Deployment creates or updates `GeoJsonFunction` with a 300-second timeout.
All manifest event rules invoke it. Input/output/OSM clients and the OSM provider
are created and closed per invocation, with `stage=geojson` and `datasourceId`
in the logging context. No datasource-specific state is retained between calls.
Different datasource invocations can execute concurrently. Cache names remain
`<id>_OSMInfo.json` and `<id>_OSMWayMapping.json` in the configured OSM bucket.

## Preprocessing

`org.traficinfo.dsfunctions.PreprocessingFunction::handleRequest` is shared by
all datasource IDs. It reads source Objects from the datasource bucket, checks
headers and expected years, and publishes immutable yearly arrays followed by
`<datasourceId>_Manifest.json`. It preserves years belonging to inactive
datasets and reuses unchanged yearly Objects. It performs no OSM enrichment.
Records outside the configured year scope are skipped and their distinct years
are logged at INFO level. Configured years without records are also logged at
INFO level; existing yearly Objects for those years are retained. With no prior
records, the manifest can contain an empty `years` map.
See the [storage contract](../specs/datasource-storage-contract.md) for the JSON
format, validation rules and failure behavior.

After `source ./scripts/setup.sh`, deploy preprocessing once and invoke any datasource:

```sh
./scripts/deploy-functions.sh preprocess
./scripts/invoke.sh preprocess NIM
```

The function loads the same bundled `dsconfig.json` as retrieval and resolves
inputs, `datasetInfoUrl` and `years` at invocation time. Runtime configuration is
`NAMESPACE` and `USE_RESOURCE_PRINCIPAL`, with
per-invocation clients and a 300-second runtime timeout. No remote publisher
requests are performed by preprocessing.

A manual request is `{"datasourceId":"NIM"}`. OCI Object Storage events select
the datasource by its bucket and last configured input Object name. Each
invocation reads current inputs, parses and validates them, then compares the
yearly output and manifest with the previous publication. Unchanged output leaves
the manifest untouched. Manual invocation can apply configuration or parser
changes without waiting for an input update; no forced mode is needed.

NIM’s active dataset uses `[2023, 2024, 2025]`; DPT30 uses `[2021, 2022, 2023, 2024, 2025]`, matching
the available annual records. Every dataset defines its explicit expected year
scope; MMM also requires its companion geometry input. Input names are used as named
manifest inputs. Preprocessing converts companion LineStrings to Points halfway
along their total geographic length, interpolating within the original segment.
Other geometry types are preserved. Rerun MMM preprocessing to apply this change
to the configured years; years outside that scope retain their published Objects.
A dataset may expand its year scope;
use a new `datasetId` to reduce the scope or change input Object names.

`setup-events.sh` connects the last raw input's create/update event to
preprocessing and the changed manifest to GeoJSON. There is no preprocessing
schedule tag. Deploy the shared functions before configuring rules. Invoke
preprocessing explicitly to process inputs uploaded before event wiring.

Publication uses `<datasourceId>_Preprocessing.lock`, created atomically and
released on normal completion or failure. A terminated invocation can leave a
lock. Inspect its owner/start time and confirm the previous publisher has stopped
before deleting it manually. Function permissions need Object read, create,
update and delete access within the datasource bucket. Failed runs can leave
unreferenced yearly Objects; cleanup is not automatic.

### Input formats

Preprocessing selects counts inputs by content type: `text/csv` for CSV sources
and `application/geo+json` for GeoJSON sources. CSV inputs retain their required
header checks. MMM additionally joins its companion geometry by counter ID;
companion LineStrings become midpoint Points during preprocessing.

DPT76 reads one GeoJSON counts input and no companion CSV. It records `GEOJSON`
with no CSV headers in the manifest, preserves FID-based identities and WGS84
geometry, and rejects malformed, duplicate-FID or explicitly truncated input
before replacing a publication. Records without geometry or counts remain in
stored yearly data; GeoJSON output filters ineligible map features. The shared
downloader does not paginate ArcGIS queries.

For NIM, displayed daily averages use observed complete days rather than an
extrapolated full-year TMJA. Source interpretation and normalization are described
in the [parser READMEs](../parsers/README.md).

### Importing existing archives once

Pass an inactive dataset ID as the third argument to preprocessing. The script
validates that entry locally and sends only `datasourceId` and `datasetId`.
The shared function resolves the named inactive dataset from its own bundled
configuration. No publisher download or function configuration update is performed. Archive inputs must have
explicit `objectName` values and must be different from the active input Objects.

For MMM, keep `activeDatasetId` set to `current` (years 2025–2026), then deploy
the updated preprocessing handler once and import the two historical datasets:

```sh
# After source ./scripts/setup.sh
./scripts/deploy-functions.sh preprocess
./scripts/invoke.sh preprocess MMM mmm-2011-2021
./scripts/invoke.sh preprocess MMM mmm-2022-2024
```

These read the October 31, 2022 and September 15, 2025 archive pairs in
`ds3mbucket`, respectively. They work even before current source inputs have
been downloaded. The manifest retains `activeDatasetId: current`, registers the
active dataset definition if necessary, and preserves years outside each import's
scope. Current CSV header history is populated when current preprocessing runs.
The import records its dataset ID, original source URLs and exact archive Object
names in the existing manifest format; yearly records retain their checksums.

Legacy archives may lack `datasource-id`, `source-url` and `downloaded-at`
metadata. Only this explicit import mode permits those missing fields. Any
present datasource ID or source URL must still match the selected settings.
The archive's existing `dataset-id` is informational. No metadata is added to
or changed on the raw archive Objects. Their source identity comes from the
explicitly selected dataset when legacy identity metadata is missing.

Preprocessing validates CSV headers, parsing and MMM geometry matches, and
requires records for every configured import year. It rechecks metadata and
SHA-256 hashes of both uncompressed source bodies before publishing the manifest;
an archive replacement with unchanged metadata is therefore also detected.
Input Object names and hashes are logged. Failures retain the previous manifest;
immutable yearly Objects written before a failure may remain unreferenced.
Repeating an unchanged successful import does not republish the manifest.
Companion exports may repeat identical station geometries across years/channels;
these are accepted, while conflicting geometries for one station are rejected.

Generate the combined GeoJSON after importing, and process current data normally
when its source Objects are available:

```sh
./scripts/invoke.sh geojson MMM
# After a successful current source download:
./scripts/invoke.sh preprocess MMM
./scripts/invoke.sh geojson MMM
```

A normal request supplies `datasourceId`; an archive request also supplies an
inactive `datasetId`. The optional dataset argument is accepted only for
`preprocess`, not `ds` or `geojson`. Empty and malformed request bodies fail.
Direct callers use `{"datasourceId":"MMM","datasetId":"mmm-2022-2024"}`;
all settings are resolved from the deployed bundle. Both modes use the same
publication lock and manifest-last validation.

If an existing manifest already defines `current` with a wider year scope,
use a new active dataset ID before processing the narrower current period;
the existing rule against shrinking a published dataset still applies. Archive
imports preserve that existing active definition rather than silently rewriting
its scope. Deploy the updated handler before using the new command: an older
handler does not interpret the identifier-only archive request body.

### Making past periods read-only

Use separate dataset IDs for the periods you want to preserve and the period
you want to keep processing. A single downloaded CSV can contain all these
years. Once you switch to a dataset whose year scope excludes an earlier period,
preprocessing keeps that period's published yearly Objects and manifest references
unchanged, even if the upstream CSV later modifies those records.

For example, DPT34 contains records for 2014–2025 in one CSV. Publish those years
in two groups, then leave 2026 as the active period.

Before starting, ensure `DPT34_Comptage.csv` has been downloaded successfully.
The DPT34 `datasets` map already contains all three periods, each with its own
`years`, `datasetInfoUrl` and `inputs`. The following fragments show only the
`activeDatasetId` field to change inside the existing `DPT34` entry in
`dsconfig.json`. Keep all dataset entries and the datasource logo URL. The
periods share the same input URL, so switching dataset IDs does not require
another download. Registering an inactive dataset in this file does not publish
it to the manifest; it must be selected and preprocessing invoked first.

**1. Publish the first historical period: 2014–2019.**

Set these values in the DPT34 configuration:

```json
{
  "activeDatasetId": "dpt34-2014-2019"
}
```

Apply the configuration and run preprocessing:

```sh
./scripts/deploy-functions.sh preprocess
./scripts/invoke.sh preprocess DPT34
```

After a successful run, inspect `DPT34_Manifest.json` in the DPT34 bucket.
Confirm that `activeDatasetId` is `dpt34-2014-2019`, that `datasets` contains this
dataset, and that `years` references a yearly JSON Object for every year from
2014 through 2019. Check the INFO messages for configured years without records;
such years have no new Object to preserve. Continue after the expected historical
records have been published.

**2. Publish the second historical period: 2020–2025.**

Change the same fields to:

```json
{
  "activeDatasetId": "dpt34-2020-2025"
}
```

Repeat the deployment and invocation above. Confirm that `activeDatasetId` is
now `dpt34-2020-2025`. The manifest should retain the dataset and exact yearly
Object references for 2014–2019, and add the dataset and references for 2020–2025.
The first historical period is now excluded from ongoing updates.

**3. Switch ongoing processing to 2026.**

Change the configuration to:

```json
{
  "activeDatasetId": "dpt34-2026"
}
```

Repeat the deployment and invocation. Confirm that `activeDatasetId` is now
`dpt34-2026` and all three datasets remain in the manifest. The yearly references
for 2014–2025 stay unchanged. Only records for 2026 can create or update yearly
Objects on subsequent runs; records for other years are skipped and their years
are logged at INFO level.

If the CSV has no 2026 records yet, preprocessing logs that year as having no
records and retains the historical publication. A 2026 Object is added when
records become available. Selecting `[2026]` does not impose a publication date:
if 2026 must only be published from the beginning of 2027, run preprocessing for
that period only from then, unless the upstream publisher itself withholds
those records until 2027.

Keep historical year ranges out of future active configurations to preserve them.
Reactivating an earlier dataset, or including its years in a new active dataset,
allows those years to be processed again. Use a new dataset ID when moving to
the next period, and inspect the generated manifest rather than editing it manually.

## OSM caches

### OpenStreetMap cache

At the beginning of each invocation, the function loads the OSM way cache from
bucket `osminfo`, object `<sourceId>_OSMInfo.json`, in `NAMESPACE` (for example,
`MMM_OSMInfo.json` or `DPT34_OSMInfo.json`). Override the shared bucket with
function configuration key `BUCKETNAME_OSM`; object names are derived from the
selected datasource ID.
The bucket must exist and the function must have permission to read and write
its objects. A missing cache object starts an empty cache; other storage errors
or invalid JSON throw an invocation error without overwriting the cache.

`org.traficinfo.dsfunctions.OSMInfoProviderImpl` handles cache loading, refreshing,
and persistence using `org.traficinfo.dsfunctions.OpenStreetMapClient` for OSM
requests. GeoJSON publication calls `loadCache()` before parsing and
`refreshCache()` after publishing the GeoJSON. The cache map remains
private to the implementation. Calls to `getWayInfo(wayId)` queue missing or stale ways
internally for the deferred refresh. Parsers use the
`org.traficinfo.parsers.OSMInfoProvider` interface, which exposes `OSMWayInfo getWayInfo(long wayId)` and the cache-only
`getWayId(centraleId, point, roadReference)` lookup.

The JSON object is keyed by OSM way ID. Each record contains `lastUpdated`, an
ISO 8601 UTC timestamp of the successful fetch, and `tags`, all tag values
returned by OSM. For example:

```json
{
  "370743659": {
    "lastUpdated": "2026-10-14T12:00:00Z",
    "tags": {"maxspeed": "30", "highway": "residential"}
  }
}
```

`GeoJsonParser.parseAndMerge` accepts the provider and a `Set<Long>` of ways to
refresh. It populates `Comptage.maximumSpeed` from the cached `maxspeed` tag and
outputs it as the GeoJSON property `maximum_speed`, formatted like
`v85_speed`: positive values as text and values ≤ 0 as `"-"`. Numeric values
(including an explicit `km/h` suffix) are used as km/h. Missing, nonnumeric,
or unsupported values such as `none`, `walk`, and `50 mph` produce `-1`;
their original tags remain in the cache.

Missing entries and entries older than three calendar months are queued once
per way. Stale speeds remain available for the current output. After generating
and uploading the GeoJSON, the function makes at most 100 sequential requests
to `https://api.openstreetmap.org/api/0.6/way/{id}.json`, then uploads the updated
cache as gzip JSON. Newly fetched tags affect the next invocation. Failed
requests count toward the limit, leave existing entries unchanged, and do not
fail the invocation. A cache upload failure throws an invocation error even though the
GeoJSON has already been published. Unprocessed ways are queued again on the
next run; the cache is written only when at least one fetch succeeds.

Each OSM request has a five-second timeout. Allow sufficient function runtime
for 100 requests plus Object Storage downloads and uploads when configuring
deployment timeouts.

OSM endpoint and tagging references: [API v0.6](https://wiki.openstreetmap.org/wiki/API_v0.6)
and [maxspeed](https://wiki.openstreetmap.org/wiki/Key:maxspeed).

### Counter-to-way mapping cache

At startup the provider also reads `<sourceId>_OSMWayMapping.json` from the same
bucket. Its name is derived from the selected datasource ID. The cache uses the
source's `codeCentrale` as its key, for example `DPT34_Per.034.2005.00.3`, and
supports the seed file in `data/osm/DPT34_OSMWayMapping.json`.

For records without a source-provided OSM ID, the parser asks the provider for
the counter's cached positive `osmWayId`. If no valid ID exists, the provider
records the counter in memory once per run. New entries have `osmWayId: null`,
`status: "MISSING"`, coordinates (null if unavailable), `firstSeen` and `lastSeen`.
Historical rows are processed oldest first so unresolved counters retain their
latest available coordinates and road reference. Missing entries also store
`sourceRoadReference` (null when unavailable), for future comparison with OSM.
DPT34 derives it from `route_id` by removing the department prefix:
`34D0011` becomes `D0011`. DPT30 retains `numero_route`, for example `D6086`.
No comparison or normalization against OSM is performed at this stage.
Each mapping is represented by `OSMWayMapping`, with getters and setters for
its way IDs, coordinates, timestamps, status, road references, and review fields.
Extra seed annotations are retained as additional properties during JSON
round trips. Existing fields, including seed review metadata, are preserved. Neither source-provided IDs nor positive cached IDs are replaced.

After GeoJSON publication, `refreshCache()` saves the entire mapping cache,
including unresolved counters, before performing the existing way-tag refresh.
INFO logs show entries loaded, unique counters missing a usable ID, and entries
saved. Missing objects start empty; other storage errors or invalid JSON fail
without overwriting that cache. A failed mapping upload throws an invocation error after the
GeoJSON has been published.

GeoJSON functions perform no reverse HTTP calls. The hourly `OSMInfoFunction`
can resolve incomplete mappings as described below. Alternatively, populate
`osmWayId` manually in Object Storage and it is used on the next run;
positive IDs are used regardless of any older descriptive `status` field.
Unresolved entries do not consume the existing 100-request way-tag budget.

## Hourly OSM mapping refresh

`org.traficinfo.dsfunctions.OSMInfoFunction::handleRequest` lists the objects in
`osminfo`, shuffles the list, and processes objects ending in `_OSMWayMapping.json`.
For each cache it loads that mapping and the matching `<prefix>_OSMInfo.json`
through `OSMInfoProviderImpl.loadCache()`. INFO logs report unknown/total mappings,
way-info entries, attempted reverse lookups, resolved entries, and HTTP failures.
Each reverse result logs its counter ID, status, assigned/candidate way ID,
source and OSM references, and review reason. `OSMWayMappingStatus` defines the
four JSON status values (`MISSING`, `MATCHED`, `NEEDS_REVIEW`, `NOT_FOUND`), with
comments describing their meaning. New mappings start as `MISSING`.

At the end of each invocation, including failures and interruption, two summaries per data source, identified by its cache prefix,
list counts for every status, including zeros: first for unique records attempted
this run or manually approved (HTTP failures retain their existing status),
then for all records in loaded caches, including records not attempted. Each
summary covers only that data source. All source caches are loaded and manual
approvals saved before the bounded API loop, so sources skipped by the API time
budget still appear in the totals and global review document.
A missing cache starts empty; other load/save errors throw after processing
the remaining resources. An empty bucket returns `OK`.

`OSMWayMapping_NEEDS_REVIEW.json` in the same bucket is a pretty-printed global
review document: data source prefix → counter ID → mapping record. Every loaded
source has an object, empty when it has no `NEEDS_REVIEW` records. At startup,
the function reads this file (a missing object starts empty). A reviewer can
change a record to `MATCHED` and optionally correct `candidateOsmWayId`, then
reupload it. For a current review candidate, the function assigns that positive
candidate ID to `osmWayId`, clears the candidate/review reason, updates
`lastUpdated`, saves the source mapping cache, and queues tag refresh. Existing
positive assignments are preserved. Invalid approvals fail without replacing
the review file. Processed counts include manual approvals.

At the end, the document is rebuilt from all source caches, including sources
whose API batches were skipped by the time budget. Applied approvals disappear
from it. The function logs added, changed, and removed record counts relative to
the document loaded at startup, identifying records by data source and counter ID.
Changes to any serialized record field, including timestamps, count as changes;
JSON formatting and object-key order do not. An unchanged document skips the PUT.
A missing document is created even when empty; changes to empty source groups
also update the document without increasing the record counts.
A load/save failure preserves the previous review document rather than
publishing incomplete results. Each mapping has generated `candidateOsmWayUrl`,
`osmWayIdUrl`, and `coordinatesOsmUrl` links where IDs/coordinates are valid; map
links use `https://www.openstreetmap.org/?mlat={latitude}&mlon={longitude}#map=17/{latitude}/{longitude}`
to show a marker at the exact coordinates. Uploaded
links are ignored and regenerated, including when a candidate ID is edited.

Before the API loop, a cache-only first pass reports eligible reverse lookup
counts per data source, the total number of eligible records, and the number of
sources with pending lookups. Counts use the same eligibility rule as processing:
no usable way ID, valid coordinates, and no reverse result cached for those
coordinates. Cached review/negative outcomes and records without coordinates do
not inflate demand; HTTP failures and changed coordinates remain eligible.
These counts determine each source’s share of the run-wide lookup budget.

Two optional positive integer configuration values bound the work. Private static
integer constants in `OSMInfoFunction` provide defaults when the keys are absent:

- `MAX_LOOP_DURATION_SECONDS` (default `180` seconds): elapsed time is checked before **every listed
  resource**, including non-mapping objects. No further resource starts after the
  budget is reached. There is deliberately no duration check between lookups;
  the active resource's bounded batch finishes and saves, so total loop duration
  can exceed the budget by that batch's remaining work and storage operations.
- `MAX_WAY_MAPPINGS_PER_RUN` (default `12` mappings across all sources): the
  budget is shared evenly among sources with eligible records. Shares are capped
  by demand and any unused capacity is redistributed. Remainders follow the
  shuffled resource order. For example, two busy sources receive six each; one
  busy source can receive all twelve. HTTP errors also consume the allocated
  budget. Entries never attempted precede older failed attempts. Known positive
  IDs are preserved. The time budget can prevent later allocated batches from
  starting; unused allocations are left for another run.

All reverse interaction is initiated by `OSMInfoProviderImpl.refreshWayMappings()`.
It uses valid cached coordinates and requests Nominatim with `format=jsonv2`,
`zoom=17`, `layer=address`, `extratags=1`, and `namedetails=1`. Only positive OSM **way** IDs with
road highway classifications are accepted; buildings, nodes, relations, and
footways are rejected. When a source road reference exists, the OSM `namedetails.ref` must
match after removing spacing, punctuation, and leading zeroes from each number
following a letter (`D0011` matches `D 11`; `D0111E01` matches `D 111E1`).
A change from `D 11` to `M 11` requires review rather than automatic assignment.
Without a source reference, a road way may be accepted by its classification.
Reverse geocoding can still select a nearby different street; see the
[Nominatim reverse API](https://nominatim.org/release-docs/latest/api/Reverse/).

Successful results populate `osmWayId` and set `status` to `MATCHED`.
Road-reference mismatches or missing OSM references set `NEEDS_REVIEW`, with
`candidateOsmWayId`, `osmRoadReference`, and `reviewReason`; the usable ID remains
unset. Responses without a suitable road way set `NOT_FOUND`. Responses and the
query coordinates are saved in `reverseLookupResult`, `lastLookupLatitude`, and
`lastLookupLongitude`, with attempt time in `lastLookupAt`. Successful negative
and review responses are cached, so unchanged coordinates are not repeatedly
queried on subsequent hourly runs. Changed coordinates become eligible again.
HTTP errors remain eligible for later runs. Existing positive IDs and unrelated
seed metadata are preserved; only changed mapping caches are written. Each
successfully resolved mapping queues its way ID for tag refresh, deduplicated
within that cache. At the end of each resource's reverse batch, the function calls
`refreshWayInfo()` to process the queue and save successful tag fetches to the
matching `_OSMInfo.json` object. Candidates awaiting review are not queued.
The existing limit of 100 way-tag requests per provider refresh still applies;
failed requests count toward that limit and preserve previous cached tags.

`OpenStreetMapClient` defines `REVERSE_INTERVAL_SECONDS` (`15`) and uses it in
its default constructor. The shared reverse client spaces request starts by at least **15 seconds** across
all resources, caches repeated coordinates within the process, identifies the
application with its User-Agent, and makes no immediate retries. Each reverse lookup logs its full request URL and response at INFO level,
including the HTTP status and complete body for unsuccessful responses; cached
responses are identified explicitly. Transport failures log the URL and error.
Each HTTP request has a five-second timeout. These settings follow the public service's
[Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/):
scheduled jobs are limited to four requests per minute and results must be cached.
Run a single invocation at a time, including manual invocations, to avoid overlap.
Reverse results contain data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
available under ODbL.

Configure `NAMESPACE`, optionally `BUCKETNAME_OSM` (default `osminfo`),
`USE_RESOURCE_PRINCIPAL` (default `true`), `MAX_LOOP_DURATION_SECONDS`, and `MAX_WAY_MAPPINGS_PER_RUN`. Invalid,
nonpositive, or out-of-range limits fail configuration. The reverse API endpoint
is fixed to `https://nominatim.openstreetmap.org/`. The function needs permission
to list/read bucket objects and write mapping and way-info objects, plus outbound
HTTPS access. Gzip cache objects and
paginated listings are supported. Count getters `getWayInfoCount()`,
`getWayMappingCount()`, and `getUnknownWayMappingCount()` remain implementation-only.

`scripts/deploy-functions.sh` registers `OSMInfoFunction`, sets its runtime timeout to
**300 seconds** on creation and updates, and applies
`TraficInfo.DataSourceSchedule=Hourly`. The `Hourly` schedule invokes it at
**the start of every hour (UTC)**. After `source ./scripts/setup.sh`, run `./scripts/deploy-functions.sh` and
`./scripts/setup-schedules.sh` to deploy and reconcile the schedule. Set environment
variables `MAX_LOOP_DURATION_SECONDS` and `MAX_WAY_MAPPINGS_PER_RUN` before deployment to override the defaults;
the script applies these values on creation and updates. Keep the function runtime
timeout above the configured loop budget plus time for the last bounded batch.
The scheduling tag namespace/key and scheduler invoke permissions must already
be configured as described in the root README.

## Deployment and invocation

After `source ./scripts/setup.sh`, use the scripts from the repository root:

```sh
./scripts/deploy-functions.sh ds
./scripts/invoke.sh ds DPT34
./scripts/setup-schedules.sh --dry-run
./scripts/setup-schedules.sh

./scripts/deploy-functions.sh preprocess
./scripts/invoke.sh preprocess DPT34

./scripts/deploy-functions.sh geojson
./scripts/invoke.sh geojson DPT34

./scripts/deploy-functions.sh osm
./scripts/invoke.sh osm
```

Retrieval, preprocessing and GeoJSON deploy once as `DataSourceFunction`,
`PreprocessingFunction` and `GeoJsonFunction`. No-argument deployment includes
these three functions and `OSMInfoFunction`. Adding a retrieval source requires
configuration, redeployment and schedule setup. New GeoJSON sources require parser
and `GeoJsonDataSource` registration, followed by redeployment; no per-source
handler or deployment entry is needed.

Deployment builds the fat JAR once and uploads the selected functions using the
managed `java21.ol9` runtime. It checks handler/resource presence, the 25 MB direct
archive limit and OCI's 4096-byte runtime configuration budget before mutation.
It polls each create/update work request for up to ten minutes. Input settings
come from the shared setup exports; `OCI_FN_APPLICATION_ID` can bypass the
application-name lookup. The application remains `3MOpenDataProcessor`.

Retrieval and preprocessing bundle `dsconfig.json` and receive `NAMESPACE`.
All stages use the input bucket selected by `dsconfig.json`. GeoJSON deployment
also accepts `BUCKETNAME_OUT` and `BUCKETNAME_OSM`. OSM cache object names derive
from the datasource ID. OSM refresh receives
`NAMESPACE`, `BUCKETNAME_OSM`, `MAX_LOOP_DURATION_SECONDS` and
`MAX_WAY_MAPPINGS_PER_RUN`.

Invocation resolves the deployed function and calls it synchronously. Retrieval
and GeoJSON send only `datasourceId`; normal preprocessing does the same and
also accepts an inactive `datasetId` for archive imports. OSM uses an empty body.
Retrieval's client timeout is the maximum configured source runtime plus 60
seconds; preprocessing, GeoJSON and OSM use 360 seconds. Missing functions and OCI errors
produce a failing exit status.

The function resource principals need Object read/write access to the configured
buckets; preprocessing also needs create/delete permissions for its publication lock. Scheduler principals must be allowed to invoke the shared retrieval function.
See the root README for generated schedules, per-source `scheduleClock` overrides,
application logging setup and event configuration.

## Manifest storage

The shared [datasource storage contract](../specs/datasource-storage-contract.md)
defines the manifest, yearly file layout and retrieval configuration for all
sources. The shared preprocessing function implements yearly publication.
Existing event rules continue to use their current CSV inputs until the separate
GeoJSON migration.

### Failure monitoring

GeoJSON and OSM handlers return `OK` on success and throw on invocation
failure so OCI records an error for the function failure alarm. OSM refresh
continues processing other caches before throwing if any cache failed.
Recoverable OSM HTTP lookup failures keep their retry behavior. Set up email
alerts using `scripts/setup-function-alerts.sh` as described in the root README.
