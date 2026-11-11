# OpenStreetMap data

## Purpose

Traffic-count datasets do not always provide an OpenStreetMap (OSM) way ID or
maximum speed. We use OSM data to associate a counter with its road and enrich
its GeoJSON with the road's posted speed limit. This complements the measured
mean and V85 speeds supplied by data sources.

A source-provided way ID takes precedence. For sources such as DPT34, which
provide coordinates but no way ID, we maintain a counter-to-way mapping.

## REST APIs

We use two APIs:

- **OSM way API:** `https://api.openstreetmap.org/api/0.6/way/{wayId}.json`
  returns a way's tags. We retain those tags and use `maxspeed` to populate
  `Comptage.maximumSpeed` and the GeoJSON property `maximum_speed`. Supported
  numeric values are interpreted as km/h; missing or unsupported values remain
  unknown rather than being guessed.
- **Nominatim reverse API:** `https://nominatim.openstreetmap.org/reverse`
  finds an OSM object near a counter's latitude and longitude. We request road
  level results and accept only suitable road ways. A supported road classification
  may come from `category=highway` and `type`, or from `extratags.highway`; this
  also accepts road ways classified as administrative boundaries by Nominatim.
  Where available, the source road reference is compared with OSM's `namedetails.ref` (using `namedetails=1`), allowing formatting differences
  such as `D0011` versus `D 11` or `D0111E01` versus `D 111E1`. If a source reference is provided, a missing or
  differing OSM reference leaves a candidate for review instead of assigning
  the way automatically.

Reverse geocoding can return a nearby different road or another kind of object.
The response is therefore a candidate to validate, not proof of the correct road.
Existing positive way IDs are preserved.

## Candidate acceptance

Automatic validation uses the Nominatim fields `osm_type`, `osm_id`, `category`,
`type`, `extratags.highway`, and `namedetails.ref`, together with the source road
identifier. The first four are response fields rather than OSM tags.

The following table checks whether the candidate is a suitable road way before
comparing references. `any` means that field does not affect that row's result.
A valid ID is a positive integer fitting in a Java `long`, supplied as a JSON
integer or a string containing only digits.

| `osm_type` | `osm_id` | `category` | `type` | `extratags.highway` | Decision |
| --- | --- | --- | --- | --- | --- |
| `way` | Valid | `highway` | Supported road type | Any | Compare references below |
| `way` | Valid | `boundary` | `administrative` | Supported road type | Compare references below |
| `way` | Valid | Any | Any | Supported road type | Compare references below |
| `node`, `relation`, other, or missing | Any | Any | Any | Any | `NOT_FOUND` (`no_road_way`) |
| `way` | Missing, zero, negative, fractional, malformed, or overflowing | Any | Any | Any | `NOT_FOUND` (`no_road_way`) |
| `way` | Valid | `highway` | Unsupported or missing | Unsupported or missing | `NOT_FOUND` (`no_road_way`) |
| `way` | Valid | Other or missing | Any | Unsupported or missing | `NOT_FOUND` (`no_road_way`) |

Supported road types for either `type` or `extratags.highway` are:
`motorway`, `trunk`, `primary`, `secondary`, `tertiary`, `unclassified`,
`residential`, `living_street`, `service`, `road`, `motorway_link`, `trunk_link`,
`primary_link`, `secondary_link`, `tertiary_link`, `footway`, `cycleway`, `path`,
`pedestrian`, and `track`. These are accepted regardless of the counter's transport type, without
checking bicycle-access tags. Either road-classification path can qualify
the way; an unsupported value in one path does not override a supported value
in the other.

For example, the DPT76 response with `category=boundary`, `type=administrative`,
and `extratags.highway=primary` qualifies as a road. Its `namedetails.ref=D 925`
matches source identifier `D0925`, producing `MATCHED`.

### Road reference comparison

For a suitable road way, the following table determines whether its reference
is accepted. Rows are the `Comptage` road identifier; columns are OSM's
`namedetails.ref`. `null` means no reference, and `A` and `B` represent distinct
references after normalization (for example, `D0011` and `D 11` both represent `A`).

| Comptage road identifier \ OSM `namedetails.ref` | `null` | `A` | `B` |
| --- | --- | --- | --- |
| `null` | `MATCHED` | `MATCHED` | `MATCHED` |
| `A` | `NEEDS_REVIEW` | `MATCHED` | `NEEDS_REVIEW` |
| `B` | `NEEDS_REVIEW` | `NEEDS_REVIEW` | `MATCHED` |

