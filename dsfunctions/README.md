The M3M function in the dsfunctions module downloads Montpellier Méditerranée
Métropole traffic counts in CSV and GeoJSON format and stores them in the
`ds3mbucket` Object Storage bucket.

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

## Shared infrastructure

`org.traficinfo.dsfunctions.HttpClient`, `ObjectStoreClient` and
`OOSResourceMetadata` are shared by the M3M, DPT30, DPT34 and MRN source functions.
The HTTP client sends `If-None-Match` for real server ETags and returns no body
for HTTP 304. When the server supplies no ETag, it stages the complete response
in a temporary file and exposes `sha256:<hex-digest>` through `getEtag()`.
It returns no body when this synthetic validator matches the previous value.
Synthetic validators are never sent in `If-None-Match`; their comparison requires
a full download on each invocation. A change of row order or formatting changes
the hash. Both real and synthetic validators use the same `current-etag` object
metadata. Last-Modified remains optional and is not inferred by the HTTP client.
Responses, body streams and temporary files are released when `EntityBody` is
closed. Unchanged downloads and failed downloads also delete temporary files.

### CSV function superclasses

DPT30, DPT34 and MRN use `AbstractCsvDataSourceFunction` and
`AbstractCsvGeoJsonOutFunction`. Their concrete handlers provide a `CsvDataSource`
enum value to the superclass constructor. The enum exposes `getDataSourceId()`
and `getCsvUri()`, selects the parser, and derives default bucket and object
names. Existing public handler names, configuration keys, constants and return
behavior are preserved. MMM remains separate because its companion GeoJSON
requires a different input sequence.

Both `config(RuntimeContext)` (annotated `@FnConfiguration`) and
`handleRequest(String)` are public final methods on the superclasses. Fn runtime
1.0.185 discovers them through inherited public methods. Tests use Fn's actual
loader, configurer and runtime context to verify configuration and invocation,
with fake clients replacing external services. Protected client constructors
and factories allow testing without OCI credentials or network requests.

## MRN functions

