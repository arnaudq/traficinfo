The M3M function in the dsfunctions module downloads Montpellier Méditerranée
Métropole traffic counts in CSV and GeoJSON format and stores them in the
`ds3mbucket` Object Storage bucket.

Files are gzip-compressed before upload, keeping their original object names and
content types with `Content-Encoding: gzip`. Compression uses a temporary file
that is deleted after the upload, including on failure. Readers must support
gzip content decoding (or explicitly decompress the downloaded body).
Existing objects remain uncompressed until a changed source file is uploaded;
the source ETag check still skips unchanged files.

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
`OCI_FN_COMPARTMENT_ID` to its compartment's OCID. On creation, the script
queries the Object Storage namespace with `oci os ns get`. `3MOpenDataSource`
receives `NAMESPACE` and `BUCKETNAME`; `3MGeoJsonOut` receives `NAMESPACE`,
`BUCKETNAME_IN`, and `BUCKETNAME_OUT`. The input bucket defaults to `ds3mbucket`
and can be overridden with environment variable `BUCKETNAME_IN`. The output bucket
defaults to the existing `traficinfo` bucket in the same compartment and can be
overridden with `BUCKETNAME_OUT`. Subsequent archive updates retain existing function configuration;
the bucket variables only apply when creating functions.

To add another function, add a name, handler, and creation-config builder to the
`functions` registry in the script, then define its config builder. The shared
deployment flow validates every handler class before uploading and prepares
all creation configs before modifying any function.

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
