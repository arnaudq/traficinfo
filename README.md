# TraficInfo

## Setup

Install JDK 25 and Homebrew, and place your OCI configuration at `~/.oci/config.arnaudq`. Then, from the project root, run:

```sh
source ./setup.sh
```

Source the script so `JAVA_HOME` and `OCI_CLI_CONFIG_FILE` remain set in your current shell. The script installs the Fn Project CLI and OCI CLI through Homebrew if needed, configures the current Fn context for the active `TraficInfo` compartment and its regional Functions API URL, then lists the deployed Fn applications.

## Object Storage buckets

Run `./setup-buckets.sh` to create or update `ds3mbucket` and `traficinfo` in
the active `TraficInfo` compartment. Requires OCI CLI and `jq`. It uses
`OCI_CLI_CONFIG_FILE` (defaulting to `~/.oci/config.arnaudq`) and the configured
profile and region. Set `OCI_BUCKET_COMPARTMENT_ID` to select a compartment by
OCID instead of looking up `TraficInfo` by name.

`ds3mbucket` has object events enabled and is private on creation. `traficinfo`
uses `ObjectReadWithoutList`: anyone can download an object using its URL,
while listing objects requires authentication. Both buckets use Standard
storage on creation. Existing objects, metadata, and unrelated bucket settings
are preserved. Repeated runs skip buckets that already meet these settings;
updates use ETags to reject concurrent edits and verify the resulting settings.

Use `./setup-buckets.sh --dry-run` to preview changes without submitting them.
Enabling bucket events prepares `ds3mbucket` for an OCI Events rule; this script
does not create the rule that invokes the GeoJSON function.

## Daily function schedule

Run `./attach-daily-schedule.sh` to attach `3MOpenDataSource` in
`3MOpenDataProcessor` to the existing `Daily` schedule in `TraficInfo`.
Requires OCI CLI and `jq`. It uses `OCI_CLI_CONFIG_FILE` (defaulting to
`~/.oci/config.arnaudq`) and the configured profile and region.

Use `./attach-daily-schedule.sh --dry-run` to preview the resource list without
changing OCI. The script preserves existing resources and invocation parameters,
and leaves the schedule's daily 15:30 UTC timing unchanged. Repeated runs skip
the update when the function is already attached. Missing or ambiguous names,
inactive schedules, and concurrent changes cause an error. The script waits for
the update to succeed and verifies the attachment before reporting success.
