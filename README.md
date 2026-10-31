# TraficInfo

## Map templates

`mapconfig.json` defines maps and their ordered data source IDs from
`dsconfig.json`. Run `./scripts/generate-map-templates.sh` after setup to generate
the MMM (MMM + DPT34) and NIM (NIM + DPT30) uMap files. See the
[map template README](map-templates/README.md) for usage and customization.

## Setup

Install JDK 25, the Fn Project CLI, OCI CLI, and `jq`, and place your OCI configuration at `~/.oci/config.arnaudq`. Then, from the project root, run:

```sh
source ./scripts/setup.sh
```

Source this script before running the other shell scripts. It exports `JAVA_HOME`,
`TRAFICINFO_ROOT`, `TRAFICINFO_DSCONFIG`, `OCI_CLI_CONFIG_FILE`,
`OCI_COMPARTMENT_ID`, `OCI_OBJECT_STORAGE_NAMESPACE`, and
`OCI_FN_APPLICATION_NAME`. It resolves the compartment and storage namespace
once, configures the Fn context, and lists deployed applications. Other scripts
consume these exported values. Bash and zsh are supported when sourcing setup.

Set `OCI_CLI_CONFIG_FILE` or `OCI_COMPARTMENT_ID` before sourcing setup to
override the default credentials file or `TraficInfo` compartment. The older
`OCI_FN_COMPARTMENT_ID`, `OCI_BUCKET_COMPARTMENT_ID`, and
`OCI_SCHEDULE_COMPARTMENT_ID` overrides are also accepted during setup, in that
order after `OCI_COMPARTMENT_ID`. Re-source setup after changing OCI credentials
or the selected compartment. Application lookup stays in the scripts that need
it, with `OCI_FN_APPLICATION_ID` available as an explicit override in deploy
and invoke, as well as event setup.

`scripts/oci-common.sh` contains reusable shell functions only. Setup and each executable
source it explicitly; it performs no OCI calls or environment initialization
when sourced. It provides data source defaults and naming, application/function
lookups, unique-match selection, logging, bucket-name validation, and dry-run
argument parsing. Deployment permits missing functions so it can create them;
invocation and event setup require existing active functions. Duplicate matches
and failed lookups are errors.

## Object Storage buckets

Run `./scripts/setup-buckets.sh` to create or update the configured input buckets and
the shared `traficinfo` and `osminfo` buckets in
the compartment selected by `source ./scripts/setup.sh`. It uses the exported
`OCI_COMPARTMENT_ID`, `OCI_OBJECT_STORAGE_NAMESPACE`, and OCI credentials.

Input buckets are read from `dsconfig.json`, using the shared naming defaults
and overrides. Currently these are `ds3mbucket`, `dsdpt30`, `dsdpt34`, `dsmrn` and `dsnim`. All have object
events enabled and are private on creation. `osminfo` is private. `traficinfo`
uses `ObjectReadWithoutList`: anyone can download an object using its URL,
while listing objects requires authentication. All buckets use Standard
storage on creation. Existing objects, metadata, and unrelated bucket settings
are preserved. Repeated runs skip buckets that already meet these settings;
updates change only the requested settings and verify the result.

Use `./scripts/setup-buckets.sh --dry-run` to preview changes without submitting them.
Enabling bucket events prepares each input bucket for an OCI Events rule; this script
does not create the rule that invokes the GeoJSON function.

## CSV events and GeoJSON generation

After running `./scripts/setup-buckets.sh` and deploying with `./scripts/deploy-functions.sh`,
run `./scripts/setup-events.sh` to create or update one Events rule for every entry with
GeoJSON enabled in `dsconfig.json`, targeting GeoJSON functions in `3MOpenDataProcessor`:

| Source ID | Events rule | CSV object | GeoJSON function |
| --- | --- | --- | --- |
| MMM | `3MComptageCsvToGeoJson` | `ds3mbucket/MMM_MMM_Comptage.csv` | `3MGeoJsonOut` |
| DPT34 | `DPT34ComptageCsvToGeoJson` | `dsdpt34/DPT34_Comptage.csv` | `DPT34GeoJsonOut` |
| DPT30 | `DPT30ComptageCsvToGeoJson` | `dsdpt30/DPT30_Comptage.csv` | `DPT30GeoJsonOut` |
| MRN | `MRNComptageCsvToGeoJson` | `dsmrn/MRN_Comptage.csv` | `MRNGeoJsonOut` |
| NIM | `NIMComptageCsvToGeoJson` | `dsnim/NIM_Comptage.csv` | `NIMGeoJsonOut` |