`org.traficinfo.dsfunctions.mrn.DataSourceFunction::handleRequest` downloads the
Métropole Rouen Normandie
[CSV export](https://data.metropole-rouen-normandie.fr/api/explore/v2.1/catalog/datasets/comptages-et-enquetes-sur-le-reseau-routier/exports/csv?use_labels=true&delimiter=%3B).
Keep French labels and semicolon delimiters. Configure `NAMESPACE`, optionally
`BUCKETNAME` (default `dsmrn`) and `USE_RESOURCE_PRINCIPAL` (default `true`).
The current object is `MRN_Comptage.csv`; previous versions are archived as
`MRN_Comptage_YYYYMMDD.csv` before replacement. Publication uses the shared gzip
storage, download metadata and content-change detection. On October 22, 2026,
the export returned neither ETag nor Last-Modified, so the HTTP client's SHA-256
fallback applies and still requires downloading the full export to compare it.

`org.traficinfo.dsfunctions.mrn.GeoJsonOutFunction::handleRequest` reads plain or
gzip CSV with `GeoJsonParser.DataSource.MRN` and publishes gzip
`MRN_TraficInfo.geojson` in `traficinfo`. Configuration keys are `NAMESPACE`,
`BUCKETNAME_IN` (default `dsmrn`), `BUCKETNAME_OUT` (default `traficinfo`), and
`USE_RESOURCE_PRINCIPAL`. OSM configuration uses the shared `BUCKETNAME_OSM`,
`OSM_CACHE_OBJECT` and `OSM_WAY_MAPPING_OBJECT` overrides, defaulting to `osminfo`,
`MRN_OSMInfo.json` and `MRN_OSMWayMapping.json`. No road reference is extracted
from Rouen street labels. See the parser package README for campaign filtering.

The MRN entry in `dsconfig.json` selects Daily retrieval. Deployment registers
`MRNOpenDataSource` and `MRNGeoJsonOut`; `BUCKETNAME_MRN` overrides their input
bucket. Bucket and event setup use the configured source ID, matching only the
current `MRN_Comptage.csv` object. After `source ./setup.sh`, use
`./deploy-functions.sh ds MRN`, `./deploy-functions.sh geojson MRN`,
`./invoke.sh ds MRN` or `./invoke.sh geojson MRN`. Add/reconcile buckets, events
and schedules with the existing setup scripts before relying on scheduled runs.

## DPT30 functions

`org.traficinfo.dsfunctions.dpt30.DataSourceFunction::handleRequest` downloads
Gard's [CSV resource](https://www.data.gouv.fr/api/1/datasets/r/69584212-8ff0-40d3-b82d-2d5d2890bff7).
The HTTP client follows the stable URL's redirect to the dated file and uses
its ETag for conditional downloads. HTTP 304 returns `OK` without uploads or
archives. Configure `NAMESPACE`; `BUCKETNAME` defaults to `dsdpt30`, and
`USE_RESOURCE_PRINCIPAL` defaults to `true`.

The function uploads `DPT30_Comptage.csv` as gzip `text/csv`, recording the
source validator in `current-etag`, a UTC `downloaded-at` timestamp and a
`last-modified` download date. Before replacing existing content, it copies the
previous CSV and metadata to `DPT30_Comptage_YYYYMMDD.csv`, using the previous
download date. Multiple versions downloaded on one day share an archive name.
An empty download, download error or archive failure prevents replacement and
fails the invocation. As with DPT34, downstream event rules must match the
current CSV name exactly so archives do not trigger processing.

`org.traficinfo.dsfunctions.dpt30.GeoJsonOutFunction::handleRequest` reads the
CSV and generates points and annual history with `GeoJsonParser.DataSource.DPT30`.
It accepts plain or gzip input and uploads gzip `DPT30_TraficInfo.geojson`
with content type `application/geo+json`. Configure `NAMESPACE`,
`BUCKETNAME_IN` (default `dsdpt30`) and `BUCKETNAME_OUT` (default `traficinfo`).
`USE_RESOURCE_PRINCIPAL` defaults to `true`. OSM enrichment uses
`DPT30_OSMInfo.json` and `DPT30_OSMWayMapping.json` in bucket `osminfo`, with
configuration overrides `OSM_CACHE_OBJECT`, `OSM_WAY_MAPPING_OBJECT` and
`BUCKETNAME_OSM`. The output handler returns `OK` on success and `KO` on errors,
including cache failures. Temporary output files are deleted on success and
failure. CSV coordinates supply geometry; no companion GeoJSON is downloaded.

`deploy-functions.sh` registers `DPT30OpenDataSource` and `DPT30GeoJsonOut`.
Names, the `Monthly` schedule tag and default bucket `dsdpt30` come from the
DPT30 entry in `dsconfig.json`; `BUCKETNAME_DPT30` overrides the deployment input
bucket. After deployment, invoke with `./invoke.sh ds DPT30` or
`./invoke.sh geojson DPT30`. Run `source ./setup.sh` before these scripts.

Tests cover first/unchanged/changed downloads, archive and upload failures,
empty downloads, plain/gzip CSV-to-GeoJSON output, annual history, OSM cache
integration and cleanup. A shared HTTP client test verifies ETag forwarding
through a redirect to another host name. Run `mvn -o -pl dsfunctions -am test`.

## DPT34 data source function

`org.traficinfo.dsfunctions.dpt34.DataSourceFunction::handleRequest` downloads
the Hérault [CSV export](https://www.herault-data.fr/api/explore/v2.1/catalog/datasets/trafic-de-l-herault-donnees-de-comptage/exports/csv?use_labels=true&delimiter=%3B).
Configure `NAMESPACE`; `BUCKETNAME` defaults to `dsdpt34`, and
`USE_RESOURCE_PRINCIPAL` defaults to `true`.

The endpoint supplies no ETag or Last-Modified header. The function delegates
change detection to the shared HTTP client using the stored `current-etag`
validator. If unchanged, it returns `OK` without uploading anything.

On first download or changed content, the function uploads
`DPT34_Comptage.csv` with content type `text/csv`, gzip encoding,
`current-etag`, a `downloaded-at` UTC timestamp and a `last-modified` download
date in user metadata.
When replacing an existing CSV, it first copies the previous content and metadata
to `DPT34_Comptage_YYYYMMDD.csv`, following the M3M date suffix convention.
The date comes from the previous object's `last-modified` metadata.
Since the source has no Last-Modified header, this
is a download date, not the publisher's modification date. As with M3M,
multiple versions dated the same day share an archive name.
Copying preserves the current object until
the replacement upload succeeds. A failed archive prevents replacement.

Download, storage and empty-response failures fail the invocation. Temporary
files are deleted on success and failure. No companion GeoJSON file is downloaded:
the DPT34 parser supplies geometry from the CSV coordinates.

The source bucket's object events cover archive uploads as well as current
uploads; downstream event rules should match the exact current object name.
`deploy-functions.sh` registers this handler as `DPT34OpenDataSource`.
Its bucket defaults to `dsdpt34`; override it with `BUCKETNAME_DPT34`.

## DPT34 GeoJSON output function

`org.traficinfo.dsfunctions.dpt34.GeoJsonOutFunction::handleRequest` reads
`DPT34_Comptage.csv` from Object Storage and generates geometry directly from
the CSV coordinates through `GeoJsonParser.DataSource.DPT34`.
Configure `NAMESPACE`, `BUCKETNAME_IN` (default `dsdpt34`) and
`BUCKETNAME_OUT` (default `traficinfo`). `USE_RESOURCE_PRINCIPAL` defaults to
`true`.

The handler accepts plain or gzip CSV and uploads `DPT34_TraficInfo.geojson`
with content type `application/geo+json` and gzip encoding. It preserves the
parser's filtering, latest geometry selection, annual history and map styling.
No companion GeoJSON is needed: the CSV supplies coordinates. Cached way IDs
from `DPT34_OSMWayMapping.json` allow speed-limit enrichment through
`DPT34_OSMInfo.json`. Both objects are stored in `osminfo`. Temporary output files are removed on success and failure.
The handler returns `OK` on success and `KO` on download, parsing or upload failure,
matching the M3M output function.

`deploy-functions.sh` registers it as `DPT34GeoJsonOut` using
`BUCKETNAME_DPT34` for its input bucket and `BUCKETNAME_OUT` for its output.
After deployment, invoke it with `./invoke.sh geojson DPT34`.

## M3M GeoJSON output function

`org.traficinfo.dsfunctions.m3m.GeoJsonOutFunction::handleRequest` reads
`MMM_MMM_Comptage.csv` and `MMM_MMM_Comptage.json` from Object Storage and
merges them with `GeoJsonParser.DataSource.M3MV2`. Configure `NAMESPACE`,
`BUCKETNAME_IN`, and `BUCKETNAME_OUT` to select the namespace and input/output
buckets. `USE_RESOURCE_PRINCIPAL` defaults to `true`, as in `DataSourceFunction`.

The function supports both plain and gzip-compressed input objects and writes
`MMM_TraficInfo.geojson` to the output bucket with content type
`application/geo+json` and `Content-Encoding: gzip`. It returns `OK` on success
or `KO` when downloading, parsing, or uploading fails. Build both modules from
the repository root with `mvn -pl dsfunctions -am package`.

### OpenStreetMap cache

At the beginning of each invocation, the function loads the OSM way cache from
bucket `osminfo`, object `<sourceId>_OSMInfo.json`, in `NAMESPACE` (for example,
`MMM_OSMInfo.json` or `DPT34_OSMInfo.json`). Override these defaults
with function configuration keys `BUCKETNAME_OSM` and `OSM_CACHE_OBJECT`.
The bucket must exist and the function must have permission to read and write
its objects. A missing cache object starts an empty cache; other storage errors
or invalid JSON return `KO` without overwriting the cache.

`org.traficinfo.dsfunctions.OSMInfoProviderImpl` handles cache loading, refreshing,
and persistence using `org.traficinfo.dsfunctions.OpenStreetMapClient` for OSM
requests. All GeoJSON functions call `loadCache()` before parsing and
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
fail the invocation. A cache upload failure returns `KO` even though the
GeoJSON has already been published. Unprocessed ways are queued again on the
next run; the cache is written only when at least one fetch succeeds.

Each OSM request has a five-second timeout. Allow sufficient function runtime
for 100 requests plus Object Storage downloads and uploads when configuring
deployment timeouts.

OSM endpoint and tagging references: [API v0.6](https://wiki.openstreetmap.org/wiki/API_v0.6)
and [maxspeed](https://wiki.openstreetmap.org/wiki/Key:maxspeed).

### Counter-to-way mapping cache

At startup the provider also reads `<sourceId>_OSMWayMapping.json` from the same
bucket. Override its name with `OSM_WAY_MAPPING_OBJECT`. The cache uses the
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
without overwriting that cache. A failed mapping upload returns `KO` after the
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
A missing cache starts empty; other load/save errors return `KO` after processing
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
from it. A load/save failure preserves the previous review document rather than
publishing incomplete results. Each mapping has generated `candidateOsmWayUrl`,
`osmWayIdUrl`, and `coordinatesOsmUrl` links where IDs/coordinates are valid; map
links use `https://www.openstreetmap.org/?mlat={latitude}&mlon={longitude}#map=17/{latitude}/{longitude}`
to show a marker at the exact coordinates. Uploaded
links are ignored and regenerated, including when a candidate ID is edited.

Two optional positive integer configuration values bound the work. Private static
integer constants in `OSMInfoFunction` provide defaults when the keys are absent:

- `MAX_LOOP_DURATION_SECONDS` (default `180` seconds): elapsed time is checked before **every listed
  resource**, including non-mapping objects. No further resource starts after the
  budget is reached. There is deliberately no duration check between lookups;
  the active resource's bounded batch finishes and saves, so total loop duration
  can exceed the budget by that batch's remaining work and storage operations.
- `MAX_WAY_MAPPINGS_PER_RESOURCE` (default `6` mappings per resource): at most six eligible incomplete mappings
  are attempted per cache; HTTP errors also consume this limit. Entries never
  attempted precede older failed attempts. Known positive IDs are preserved.

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

The shared reverse client spaces request starts by at least **15 seconds** across
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
`USE_RESOURCE_PRINCIPAL` (default `true`), `MAX_LOOP_DURATION_SECONDS`, and `MAX_WAY_MAPPINGS_PER_RESOURCE`. Invalid,
nonpositive, or out-of-range limits fail configuration. The reverse API endpoint
is fixed to `https://nominatim.openstreetmap.org/`. The function needs permission
to list/read bucket objects and write mapping and way-info objects, plus outbound
HTTPS access. Gzip cache objects and
paginated listings are supported. Count getters `getWayInfoCount()`,
`getWayMappingCount()`, and `getUnknownWayMappingCount()` remain implementation-only.

`deploy-functions.sh` registers `OSMInfoFunction`, sets its runtime timeout to
**300 seconds** on creation and updates, and applies
`TraficInfo.DataSourceSchedule=Hourly`. The `Hourly` schedule invokes it at
**the start of every hour (UTC)**. After `source ./setup.sh`, run `./deploy-functions.sh` and
`./setup-schedules.sh` to deploy and reconcile the schedule. Set environment
variables `MAX_LOOP_DURATION_SECONDS` and `MAX_WAY_MAPPINGS_PER_RESOURCE` before deployment to override the defaults;
the script applies these values on creation and updates. Keep the function runtime
timeout above the configured loop budget plus time for the last bounded batch.
The scheduling tag namespace/key and scheduler invoke permissions must already
be configured as described in the root README.

## Archive deployment (code-only function)

From the repository root, run `./deploy-functions.sh`. It builds the Maven
`jar-with-dependencies` artifact once and uploads it directly to OCI Functions as
`3MOpenDataSource`, `DPT30OpenDataSource`, `DPT34OpenDataSource`, `MRNOpenDataSource`,
`3MGeoJsonOut`, `DPT30GeoJsonOut`, `DPT34GeoJsonOut`, `MRNGeoJsonOut` and `OSMInfoFunction`
in the existing `3MOpenDataProcessor` application. It uses
the managed `java21.ol9` runtime, which can run this project's Java 17 bytecode.
On later runs, the script updates each exact-name archive function with its own
handler (`DataSourceFunction::handleRequest`, `GeoJsonOutFunction::handleRequest`,
or `OSMInfoFunction::handleRequest`).

With no arguments, all functions are deployed. To deploy just one, use the same
selectors as `invoke.sh`:

```sh
./deploy-functions.sh ds DPT34
./deploy-functions.sh geojson MMM
./deploy-functions.sh osm
```

The script still builds the shared JAR once, then validates and deploys only the
selected function. Invalid selectors fail before building or making OCI calls.

First run `source ./setup.sh` to initialize the shared environment. Deployment
uses the exported OCI credentials, `OCI_COMPARTMENT_ID`,
`OCI_OBJECT_STORAGE_NAMESPACE`, `OCI_FN_APPLICATION_NAME`, `TRAFICINFO_ROOT`,
and `TRAFICINFO_DSCONFIG`. Set `OCI_FN_APPLICATION_ID` to bypass application
lookup. The compartment and storage namespace are resolved during setup rather
than queried again during each deployment. `3MOpenDataSource` and `DPT34OpenDataSource`
receive `NAMESPACE` and `BUCKETNAME`; both GeoJSON functions receive `NAMESPACE`,
`BUCKETNAME_IN`, `BUCKETNAME_OUT`, `BUCKETNAME_OSM`, `OSM_CACHE_OBJECT`, and
`OSM_WAY_MAPPING_OBJECT`.
`OSMInfoFunction` receives `NAMESPACE`, `BUCKETNAME_OSM`, `MAX_LOOP_DURATION_SECONDS`, and `MAX_WAY_MAPPINGS_PER_RESOURCE`.
The script requires `jq` to prepare function configuration.
Function names and input buckets are derived by `oci-common.sh` from each
data source's `id`, `functionPrefix`, and `inputBucket` in `dsconfig.json`.
MMM currently uses `ds3mbucket`, overridable with `BUCKETNAME_IN`; DPT34 uses
`dsdpt34`, overridable with `BUCKETNAME_DPT34`. The output bucket
defaults to the existing `traficinfo` bucket in the same compartment and can be
overridden with `BUCKETNAME_OUT`. The OSM bucket defaults to `osminfo` and its
tag cache object to `<sourceId>_OSMInfo.json`, with mappings in
`<sourceId>_OSMWayMapping.json`; override these with environment variables
`BUCKETNAME_OSM`, `OSM_CACHE_OBJECT`, and `OSM_WAY_MAPPING_OBJECT`. The script sets the full function
configuration from these defaults and environment overrides on both creation
and updates.

To add another function, add its data source ID, kind (`ds` or `geojson`), and
Java handler to the `functions` registry in the script. Extend the local config
builder if it needs additional environment variables. The standalone
`osm` registry entry deploys `OSMInfoFunction` independently of `dsconfig.json`. The shared
deployment flow validates every handler class before uploading and prepares
all configs before modifying any function.

The OCI CLI commands submit asynchronous create or update requests. The script
captures the `opc-work-request-id` response header, polls that exact work
request for up to ten minutes, and exits successfully only when it reaches
`SUCCEEDED`.

For example:

```sh
export OCI_CLI_CONFIG_FILE="$HOME/.oci/config.arnaudq"
./deploy-functions.sh
fn invoke 3MOpenDataProcessor 3MOpenDataSource
fn invoke 3MOpenDataProcessor 3MGeoJsonOut
```

To invoke a deployed function using OCI CLI:

```sh
./invoke.sh ds MMM
./invoke.sh geojson MMM
./invoke.sh ds DPT34
./invoke.sh geojson DPT34
./invoke.sh osm
```

For data source functions, `invoke.sh` accepts two arguments: `ds|geojson` and
the data source ID `MMM|DPT30|DPT34|MRN` from `dsconfig.json`. `MMM` maps to the
existing `3M` function names. Use `./invoke.sh osm` with no data source argument
to invoke `OSMInfoFunction`, independently of `dsconfig.json`. For `osm` only,
the script sets `--read-timeout 360`, allowing the client to wait up to six minutes
for the response. This exceeds the function's configured 300-second runtime.
Other invocation types use the OCI CLI's existing timeout settings (the default
read timeout is 60 seconds; see [invocation timeouts](https://docs.oracle.com/en-us/iaas/Content/Functions/Tasks/functionsinvokingfunctions.htm)).
The script resolves the selected function in `3MOpenDataProcessor`, invokes
synchronously with an empty body, and prints the
function response to standard output. OCI errors propagate as a failing exit code.
Run `source ./setup.sh` first. Application lookup uses the exported application
name and compartment, or `OCI_FN_APPLICATION_ID`, as in the deployment script.
Invoking a function that has not been deployed reports its name and exits
without invoking anything.

Direct uploads are limited to 25 MB; the script checks the JAR size before
uploading. OCI's exact-name function lookup is case sensitive, so use the
capitalization shown above when invoking or configuring this function.

OCI Dynamic Groups
traficinfo_fn: all Functions in traficinfo compartment
traficinfo_apigw: all APIGW in traficinfo compartment

Policies
grant-fn-access-to-use-objects: "Allow dynamic-group traficinfo_fn to use objects in compartment TraficInfo"
