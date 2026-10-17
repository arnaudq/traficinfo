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

## GeoJSON output function

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
`3MOpenDataSource` and `3MGeoJsonOut` in the existing `3MOpenDataProcessor` application. It uses
the managed `java21.ol9` runtime, which can run this project's Java 17 bytecode.
On later runs, the script updates each exact-name archive function with its own
handler (`DataSourceFunction::handleRequest` or `GeoJsonOutFunction::handleRequest`).

The script uses OCI CLI credentials (`OCI_CLI_CONFIG_FILE` and
`OCI_CLI_PROFILE` if needed). It finds the application using
`oracle.compartment-id` in the current Fn context configured by `setup.sh`.
Alternatively, set `OCI_FN_APPLICATION_ID` to the application's OCID or
`OCI_FN_COMPARTMENT_ID` to its compartment's OCID. On each deployment, the script
queries the Object Storage namespace with `oci os ns get`. `3MOpenDataSource`
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

Direct uploads are limited to 25 MB; the script checks the JAR size before
uploading. OCI's exact-name function lookup is case sensitive, so use the
capitalization shown above when invoking or configuring this function.

OCI Dynamic Groups
traficinfo_fn: all Functions in traficinfo compartment
traficinfo_apigw: all APIGW in traficinfo compartment

Policies
grant-fn-access-to-use-objects: "Allow dynamic-group traficinfo_fn to use objects in compartment TraficInfo"