When a source identifier exists, `NEEDS_REVIEW` has reason `missing_osm_ref`
if the OSM reference is absent, or `different_ref` if it differs. Without a
source identifier, a suitable road way is accepted regardless of its reference.
OSM references separated by `;` are compared individually; any normalized match
is sufficient. Fields such as `name`, `address`, `maxspeed`, `lanes`, and
`admin_level` do not determine candidate acceptance.

## Object ownership and formats

Request producers and the OSM function use separate output objects so their
cache reads and writes can overlap without replacing each other's updates.

OSM objects live in the `osminfo` bucket. `<sourceId>` is the data source prefix,
for example `MRN`, `MMM`, or `DPT34`.

| Object | Writer | Readers | Contents |
| --- | --- | --- | --- |
| `<sourceId>_OSMRequests.json` | That source's GeoJSON function | OSM function | Current unresolved coordinates and requested way-tag refreshes |
| `<sourceId>_OSMWayMapping.json` | OSM function | GeoJSON and OSM functions | Counter ID → assignment, coordinates, references, status, timestamps, and original reverse response |
| `<sourceId>_OSMInfo.json` | OSM function | GeoJSON and OSM functions | Way ID → tags and last successful refresh timestamp |
| `OSMWayMapping_NEEDS_REVIEW.json` | OSM function | Human reviewer | Data source → counter ID → `NEEDS_REVIEW` record |
| `OSMWayMapping_NOT_FOUND.json` | OSM function | Human reviewer | Data source → counter ID → `NOT_FOUND` record |
| `OSMWayMapping_NEEDS_REVIEW_MERGED.json` | Human reviewer uploads; OSM function deletes after successful processing | OSM function | Approvals from either review file, grouped by data source and counter ID |

GeoJSON generation filters by count, year, population, and other output predicates
before requesting OSM enrichment. Each successful run publishes its source's
request snapshot after producing both vehicle and bicycle GeoJSON. It includes
empty sections when no work remains. GeoJSON functions read the OSM caches and
publish their own request objects; they do not update the mapping/tag caches or
call the OSM APIs.

### Request format

For example, `MRN_OSMRequests.json` can contain both kinds of work:

```json
{
  "reverseLookups": {
    "MRN_237": {
      "latitude": 49.43,
      "longitude": 1.08,
      "sourceRoadReference": null
    }
  },
  "wayTagRefreshes": [123456, 789012]
}
```

The counter ID and coordinates identify a reverse request. The optional
`sourceRoadReference` validates the returned candidate. A direct tag request
needs only its positive way ID. The filename identifies the data source; status,
lookup results, tags, and update timestamps belong in the caches.

The OSM function leaves request objects unchanged. It checks the caches before
acting: satisfied requests are skipped, cached `NEEDS_REVIEW`/`NOT_FOUND`
outcomes are reused at unchanged coordinates, and fresh way tags are reused.
Tags become stale after three calendar months. A source-provided way ID takes
precedence and does not need a counter-to-way mapping.

### Review format

Both generated review files contain full mapping records and include
`"candidateOsmWayId": null` when no candidate exists. Available identifiers and
coordinates produce `candidateOsmWayUrl`, `osmWayIdUrl`, and a
`coordinatesOsmUrl` with a marker at zoom 17. Links are derived values; uploaded
link values are ignored.

Upload approvals to `OSMWayMapping_NEEDS_REVIEW_MERGED.json`, using the same
format for records originating in either review file:

```json
{
  "MRN": {
    "MRN_237": {
      "status": "MATCHED",
      "candidateOsmWayId": 789
    }
  }
}
```

Only include the decisions being submitted. `candidateOsmWayId` is the approved
positive way ID, whether it confirms the original candidate or supplies a
different one. The OSM function preserves the source record's original
`reverseLookupResult` when applying an approval.

Generated review files and source caches are outputs, not manual upload targets.
Do not replace an outstanding `_MERGED.json` submission while OSM may be reading
or deleting it: a later deletion could remove the replacement. Wait until it has
been processed and deleted before submitting another batch. This fixed-filename
convention separates review input from generated output, but does not support
concurrent manual submissions.

## Lifecycle scenarios

Each scenario below is independent. Examples use counter `MRN_237`, coordinates
(latitude `49.43`, longitude `1.08`), and illustrative way IDs. JSON snippets show
only relevant fields; omitted fields are retained, not deleted. `T1` and `T2`
stand for ISO-8601 update timestamps. `{ "MRN": {} }` means this example's record
has been removed; other sources and records in the real object remain.

GeoJSON output objects shown below are in the configured output bucket; the
request, cache, and review objects are in `osminfo`. API responses are not bucket
objects. All successful API steps remain subject to the run's request/time limits.

