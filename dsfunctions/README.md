The M3M function in the dsfunctions module downloads the csv and geojson files from

and store them in a bucket named ds3mbucket

Files are gzip-compressed before upload, keeping their original object names and
content types with `Content-Encoding: gzip`. Compression uses a temporary file
that is deleted after the upload, including on failure. Readers must support
gzip content decoding (or explicitly decompress the downloaded body).
Existing objects remain uncompressed until a changed source file is uploaded;
the source ETag check still skips unchanged files.

fn list context 
fn list apps

fn -v deploy --app 3MOpenDataProcessor

fn config function 3MOpenDataProcessor 3mopendatasource NAMESPACE frivwhhfdcjn
fn config function 3MOpenDataProcessor 3mopendatasource BUCKETNAME ds3mbucket


fn invoke 3MOpenDataProcessor 3mopendatasource

## Archive deployment (code-only function)

From the repository root, run `./deploy-archive.sh`. It builds the Maven
`jar-with-dependencies` artifact and uploads it directly to OCI Functions as
`3MOpenDataSource` in the existing `3MOpenDataProcessor` application. It uses
the managed `java21.ol9` runtime, which can run this project's Java 17 bytecode.
No Docker build or registry push is involved. On later runs, the script updates
the exact-name archive function. The existing `3mopendatasource` image-based
function remains separate.

The script uses OCI CLI credentials (`OCI_CLI_CONFIG_FILE` and
`OCI_CLI_PROFILE` if needed). It finds the application using
`oracle.compartment-id` in the current Fn context. Alternatively, set
`OCI_FN_APPLICATION_ID` to the application's OCID. On creation, the script
queries the Object Storage namespace with `oci os ns get` and sets function
configuration `NAMESPACE` and `BUCKETNAME` (`ds3mbucket`). Subsequent archive
updates retain the existing function configuration.

The OCI CLI commands submit asynchronous create or update requests. The script
captures the `opc-work-request-id` response header, polls that exact work
request for up to ten minutes, and exits successfully only when it reaches
`SUCCEEDED`.

For example:

```sh
export OCI_CLI_CONFIG_FILE="$HOME/.oci/config.arnaudq"
./deploy-archive.sh
fn invoke 3MOpenDataProcessor 3MOpenDataSource
```

Direct uploads are limited to 25 MB; the script checks the JAR size before
uploading. OCI's exact-name function lookup is case sensitive, so use the
capitalization shown above when invoking or configuring this function.

OCI Dynamic Groups
traficinfo_fn: all Functions in traficinfo compartment
traficinfo_apigw: all APIGW in traficinfo compartment

Policies
grant-fn-access-to-use-objects: "Allow dynamic-group traficinfo_fn to use objects in compartment TraficInfo"
