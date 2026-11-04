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
  level results and accept only suitable road ways. Where available, the source
  road reference is compared with OSM's `namedetails.ref` (using `namedetails=1`), allowing formatting differences
  such as `D0011` versus `D 11` or `D0111E01` versus `D 111E1`. If a source reference is provided, a missing or
  differing OSM reference leaves a candidate for review instead of assigning
  the way automatically.

Reverse geocoding can return a nearby different road or another kind of object.
The response is therefore a candidate to validate, not proof of the correct road.
Existing positive way IDs are preserved.

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

## Caching and processing

`OSMInfoProviderImpl` handles API interaction and cache persistence. Two JSON
objects per data source are stored in the `osminfo` bucket:

- `<sourceId>_OSMWayMapping.json`: counter IDs, coordinates, road references,
  resolved or candidate way IDs, lookup results, and review information.
- `<sourceId>_OSMInfo.json`: way IDs, their OSM tags, and the timestamp of the last
  successful tag refresh.

GeoJSON generation reads these caches first. Missing way-tag entries and entries
older than three calendar months are queued for refresh after publishing the
GeoJSON. Existing cached values remain usable, and newly fetched data is
available to subsequent invocations. Failed requests preserve existing tags.

A dedicated OCI function, **`OSMInfoFunction`**, runs **hourly**, at the start of
each hour. It processes incomplete mapping caches in bounded batches, saves
results, and fetches tags for newly resolved ways. A cache-only first pass counts
eligible lookups per source, then shares a default budget of 12 lookups evenly
among sources with work, redistributing unused shares. At the end of the run it logs
counts per data source, identified by its cache prefix, for each status among
processed records (including manual approvals) and then all records in its loaded
cache. All caches are loaded before the bounded API loop, so sources skipped by
its time budget still appear in the review document and status summaries.
GeoJSON parsing itself does not perform reverse lookups.

Two global review objects in the same bucket group records by data source
prefix, then counter ID:

- `OSMWayMapping_NEEDS_REVIEW.json`: all `NEEDS_REVIEW` records.
- `OSMWayMapping_NOT_FOUND.json`: all `NOT_FOUND` records, for manually finding
  a road when reverse lookup returned no suitable way.

For example, the `NEEDS_REVIEW` file contains:

```json
{
  "DPT30": { "counter-id": { "status": "NEEDS_REVIEW", "candidateOsmWayId": 123 } },
  "DPT34": {}
}
```

Download either file, set a positive `candidateOsmWayId` to the chosen road way,
change the record's `status` to `MATCHED`, and upload it to the same object.
`NOT_FOUND` records generally have no candidate ID, so add that field when
approving them.
At the start of the next run, accepted candidates become `osmWayId` assignments
in their source caches, with tag refresh queued. Existing assigned ways are
preserved. The original `reverseLookupResult` remains unchanged. Each final
review file contains only records still in its corresponding status. Unchanged
files are not rewritten. If any source cache cannot be loaded or saved, the
previous review files are kept so that pending decisions are not lost.

Each mapping includes derived review links when the relevant values are available:
`candidateOsmWayUrl`, `osmWayIdUrl`, and `coordinatesOsmUrl` (map zoom 17, with
a marker at the exact latitude and longitude).
Uploaded link values are ignored and regenerated from IDs and coordinates.

Unsuitable or absent road results are recorded as `NOT_FOUND`; ambiguous road
references are recorded as `NEEDS_REVIEW`. `MISSING` means no assignment yet,
and `MATCHED` means an accepted way assignment. These values are defined in
`OSMWayMappingStatus`. These responses are cached for
unchanged coordinates to avoid repeating unsuccessful queries. HTTP failures
remain eligible for a later retry.

Reverse requests are sequential and spaced at least 15 seconds apart, per the
[Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/),
which limits scheduled jobs to four requests per minute. Caching, request limits,
and application identification limit load on the public service.
OSM data is attributed to OpenStreetMap contributors under the ODbL.

## References

- [OSM API v0.6](https://wiki.openstreetmap.org/wiki/API_v0.6)
- [Nominatim reverse API](https://nominatim.org/release-docs/latest/api/Reverse/)
- [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/)
- [OpenStreetMap attribution and licence](https://www.openstreetmap.org/copyright)
- [Lozère — Référentiel routier mesuré: road identifier structure and letter indices](https://www.data.gouv.fr/datasets/referentiel-routier-mesure)
- [Hérault — Bornage du réseau routier départemental: padded road codes and display names](https://www.herault-data.fr/explore/dataset/bornage-routier-34/)
