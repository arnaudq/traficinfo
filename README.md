# TraficInfo

## Setup

Install JDK 25, the Fn Project CLI, OCI CLI, and `jq`, and place your OCI configuration at `~/.oci/config.arnaudq`. Then, from the project root, run:

```sh
source ./setup.sh
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
and invoke.

## Object Storage buckets

Run `./setup-buckets.sh` to create or update `ds3mbucket` and `traficinfo` in
the compartment selected by `source ./setup.sh`. It uses the exported
`OCI_COMPARTMENT_ID`, `OCI_OBJECT_STORAGE_NAMESPACE`, and OCI credentials.

`ds3mbucket` has object events enabled and is private on creation. `traficinfo`
uses `ObjectReadWithoutList`: anyone can download an object using its URL,
while listing objects requires authentication. Both buckets use Standard
storage on creation. Existing objects, metadata, and unrelated bucket settings
are preserved. Repeated runs skip buckets that already meet these settings;
updates change only the requested settings and verify the result.

Use `./setup-buckets.sh --dry-run` to preview changes without submitting them.
Enabling bucket events prepares `ds3mbucket` for an OCI Events rule; this script
does not create the rule that invokes the GeoJSON function.

## CSV events and GeoJSON generation

After running `./setup-buckets.sh` and deploying with `./deploy-functions.sh`,
run `./setup-events.sh` to create or update one Events rule for every entry in
`dsconfig.json`, targeting GeoJSON functions in `3MOpenDataProcessor`:

| Source ID | Events rule | CSV object | GeoJSON function |
| --- | --- | --- | --- |
| MMM | `3MComptageCsvToGeoJson` | `ds3mbucket/MMM_MMM_Comptage.csv` | `3MGeoJsonOut` |
| DPT34 | `DPT34ComptageCsvToGeoJson` | `dsdpt34/DPT34_Comptage.csv` | `DPT34GeoJsonOut` |

Names default to function `${id}GeoJsonOut`, rule `${id}ComptageCsvToGeoJson`,
bucket `ds${lowercase(id)}`, and CSV `${id}_Comptage.csv`. Optional per-source
`functionPrefix`, `inputBucket`, and `csvObject` fields override these defaults.
MMM supplies all three overrides to retain its existing resource names; DPT34
uses the defaults. `functionPrefix` affects both the rule and function name.

Each rule matches create and update events for exactly its configured CSV in
its bucket, compartment, and Object Storage namespace. Archive objects,
companion JSON, and output GeoJSON do not match. Input buckets must exist with
object events enabled. Deployment bucket overrides must agree with the input
bucket configured here.

The script uses OCI CLI and `jq` directly, shell functions, and the environment
exported by `source ./setup.sh`, including the data source config path,
application name, compartment, and storage namespace. Credentials
must allow managing Events rules and configuring Functions actions; functions
need access to their inputs and outputs. See
[Oracle's Events IAM policies](https://docs.oracle.com/en-us/iaas/Content/Events/Concepts/eventspolicy.htm).

```sh
./setup-events.sh --dry-run
./setup-events.sh
```

Dry runs show proposed changes for every source without submitting them.
Progress messages identify discovery, validation, and apply steps. Repeated
runs skip matching rules; updates replace each dedicated rule's filter and
complete action list. Duplicate rule names and ambiguous function names cause
an error. No ETags or tool-availability checks are used. The script waits for
active rules and verifies their conditions and enabled function actions.
Existing CSV objects are not processed until the next matching object event.

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
Untagged functions do not match. Deployment with `./deploy-functions.sh` reads
`dsconfig.json` and sets `TraficInfo.DataSourceSchedule` on the data source
functions: `MMM` maps to `3MOpenDataSource`, and `DPT34` maps to
`DPT34OpenDataSource`. Current schedules are Daily and Monthly respectively.
Both creation and updates set the data source function's complete defined-tag
map to the configured scheduling tag. GeoJSON functions receive no scheduling tag from
deployment. Deploy the intended functions before converting an existing
schedule from static targets to filters.

```sh
./setup-schedules.sh --dry-run
./setup-schedules.sh
```

Timing is defined directly in the script, using settings recorded previously
from Daily. No schedule serves as a template, and Daily may be missing.
Existing schedules are reconciled to the configured timing and filters, clearing
any explicit resource list. Tags, descriptions, and start/end dates are preserved.
Already matching schedules are skipped. Duplicate names (matched
case-insensitively) cause an error. Updates do not use ETags.

Uses shell functions, OCI CLI, and `jq`, without checking tool availability.
Uses the OCI credentials and `OCI_COMPARTMENT_ID` exported by
`source ./setup.sh`.

Dry runs read OCI and show proposed creates and updates without submitting them.
Normal runs wait for each operation to succeed and verify timing, filters, and
absence of static targets. OCI permits duplicate names; avoid simultaneous runs,
as the pre-creation recheck cannot make creation atomic.
