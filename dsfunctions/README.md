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
`OOSResourceMetadata` are shared by the M3M and DPT34 source functions.
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
No companion GeoJSON or OSM cache is needed: the CSV supplies coordinates
and no OSM way IDs. Temporary output files are removed on success and failure.
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
bucket `osminfo`, object `MMM_OSMInfo.json`, in `NAMESPACE`. Override these defaults
with function configuration keys `BUCKETNAME_OSM` and `OSM_CACHE_OBJECT`.
The bucket must exist and the function must have permission to read and write
its objects. A missing cache object starts an empty cache; other storage errors
or invalid JSON return `KO` without overwriting the cache.

`org.traficinfo.dsfunctions.OSMInfoProviderImpl` handles cache loading, refreshing,
and persistence using `org.traficinfo.dsfunctions.OpenStreetMapClient` for OSM
requests. The M3M `GeoJsonOutFunction` calls `loadCache()` before parsing and
`refreshCache(waysToRefresh)` after publishing the GeoJSON. The cache map remains
private to the implementation. Parsers use the
`org.traficinfo.parsers.OSMInfoProvider` interface, which exposes only
`OSMWayInfo get(long wayId)`.

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

## Archive deployment (code-only function)

From the repository root, run `./deploy-functions.sh`. It builds the Maven
`jar-with-dependencies` artifact once and uploads it directly to OCI Functions as
`3MOpenDataSource`, `DPT34OpenDataSource`, `3MGeoJsonOut` and `DPT34GeoJsonOut`
in the existing `3MOpenDataProcessor` application. It uses
the managed `java21.ol9` runtime, which can run this project's Java 17 bytecode.
On later runs, the script updates each exact-name archive function with its own
handler (`DataSourceFunction::handleRequest` or `GeoJsonOutFunction::handleRequest`).

First run `source ./setup.sh` to initialize the shared environment. Deployment
uses the exported OCI credentials, `OCI_COMPARTMENT_ID`,
`OCI_OBJECT_STORAGE_NAMESPACE`, `OCI_FN_APPLICATION_NAME`, `TRAFICINFO_ROOT`,
and `TRAFICINFO_DSCONFIG`. Set `OCI_FN_APPLICATION_ID` to bypass application
lookup. The compartment and storage namespace are resolved during setup rather
than queried again during each deployment. `3MOpenDataSource` and `DPT34OpenDataSource`
receives `NAMESPACE` and `BUCKETNAME`; `3MGeoJsonOut` receives `NAMESPACE`,
`BUCKETNAME_IN`, `BUCKETNAME_OUT`, `BUCKETNAME_OSM`, and `OSM_CACHE_OBJECT`.
The script requires `jq` to prepare function configuration.
The input bucket defaults to `ds3mbucket`
and can be overridden with environment variable `BUCKETNAME_IN`. The output bucket
defaults to the existing `traficinfo` bucket in the same compartment and can be
overridden with `BUCKETNAME_OUT`. The OSM bucket defaults to `osminfo` and its
cache object to `MMM_OSMInfo.json`; override these with environment variables
`BUCKETNAME_OSM` and `OSM_CACHE_OBJECT`. The script sets the full function
configuration from these defaults and environment overrides on both creation
and updates.

To add another function, add a name, handler, and config builder to the
`functions` registry in the script, then define its config builder. The shared
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
```

`invoke.sh` accepts exactly two arguments: `ds|geojson` and the data source ID
`MMM|DPT34` from `dsconfig.json`. `MMM` maps to the existing `3M` function names.
It resolves `<source>OpenDataSource` or `<source>GeoJsonOut` in
`3MOpenDataProcessor`, invokes synchronously with an empty body, and prints the
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