### Coordinates resolve to an accepted way

Initially, neither the counter mapping nor the way tags are cached.

| Step | Actor and action | Object changed | Relevant JSON added, updated, or removed |
| --- | --- | --- | --- |
| 1 | MRN GeoJSON finds no mapping and publishes without OSM enrichment | `MRN_TraficInfo.geojson` | Feature includes `{"properties":{"code_centrale":"MRN_237"}}`; `maximum_speed` is absent |
| 2 | MRN GeoJSON publishes its unresolved request | `MRN_OSMRequests.json` | Add `{"reverseLookups":{"MRN_237":{"latitude":49.43,"longitude":1.08,"sourceRoadReference":null}},"wayTagRefreshes":[]}` |
| 3 | OSM reads the request, confirms it is unresolved, and calls Nominatim | None yet; reads requests/caches | Response includes `{"osm_type":"way","osm_id":123,"category":"highway","type":"primary"}` |
| 4 | OSM accepts the candidate and saves the mapping | `MRN_OSMWayMapping.json` | Add/update `{"MRN_237":{"osmWayId":123,"candidateOsmWayId":null,"latitude":49.43,"longitude":1.08,"status":"MATCHED","lastUpdated":"T1"}}`; retain the reverse response in `reverseLookupResult` |
| 5 | OSM fetches `/api/0.6/way/123.json` and saves its tags | `MRN_OSMInfo.json` | Add/update `{"123":{"tags":{"maxspeed":"50"},"lastUpdated":"T1"}}` |
| 6 | Next MRN GeoJSON run reads the mapping and tags | `MRN_TraficInfo.geojson` | Update the feature with `{"properties":{"code_centrale":"MRN_237","maximum_speed":"50"}}` |
| 7 | MRN GeoJSON publishes its new request snapshot | `MRN_OSMRequests.json` | Remove `MRN_237` from `reverseLookups`; with no other work the object is `{"reverseLookups":{},"wayTagRefreshes":[]}` |

Between steps 5 and 7, another OSM run sees the still-published request but skips
it because the mapping is already satisfied. It does not repeat the reverse call.

### Known way ID: MMM

The source supplies way ID `123456`. No reverse lookup or counter-to-way mapping
is needed. If cached tags are fresh, GeoJSON uses them immediately and publishes
no request for this way. The table covers missing or stale tags instead.

| Step | Actor and action | Object changed | Relevant JSON added, updated, or removed |
| --- | --- | --- | --- |
| 1 | MMM GeoJSON uses the available cache and publishes | `MMM_TraficInfo.geojson` | With missing tags, `maximum_speed` is absent; with stale tags, the existing value remains usable |
| 2 | MMM GeoJSON requests a refresh for the known way | `MMM_OSMRequests.json` | Add `{"reverseLookups":{},"wayTagRefreshes":[123456]}` |
| 3 | OSM checks tag freshness and, if still needed, fetches `/api/0.6/way/123456.json` | `MMM_OSMInfo.json` | Add/update `{"123456":{"tags":{"maxspeed":"50"},"lastUpdated":"T1"}}` |
| 4 | Next MMM GeoJSON run reads the refreshed tags | `MMM_TraficInfo.geojson` | Add/update the corresponding feature's `{"maximum_speed":"50"}` property |
| 5 | MMM GeoJSON publishes its new request snapshot | `MMM_OSMRequests.json` | Remove `123456` from `wayTagRefreshes`; with no other work the object is `{"reverseLookups":{},"wayTagRefreshes":[]}` |

`MMM_OSMWayMapping.json` is unchanged for this record. If OSM runs again before
step 5, it checks the refresh timestamp and skips another tag fetch.

### Reverse lookup produces NEEDS_REVIEW

Here the source reference is `D0925`, but the candidate reference is `M 925`.

