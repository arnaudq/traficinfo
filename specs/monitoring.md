# Monitoring

## TraficInfo metrics

Processing functions publish application metrics through the OCI Monitoring Java
SDK in the `traficinfo` namespace by default. Every metric below has unit `count`
and, except for `FunctionFailures`, represents a snapshot rather than an increment.
`FunctionFailures` counts handled application failures. Its Java emission is
implemented, along with deployment settings, manual response handling, and the
Terraform-managed alarms.
See the [functions README](../dsfunctions/README.md) for deployment and invocation.
The [Alarms and Notifications](#alarms-and-notifications) chapter describes the
function failure alarms based on OCI's built-in metrics and the custom
failure metric.

### `GeoJsonFeatures`

Final number of GeoJSON features published for one datasource and population,
after record filtering, grouping by counter and geometry selection. Each feature
can combine several observation years, so this metric has no year dimension.
Each successful publication emits both populations, including zero for an empty
collection.

| Dimension | Description | Values |
| --- | --- | --- |
| `datasourceId` | Datasource being exported | Bundled source ID, e.g. `DPT34`, `MMM` |
| `population` | Exported counter population | `VEHICULES`, `BICYCLES` |

### `GeoJsonRecordsBeforeFiltering`

Number of annual records loaded from the manifest for one datasource, population
and observation year, before GeoJSON record filters. Population selection occurs
before counting: records classified as neither vehicles nor bicycles are excluded.
This measures annual records, so a counter present in two years contributes once
to each year's count.

| Dimension | Description | Values |
| --- | --- | --- |
| `datasourceId` | Datasource being exported | Bundled source ID, e.g. `DPT34`, `MMM` |
| `population` | Exported counter population | `VEHICULES`, `BICYCLES` |
| `year` | Record's observation year | Year as a string, e.g. `2025`, `2026` |

### `GeoJsonRecordsAfterFiltering`

Number of annual records passing GeoJSON's year, counter/city and traffic-value
filters for one datasource, population and observation year. The rendering window
includes the current year and the preceding ten years; traffic values must be
strictly greater than zero and less than 300,000. Counter and city filters apply
when configured in the renderer. Statistics use the same filtered list as rendering.

This count precedes grouping, geometry selection and history truncation. A record
can pass the filters even when its counter has no geometry and produces no feature.
Use `GeoJsonFeatures` to measure the final rendered output size.

| Dimension | Description | Values |
| --- | --- | --- |
| `datasourceId` | Datasource being exported | Bundled source ID, e.g. `DPT34`, `MMM` |
| `population` | Exported counter population | `VEHICULES`, `BICYCLES` |
| `year` | Record's observation year | Year as a string, e.g. `2025`, `2026` |

### `OSMWayMappings`

Number of counter-to-way mappings in each status for one datasource, after the
hourly OSM refresh. Counts match the log's **all records** status totals, including
mappings not attempted this run. They include uploaded approvals and newly created
review candidates. They do not report only the processed-record subset.

| Dimension | Description | Values |
| --- | --- | --- |
| `datasourceId` | Datasource whose mapping cache was loaded | Source ID from `<datasourceId>_OSMWayMapping.json`, e.g. `MMM` |
| `status` | Mapping assignment outcome | `MISSING`, `MATCHED`, `NEEDS_REVIEW`, `NOT_FOUND` |

| Status | Meaning |
| --- | --- |
| `MISSING` | No usable assignment yet: lookup pending, coordinates unavailable, or an HTTP attempt failed |
| `MATCHED` | A road way is assigned from the seed/manual cache or an accepted reverse lookup |
| `NEEDS_REVIEW` | A cached candidate requires review because no supplied road reference or street name matched when comparison-based review was enabled (`REVIEW_ROAD_MISMATCHES=true`) |
| `NOT_FOUND` | A successful reverse response contained no suitable road way |

### `FunctionFailures`

Application failure metric in the `traficinfo` namespace, with unit
`count`. Each invocation that catches an application exception at the handler
boundary publishes one sample with value `1`, then returns a structured error
response normally. This lets OCI consider the invocation successful, avoiding
retries caused by an unhandled application exception, while retaining a separate
signal for email alerts. Both synchronous and detached invocations are covered.
Successful processing emits no sample. This is an event count, not a snapshot;
use `sum()` to count failed invocations in an interval.

| Dimension | Description | Values |
| --- | --- | --- |
| `applicationId` | Application containing the function | OCID of `3MOpenDataProcessor` |
| `resourceId` | Function that handled the exception | Function OCID |
| `resourceDisplayName` | Name identifying the affected function | E.g. `DataSourceFunction`, `OSMInfoFunction` |
| `datasourceId` | Datasource being processed when known | Bundled source ID, e.g. `MMM`, or `UNKNOWN` when unresolved, or `ALL` for an application-wide operation |

Publish once per failed invocation, even if several nested operations log the
same exception. Exception messages, stack traces and invocation identifiers belong
in logs and the error response, not metric dimensions. Caught, recoverable
per-record errors that allow processing to continue do not emit this metric.

Failure metric publication uses the existing `MetricsPublisher` abstraction and
runtime settings. All functions must receive metrics configuration and IAM
permissions. If publishing the failure metric fails (including missing metrics
configuration), log that reporting failure and rethrow the original application
exception. OCI then records an invocation error for the existing non-503 alarm.
This fallback permits caller retries, prioritizing failure visibility when the
custom monitoring path is unavailable. Timeouts, forced termination and failures
before the handler runs remain covered by OCI's built-in failure metrics.

The `TraficInfo-ApplicationFailures` alarm selects the application and
retains function and datasource dimensions:

```text
FunctionFailures[1m]{applicationId = "<application-ocid>"}.groupBy(resourceId, resourceDisplayName, datasourceId).sum() > 0
```

It has severity `CRITICAL`, a one-minute pending duration, and sends to the existing
`TraficInfoMonitoring` topic. It covers handled failures in scheduled runs and
manual invocations. The synchronous error response also lets `scripts/invoke.sh`
display exception details and exit unsuccessfully without invoking the function
again. The script disables OCI CLI retries, prints handled exception details to
standard error, and exits with status 1. Successful responses go to standard
output; OCI invocation errors retain their exit status and output. The Java
handler, metric emission, script feedback and deployment configuration are
implemented. The Terraform/OpenTofu monitoring module manages the additional alarm.

### GeoJSON publication

GeoJSON metrics are sent after both output uploads and OSM cache refresh succeed.
For every year represented in either exported population's input records, both
populations emit both record metrics, including zero when a population is absent
or all its records are filtered out. Years outside the rendering window still
have before counts and zero after counts. A completely empty input emits only
the two zero feature counts because there is no record year to attribute.

All observations from a publication share a timestamp and are submitted together
through `MetricsPublisher`. The OCI adapter splits requests at its 50-stream limit.
No previous-run counts or percentage metrics are stored.

### OSM publication

Each successful hourly run emits all four statuses for every loaded datasource,
including zero for empty categories and caches. Sources skipped by the
reverse-lookup time budget are still included. Unchanged mapping/review documents
still produce fresh samples. A run with no mapping caches emits no samples.
All observations from the run share a timestamp.

Metrics are published after a successful refresh and successful review-document
save (including a skipped write when unchanged). A load, refresh or review-save
failure suppresses the run's metrics and retains existing function failure
behavior. Metrics failures are logged without failing the refresh. Publishing
metrics does not change the reverse-lookup budget.

### Querying snapshots

Use the metric's dimensions to select the intended series. `max()` shows the
highest snapshot in each interval; with one publication in that interval, this
is the published count. `sum()` over repeated observations adds snapshots and
should not be used to represent the current count. Intervals without publication
have no data, rather than zero.

```text
GeoJsonFeatures[1h]{datasourceId = "MMM", population = "BICYCLES"}.max()
GeoJsonRecordsBeforeFiltering[1h]{datasourceId = "MMM", population = "BICYCLES", year = "2025"}.max()
GeoJsonRecordsAfterFiltering[1h]{datasourceId = "MMM", population = "BICYCLES", year = "2025"}.max()
OSMWayMappings[1h]{datasourceId = "MMM", status = "NEEDS_REVIEW"}.max()
```

### Configuration and permissions

| Runtime setting | Default |
| --- | --- |
| `METRICS_COMPARTMENT_ID` | Unset: warn and skip processing metrics; rethrow the original exception when failure reporting is required |
| `METRICS_NAMESPACE` | `traficinfo` |

Deployment sets `METRICS_COMPARTMENT_ID` to the shared `OCI_COMPARTMENT_ID` exported
by `scripts/setup.sh` for all function deployments. Each function's
resource-principal dynamic group needs an
additional IAM policy (substitute its name and compartment OCID):

```text
Allow dynamic-group <functions-group> to use metrics in compartment id <compartment-ocid> where target.metrics.namespace='traficinfo'
```

### Publication and failure behavior

The function needs network access to the regional Monitoring ingestion endpoint.
Metrics use resource-principal authentication by default, or the existing local
`arnaudq` OCI profile when `USE_RESOURCE_PRINCIPAL=false`. The client is created
and closed per nonempty publication batch, with a 2-second connection timeout, 3-second read
timeout, and no request retries. Initialization, transport, partial rejection and
close failures are logged without failing successful data publication. Failed
data/cache publication sends no publication snapshots. The
`FunctionFailures` sample is independent of those snapshots. No previous counts
or percentages are stored, and this does not create alarms or IAM policies.

### Backend abstraction

`MetricSample` and `MetricsPublisher` define the backend-independent publishing
boundary. New observations can reuse that interface. OCI SDK types, batching and
transport details are confined to `OciMetricsPublisher`.
`TraficInfoBaseFunction` reads metrics compartment and namespace settings once,
uses the shared authentication setting, and configures one backend through the
overridable `createMetricsPublisher()` factory. Clients are opened lazily for
nonempty batches and closed after publication, without caching a client between
invocations.

The inherited `publishMetrics()` method logs backend failures without failing
successful processing. GeoJSON and OSM pass this method to their processing
publishers. Failure events use the same backend directly: missing configuration,
transport, rejection or close failures rethrow the original application exception.
This preserves the built-in error alarm fallback. `GeoJsonPublisher` and
`OSMMappingRefresher` retain backend-independent `MetricsPublisher` injection.
The four-argument GeoJSON publisher constructor disables metrics for callers
that do not supply a backend.

## Alarms and Notifications

Function failures feed three OCI alarms. The application failure alarm covers
handled application exceptions through `FunctionFailures`. All three paths send
notifications through a shared topic to its confirmed email subscriptions. The aim
is to identify the affected function and provide a starting point for investigation.

### Chain of actions

```mermaid
flowchart TD
    subgraph functions["OCI Functions"]
        F["Any function in application<br/>3MOpenDataProcessor"]
    end

    subgraph monitoring["OCI Monitoring"]
        M["FunctionResponseCount<br/>responseType = Error"]
        A["TraficInfo-FunctionErrors<br/>Non-503 errors: at least 1 in 1 minute<br/>CRITICAL"]
        B["TraficInfo-FunctionAvailability<br/>503 responses: at least 3 in 15 minutes<br/>WARNING"]
        C["traficinfo: FunctionFailures<br/>One sample per handled failure"]
        D["TraficInfo-ApplicationFailures<br/>At least 1 handled failure in 1 minute<br/>CRITICAL"]
    end

    subgraph notifications["OCI Notifications"]
        T["Topic: TraficInfoMonitoring<br/>Function alarms publish here"]
        S["EMAIL subscription<br/>alice@example.com<br/>Must be confirmed for delivery"]
    end

    E["Email inbox"]

    F -->|"Failure recorded as a metric"| M
    M -->|"ErrorCode is not 503"| A
    M -->|"ErrorCode is 503"| B
    A -->|"Alarm fires and publishes a message"| T
    B -->|"Alarm fires and publishes a message"| T
    F -->|"Caught exception; return normally"| C
    C -->|"Group by function and datasource"| D
    C -->|"Reporting fails: rethrow original exception"| M
    D -->|"Alarm fires and publishes a message"| T
    T -->|"Delivers to each confirmed subscription"| S
    S -->|"Sends email"| E
```

### Components and relationships

| Component | Responsibility |
| --- | --- |
| Application and functions | `3MOpenDataProcessor` contains the functions being monitored. Each invocation contributes its execution metrics. |
| Metric | `FunctionResponseCount` in the `oci_faas` namespace counts responses. Both alarms select responses of type `Error`. `ErrorCode` is the numeric status (such as `503`); `ErrorMessage` is the symbolic cause (such as `FunctionInvokeServiceUnavailable`). |
| Custom metric | `FunctionFailures` in `traficinfo` counts handled application failures even when OCI records a successful invocation. |
| Alarms | Separate non-503 invocation failures, repeated 503 availability errors, and handled application failures. |
| Topic | `TraficInfoMonitoring` receives messages from the function alarms and the OSM review alarm and distributes them to its subscriptions. The topic itself contains no error-detection rule. |
| Subscription | Connects a topic to a delivery method and recipient. The EMAIL subscription delivers to `alice@example.com` after confirmation. |

The built-in alarms cover all functions in the application, including functions
added later. The application failure alarm covers functions emitting the custom
metric. They evaluate each function and error category separately, so one function can
trigger a notification independently of the others. This covers source
retrieval, preprocessing, GeoJSON generation and OSM maintenance when deployed
in that application.

A topic can have several subscriptions. All confirmed subscribers receive the
alarm messages published to it; adding a recipient changes delivery rather than
the alarm condition. Email subscriptions require confirmation before delivery.

### When notifications are sent

| Alarm | Condition per function and error category | Severity |
| --- | --- | --- |
| `TraficInfo-FunctionErrors` | At least one non-503 error in one minute | `CRITICAL` |
| `TraficInfo-FunctionAvailability` | At least three 503 responses in 15 minutes | `WARNING` |
| `TraficInfo-ApplicationFailures` | At least one handled application failure in one minute, per function and datasource | `CRITICAL` |

Non-503 failures include application execution errors, timeouts, image-loading
errors and other invocation failures. The availability alarm covers 503 responses
such as `FunctionInvokeServiceUnavailable`, which can occur before application
code runs. All three function alarms have a one-minute pending duration; the first breaching
evaluation can move the relevant stream from OK to firing.

The availability threshold reduces noise from transient errors; it is not an
OCI retry limit or proof that retries are exhausted. Resource Scheduler invokes
functions in detached mode, but the reviewed Oracle documentation does not
specify its maximum retry count for 503 responses. Two 503 responses followed
by a successful run do not meet the three-error threshold. Conversely, three
errors can meet it even if a later attempt succeeds within the same window.

An isolated 503 can still mean a missed run, without an email from these alarms.
Checking that a scheduled run eventually succeeded requires separate monitoring.

Email delivery takes several minutes because metrics must be collected and
evaluated before a notification is delivered. Existing OCI failure emails show
the function name, alarm state, time, error code and cause, and a link to the alarm,
with the function ID available for investigation. Proposed application failure
emails identify the function and datasource; exception details remain in logs
and the synchronous response. Repeated failures while that function's alarm state
remains firing can be grouped into one incident; this is not one email per failed
invocation. Periodic reminder emails are not configured.

A recovery notification can be sent when the alarm returns to OK. This means
the error condition has cleared; it does not prove that a later invocation
succeeded or that failed processing has been repaired.

### Configuration and operation

The Terraform/OpenTofu [monitoring module](../infra/modules/monitoring/oci/README.md)
creates and updates all five alarms: the three function failure alarms and the two
OSM status alarms. The dev environment supplies the existing application and topic
OCIDs through `function_application_id` and `notification_topic_id` in private
`~/.config/traficinfo/dev.tfvars`. The module does not inspect, create or change
email subscriptions; confirmed subscribers to the topic receive notifications.
There is no email variable or subscription check.

Use `tofu plan` to review changes and `tofu apply` to reconcile the definitions.
The former `setup-function-alerts.sh` and `setup-osm-review-alert.sh` scripts have
been removed. Setup commands are in the
[setup specification](setup.md#function-failure-emails).

After receiving an alert, use the function's resource ID to find its metrics
and invocation logs. Inspect the failed operation and any outputs already
written before retrying. The alert mechanism does not retry functions or roll
back their writes. Confirm recovery through a successful run and the expected
data, rather than relying solely on the alarm returning to OK.

### OSM review reminders

`TraficInfo-OSMWayMappingsNeedReview` is a separate `WARNING` alarm in namespace
`traficinfo`. It evaluates each datasource's review count using:

```text
OSMWayMappings[1h]{status = "NEEDS_REVIEW"}.groupBy(datasourceId).max() > 0
```

Keeping `datasourceId` in the result lets formatted emails identify affected
sources and their counts. **Group notifications across metric streams** combines
qualifying sources into one alarm-level message. The alarm uses a one-minute
pending duration and `repeat_notification_duration = "PT24H"` for daily reminders while
continuously firing. It sends through the existing `TraficInfoMonitoring` topic
selected by `notification_topic_id`. Delivery uses the topic's confirmed subscriptions.

The monitoring module manages this alarm alongside the other four. Use the shared
Terraform plan/apply workflow in [Configuration and operation](#configuration-and-operation).
The existing topic and its subscriptions are reused without recipient verification.

The email points to `OSMWayMapping_NEEDS_REVIEW.json` in the OSM bucket. Uploaded
approvals are applied by the next hourly OSM refresh. Counts are maxima within the
last hour, so cleared reviews can remain visible until an older sample leaves
the window. An hourly run that is late or fails can cause a gap and an alarm reset.
Daily repeats are not a strict email cap: recovery, resets and later firing episodes
can produce additional messages. This tradeoff is accepted for the manual review
workflow, where the queue is expected to change slowly.

### OSM ways not found

`TraficInfo-OSMWayMappingsNotFound` adds a separate `WARNING` alarm for successful
reverse lookups that returned no suitable road way. It uses:

```text
OSMWayMappings[1h]{status = "NOT_FOUND"}.groupBy(datasourceId).max() > 0
```

It uses the same topic, grouped datasource details, one-minute pending duration
and daily reminders as the review alarm. Terraform manages both alarms together.
The `NOT_FOUND` email points to each affected `<datasourceId>_OSMWayMapping.json`
cache and the stored reverse response; these records are not included in the
dedicated `NEEDS_REVIEW` document.

Daily repeats apply independently to each alarm. If both categories are nonzero,
you can receive one reminder from each alarm per day while continuously firing,
plus any recovery/reset or later firing notifications.

### Coverage and further monitoring

The alarms detect errors recorded by OCI Functions, including runtime failures
such as timeouts. The application failure alarm also detects handled application
failures reported through `FunctionFailures`. Successful invocations with
recoverable warnings do not trigger these failure alarms. Functions outside the
selected application or region require their own monitoring setup.

Other monitoring signals answer different questions:

- **Invocation counts:** whether scheduled functions actually ran.
- **Duration and throttling:** whether execution is becoming slow or constrained.
- **Publication metadata and manifests:** whether expected data is available
  and sufficiently recent.

These signals can support future alerts for missed runs, stale data or incomplete
processing. Those alerts are not part of the current function error mechanism.

References: [function error metrics](https://docs.oracle.com/en-us/iaas/Content/Functions/Reference/functionsmetrics.htm),
[scheduled invocation mode](https://docs.oracle.com/en-us/iaas/Content/Functions/Tasks/functionsschedulingfunctions-about.htm),
and [503 troubleshooting](https://docs.oracle.com/en-us/iaas/Content/Functions/Tasks/functionstroubleshooting_topic-Issues-invoking-functions.htm).