Names default to function `${id}GeoJsonOut`, rule `${id}ComptageCsvToGeoJson`,
bucket `ds${lowercase(id)}`, and CSV `${id}_Comptage.csv`. Optional per-source
`functionPrefix`, `inputBucket`, and `csvObject` fields override these defaults.
MMM supplies all three overrides to retain its existing resource names; DPT34
uses the defaults. `functionPrefix` affects both the rule and function name.

NIM selects the `nim-2023-2025` dataset and enables GeoJSON in `dsconfig.json`.
Use `./scripts/deploy-functions.sh ds NIM` and `./scripts/invoke.sh ds NIM`
to retrieve it. The source uses bucket `dsnim`, Object `NIM_Comptage.csv`,
a 300-second runtime timeout and the Monthly schedule tag.
Deploy and run preprocessing with `./scripts/deploy-functions.sh preprocess NIM`
and `./scripts/invoke.sh preprocess NIM` to publish its yearly Objects and manifest.
Then deploy `NIMGeoJsonOut` with `./scripts/deploy-functions.sh geojson NIM`
and invoke it with `./scripts/invoke.sh geojson NIM` to publish
`NIM_TraficInfo.geojson`. See the [NIM function documentation](dsfunctions/README.md#nim-functions).
Preprocessing remains manual: existing CSV events invoke GeoJSON against the
latest published manifest. Run preprocessing after source changes; automatic
source-to-preprocessing-to-GeoJSON routing remains a later step.

Each rule matches create and update events for exactly its configured CSV in
its bucket, compartment, and Object Storage namespace. Archive objects,
companion JSON, and output GeoJSON do not match. Input buckets must exist with
object events enabled. Deployment bucket overrides must agree with the input
bucket configured here.

The script uses OCI CLI and `jq` directly, shell functions, and the environment
exported by `source ./scripts/setup.sh`, including the data source config path,
application name, compartment, and storage namespace. Credentials
must allow managing Events rules and configuring Functions actions; functions
need access to their inputs and outputs. See
[Oracle's Events IAM policies](https://docs.oracle.com/en-us/iaas/Content/Events/Concepts/eventspolicy.htm).

```sh
./scripts/setup-events.sh --dry-run
./scripts/setup-events.sh
```

Dry runs show proposed changes for every source without submitting them.
Progress messages identify discovery, validation, and apply steps. Repeated
runs skip matching rules; updates replace each dedicated rule's filter and
complete action list. Duplicate rule names and ambiguous function names cause
an error. No ETags or tool-availability checks are used. The script waits for
active rules and verifies their conditions and enabled function actions.
Existing CSV objects are not processed until the next matching object event.

## Hourly, daily, weekly and monthly schedules

Run `./scripts/setup-schedules.sh` to create or reconcile `Hourly` (at **the start of every hour**),
`Daily`, `Weekly` (Wednesdays), and `Monthly` (the 5th of each month). The latter
three run at **15:30 UTC**; all schedules use UTC. Each uses dynamic
resource filters selecting only `FunctionsFunction` resources in `TraficInfo`
(excluding child compartments) with defined tag `TraficInfo.DataSourceSchedule`
equal to its name: `Hourly`, `Daily`, `Weekly`, or `Monthly`.

Create the `TraficInfo` tag namespace and `DataSourceSchedule` key definition
first, with allowed values `Hourly`, `Daily`, `Weekly`, and `Monthly`. Apply the
appropriate value directly to each data source function. The script does not tag functions
or attach function OCIDs; OCI selects matching functions dynamically at run time.
Untagged functions do not match. Deployment with `./scripts/deploy-functions.sh` reads
`dsconfig.json` and sets `TraficInfo.DataSourceSchedule` on the data source
functions: `MMM` maps to `3MOpenDataSource`, and `DPT34` maps to
`DPT34OpenDataSource`. Current schedules are Daily and Monthly respectively.
Both creation and updates set the data source function's complete defined-tag
map to the configured scheduling tag. Deployment also tags `OSMInfoFunction` as
`Hourly` so it refreshes incomplete OSM way mappings at the start of every hour.
GeoJSON functions receive no scheduling tag from deployment. Deploy the intended
functions before converting an existing schedule from static targets to filters.

```sh
./scripts/setup-schedules.sh --dry-run
./scripts/setup-schedules.sh
```

Timing is defined directly in the script, using settings recorded previously
from Daily. No schedule serves as a template, and Daily may be missing.
Existing schedules are reconciled to the configured timing and filters, clearing
any explicit resource list. Tags, descriptions, and start/end dates are preserved.
Already matching schedules are skipped. Duplicate names (matched
case-insensitively) cause an error. Updates do not use ETags.

Uses shell functions, OCI CLI, and `jq`, without checking tool availability.
Uses the OCI credentials and `OCI_COMPARTMENT_ID` exported by
`source ./scripts/setup.sh`.

Dry runs read OCI and show proposed creates and updates without submitting them.
Normal runs wait for each operation to succeed and verify timing, filters, and
absence of static targets. OCI permits duplicate names; avoid simultaneous runs,
as the pre-creation recheck cannot make creation atomic.

## Function failure emails

See the [monitoring and alerts specification](specs/monitoring.md) for the
alert flow, failure coverage, limits and monitoring extensions.

Use the `TraficInfoMonitoring` Notifications topic with a confirmed email
subscription:

```sh
./scripts/setup-function-alerts.sh arnaudq@quillaud.org --dry-run
./scripts/setup-function-alerts.sh arnaudq@quillaud.org
```

Run after `source ./scripts/setup.sh`. Set `OCI_FN_ALERT_TOPIC_NAME` to use
another existing topic in the selected compartment. The script checks that the
supplied address has an active EMAIL subscription; it does not create a topic
or subscription or change existing bucket Events rules. All subscribers to the
chosen topic receive the alarm notifications.

Two alarms monitor `oci_faas.FunctionResponseCount` with `responseType = "Error"`,
filtered to the application and grouped by function OCID, display name, error
code and cause:

| Alarm | Trigger per function and error category | Severity |
| --- | --- | --- |
| `TraficInfo-FunctionErrors` | At least 1 non-503 error in 1 minute | `CRITICAL` |
| `TraficInfo-FunctionAvailability` | At least 3 HTTP 503 errors in 15 minutes | `WARNING` |

Both use a one-minute pending duration. The 503 threshold reduces noise; it is
not OCI's retry limit and does not determine whether a later retry succeeded.
An isolated 503 that causes a missed run can remain without an email. Successful-run
monitoring is separate. Emails show the function name, alarm state, time, error
code and cause, and a link to the alarm.
New functions in the application are covered automatically. Delivery takes
several minutes because OCI must ingest and evaluate the metrics. Repeated
failures while a function's alarm remains firing are grouped; this is not one
email per failed invocation. OCI also sends alarm recovery notifications.

Repeated setup runs skip matching alarms or reconcile their settings. The
script creates availability coverage before renaming and narrowing the legacy
`3MOpenDataProcessor-FunctionErrors` alarm to non-503 errors. Both definitions
are checked before any writes. Duplicate names, simultaneous legacy/replacement
error alarms, failed lookups, missing confirmed subscriptions and
existing alarm suppression stop setup. Credentials need access to read Functions
applications and Notifications topics/subscriptions and manage Monitoring alarms.

GeoJSON and OSM handlers throw on invocation failure so OCI records an error,
instead of returning a successful response containing `KO`. Deploy the updated
handlers to cover these failures. Download and preprocessing handlers already
throw on failures; runtime errors and timeouts are also monitored. Successful
invocations with recoverable OSM lookup warnings do not trigger this alarm.
See [OCI function metrics](https://docs.oracle.com/en-us/iaas/Content/Functions/Reference/functionsmetrics.htm).

## Shell script checks

Run `python3 -m unittest discover -s tests -v` for the shell integration tests.
They use a local fake OCI CLI to check config mappings, create/update behavior,
dry runs, repeated runs, lookup failures, and bash/zsh setup. No cloud resources
are accessed or changed.