| Step | Actor and action | Object changed | Relevant JSON added, updated, or removed |
| --- | --- | --- | --- |
| 1 | MRN GeoJSON publishes an unresolved mapping request after publishing its GeoJSON | `MRN_OSMRequests.json` | Add `{"reverseLookups":{"MRN_237":{"latitude":49.43,"longitude":1.08,"sourceRoadReference":"D0925"}},"wayTagRefreshes":[]}` |
| 2 | OSM reverse lookup finds way `456`; reference comparison fails | `MRN_OSMWayMapping.json` | Add/update `{"MRN_237":{"osmWayId":null,"candidateOsmWayId":456,"status":"NEEDS_REVIEW","sourceRoadReference":"D0925","osmRoadReference":"M 925","reviewReason":"different_ref"}}`; save the response and lookup coordinates/timestamps |
| 3 | OSM regenerates the review export | `OSMWayMapping_NEEDS_REVIEW.json` | Add/update `{"MRN":{"MRN_237":{"status":"NEEDS_REVIEW","candidateOsmWayId":456,"sourceRoadReference":"D0925","osmRoadReference":"M 925","reviewReason":"different_ref"}}}` |
| 4 | Subsequent GeoJSON runs still request the unresolved mapping; OSM recognizes the cached outcome | `MRN_OSMRequests.json` remains populated; caches/review export need no change | Keep `MRN_237` in `reverseLookups`; no additional reverse call at unchanged coordinates and no tag fetch for the unapproved candidate |
| 5 | You approve or correct the candidate | `OSMWayMapping_NEEDS_REVIEW_MERGED.json` | Follow the common approval workflow below; do not upload over the generated review file |

A missing `namedetails.ref` follows the same lifecycle, with
`"osmRoadReference": null` (when represented) and
`"reviewReason": "missing_osm_ref"`.

### Reverse lookup produces NOT_FOUND

Nominatim returns no suitable way: for example, an error object, a node or
relation, an invalid ID, or an unsupported highway classification.

| Step | Actor and action | Object changed | Relevant JSON added, updated, or removed |
| --- | --- | --- | --- |
| 1 | MRN GeoJSON publishes an unresolved mapping request after publishing its GeoJSON | `MRN_OSMRequests.json` | Add `{"reverseLookups":{"MRN_237":{"latitude":49.43,"longitude":1.08,"sourceRoadReference":null}},"wayTagRefreshes":[]}` |
| 2 | OSM reverse lookup returns no acceptable way | `MRN_OSMWayMapping.json` | Add/update `{"MRN_237":{"osmWayId":null,"candidateOsmWayId":null,"status":"NOT_FOUND","reviewReason":"no_road_way"}}`; save the response and lookup coordinates/timestamps |
| 3 | OSM regenerates the review export | `OSMWayMapping_NOT_FOUND.json` | Add/update `{"MRN":{"MRN_237":{"status":"NOT_FOUND","candidateOsmWayId":null,"latitude":49.43,"longitude":1.08,"reviewReason":"no_road_way"}}}` |
| 4 | Subsequent GeoJSON runs still request the unresolved mapping; OSM recognizes the cached outcome | `MRN_OSMRequests.json` remains populated; caches/review export need no change | Keep `MRN_237` in `reverseLookups`; no additional reverse call at unchanged coordinates |
| 5 | You find and approve a suitable way manually | `OSMWayMapping_NEEDS_REVIEW_MERGED.json` | Follow the common approval workflow below, supplying the previously absent `candidateOsmWayId` |

If no suitable way can be found, the record stays `NOT_FOUND` in the cache and
review export. There is no permanent dismissal status in this design.

### Common manual approval workflow

These steps apply to records in either generated review file. Here you approve
way `789`, whether it confirms the candidate or replaces it.

| Step | Actor and action | Object changed | Relevant JSON added, updated, or removed |
| --- | --- | --- | --- |
| 1 | You upload the decision | `OSMWayMapping_NEEDS_REVIEW_MERGED.json` | Add `{"MRN":{"MRN_237":{"status":"MATCHED","candidateOsmWayId":789}}}` |
| 2 | At the start of the next run, OSM applies and persists the approval | `MRN_OSMWayMapping.json` | Update `{"MRN_237":{"osmWayId":789,"candidateOsmWayId":null,"status":"MATCHED","lastUpdated":"T2"}}`; remove `reviewReason`; preserve coordinates, source metadata, and the original `reverseLookupResult` |
| 3 | OSM queues and fetches the approved way's tags | `MRN_OSMInfo.json` | Add/update `{"789":{"tags":{"maxspeed":"50"},"lastUpdated":"T2"}}` |
| 4 | OSM regenerates both review exports from the source caches | `OSMWayMapping_NEEDS_REVIEW.json` and `OSMWayMapping_NOT_FOUND.json` | Remove `MRN_237` wherever present; the example source group becomes `{"MRN":{}}` |
| 5 | OSM finishes processing and persisting the submission | `OSMWayMapping_NEEDS_REVIEW_MERGED.json` | Delete the object; do not replace it with an empty JSON document |
| 6 | Next MRN GeoJSON run uses the approved mapping and available tags | `MRN_TraficInfo.geojson` | Add/update the feature with `{"properties":{"code_centrale":"MRN_237","maximum_speed":"50"}}` |
| 7 | MRN GeoJSON publishes its new request snapshot | `MRN_OSMRequests.json` | Remove `MRN_237` from `reverseLookups`; if the approved way's tags are still missing/stale, request `{"wayTagRefreshes":[789]}`; otherwise both sections are empty |

