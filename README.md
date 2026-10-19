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

## CSV events and GeoJSON generation

After running `./setup-buckets.sh` and deploying the functions with
`./deploy-functions.sh`, run `./setup-events.sh` to create or update the dedicated
`3MComptageCsvToGeoJson` Events rule. It invokes `3MGeoJsonOut` in
`3MOpenDataProcessor` for create and update events on exactly
`ds3mbucket/MMM_MMM_Comptage.csv`. The condition also matches the selected
compartment and Object Storage namespace. Archive objects, the companion JSON,
and the output GeoJSON do not match.

The script requires OCI CLI and `jq` and uses the same credentials, region,
and `OCI_BUCKET_COMPARTMENT_ID` override as `setup-buckets.sh`. It requires the
input bucket to exist with object events enabled, and resolves exactly one
active application and function by their case-sensitive names. Credentials
must have permission to manage Events rules and configure Functions actions;
the function needs permission to read its inputs and write its output. See
[Oracle's Events IAM policies](https://docs.oracle.com/en-us/iaas/Content/Events/Concepts/eventspolicy.htm).

```sh
./setup-events.sh --dry-run
./setup-events.sh
```

Dry runs show the proposed filter and function action without submitting changes.
Progress messages on stderr identify each discovery, validation, and apply step,
including the wait for an active rule. Failures report the step that failed.
Repeated runs skip an already configured rule. Updates reconcile the dedicated
rule's complete filter and action list, preserving tags, description, and other
rules. Duplicate rule names and concurrent edits cause an error. The script
waits for the rule to become active and verifies the filter and enabled function
action. Setup does not process the CSV already stored; the next matching object
event triggers processing.

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

## Daily, weekly and monthly schedules

Run `./setup-schedules.sh` to create or reconcile `Daily`, `Weekly` (Wednesdays),
and `Monthly` (the 5th of each month), all at **15:30 UTC**. Each uses dynamic
resource filters selecting only `FunctionsFunction` resources in `TraficInfo`
(excluding child compartments) with defined tag `TraficInfo.DataSourceSchedule`
equal to its name: `Daily`, `Weekly`, or `Monthly`.

Create the `TraficInfo` tag namespace and `DataSourceSchedule` key definition
first, with allowed values `Daily`, `Weekly`, and `Monthly`. Apply the appropriate
value directly to each data source function. The script does not tag functions
or attach function OCIDs; OCI selects matching functions dynamically at run time.
Untagged functions do not match. Tag the intended functions before converting
an existing schedule from static targets to filters.

```sh
./setup-schedules.sh --dry-run
./setup-schedules.sh
```

Timing is defined directly in the script, using settings recorded previously
from Daily. No schedule serves as a template, and Daily may be missing.
Existing schedules are reconciled to the configured timing and filters, clearing
any explicit resource list. Tags, descriptions, and start/end dates are preserved.
Already matching schedules are skipped. Updates use ETags to reject concurrent
edits; duplicate names (matched case-insensitively) cause an error.

Uses shell functions, OCI CLI, and `jq`, without checking tool availability.
Uses the configured OCI profile and region, with `OCI_CLI_CONFIG_FILE` defaulting
to `~/.oci/config.arnaudq`. The compartment defaults to `TraficInfo`; set
`OCI_SCHEDULE_COMPARTMENT_ID` (or `OCI_BUCKET_COMPARTMENT_ID`) to select it by OCID.

Dry runs read OCI and show proposed creates and updates without submitting them.
Normal runs wait for each operation to succeed and verify timing, filters, and
absence of static targets. OCI permits duplicate names; avoid simultaneous runs,
as the pre-creation recheck cannot make creation atomic.