OSM retains the submission on approval-processing or persistence failure, so a
later run can retry it. A run that did not load a submission must not delete an
object uploaded later during that run. Reapplying an already persisted approval must be
idempotent and preserve an existing positive assignment. Tag-fetch failures do
not undo an approved mapping; missing/stale tags remain eligible for refresh.
Approved IDs disappear from review exports only after their assignments have
been saved. Unchanged exports skip their PUTs; OSM logs added, changed, and
removed record counts separately for each export.

## Hourly processing and status semantics

`OSMInfoProviderImpl` handles the OSM API interaction and cache persistence.
`OSMInfoFunction` runs hourly, at the start of each hour. At the beginning of the
run it reads any manual submission, loads source caches and current request
snapshots, and merges eligible requests. Newly encountered unresolved counters
start as `MISSING`. Only OSM writes that state to the mapping cache; if its batch
is deferred, the published request remains available for a later run.

A cache-only pass counts eligible reverse lookups per source and shares a
default budget of **16** across sources with work, redistributing unused shares.
The internal loop budget is **240 seconds**, checked between resources; the
active batch may finish after that budget. The deployed OCI timeout remains
**300 seconds**. Valid coordinates and no positive assigned way ID are required
for reverse lookup. Tags are fetched for newly resolved ways and requested
missing/stale way IDs. Tag requests are deduplicated, fresh tags are skipped,
and failed fetches preserve existing tags.

Reverse requests are sequential and spaced at least **15 seconds** apart, per
the [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/),
which limits scheduled jobs to four requests per minute. Repeated coordinates
are deduplicated within a run. Request files are not acknowledged or deleted by
OSM: cache state determines whether each request still needs work.

| Status | Meaning | Reverse lookup behavior |
| --- | --- | --- |
| `MISSING` | Lookup pending, explicitly requested again, or unable to complete | Eligible with valid coordinates and no assigned way, even if an old response exists; HTTP failure leaves it pending |
| `MATCHED` | Accepted automatic or manual way assignment | No reverse lookup; supplied or assigned way ID takes precedence |
| `NEEDS_REVIEW` | Suitable way with a missing or differing reference | Reuse the cached outcome at unchanged coordinates; expose in its review file |
| `NOT_FOUND` | No acceptable way returned | Reuse the cached outcome at unchanged coordinates; expose in its review file |

A successful fresh lookup replaces `reverseLookupResult` and sets its resulting
status. An HTTP failure preserves the previous response. Changing coordinates
makes an unresolved cached outcome eligible again. A manual retry/reset input
beyond `MATCHED` approvals is not specified here; direct source-cache edits
would bypass the single-writer convention.

At the end of each run, INFO logs summarize status counts per source, first for
processed records (including manual approvals), then for all cached records.
The global review exports include all source caches, even those skipped by the
API time budget. Filtering a counter out of GeoJSON removes it from the next
request snapshot but does not automatically delete its historical cache entry
or remove an existing review outcome.

### Accident caches

The accident processor uses separate departmental prefixes
`ONISR_DPT30`, `ONISR_DPT34`, and `ONISR_DPT76`, distinct from traffic-count
prefixes. The same object ownership applies to this request producer;
for example, `ONISR_DPT30_OSMRequests.json`, `ONISR_DPT30_OSMWayMapping.json`,
and `ONISR_DPT30_OSMInfo.json`. Mapping keys identify accidents rather than
traffic counters. Raw accident CSVs and processed accident output belong in
`onisrinfo`. OSM discovers sources through request/cache object suffixes,
without requiring `dsconfig.json` registration.

OSM data is attributed to OpenStreetMap contributors under the ODbL.

## References

- [OSM API v0.6](https://wiki.openstreetmap.org/wiki/API_v0.6)
- [Nominatim reverse API](https://nominatim.org/release-docs/latest/api/Reverse/)
- [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/)
- [OpenStreetMap attribution and licence](https://www.openstreetmap.org/copyright)
- [Lozère — Référentiel routier mesuré: road identifier structure and letter indices](https://www.data.gouv.fr/datasets/referentiel-routier-mesure)
- [Hérault — Bornage du réseau routier départemental: padded road codes and display names](https://www.herault-data.fr/explore/dataset/bornage-routier-34/)
