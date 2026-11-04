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
`primary_link`, `secondary_link`, `tertiary_link`, `footway`, `cycleway`, and
`path`. These are accepted regardless of the counter's transport type, without
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

## Caching and processing

`OSMInfoProviderImpl` handles API interaction and cache persistence. Two JSON
objects per data source are stored in the `osminfo` bucket:

- `<sourceId>_OSMWayMapping.json`: counter IDs, coordinates, road references,
  resolved or candidate way IDs, lookup results, and review information.
- `<sourceId>_OSMInfo.json`: way IDs, their OSM tags, and the timestamp of the last
  successful tag refresh.

GeoJSON generation loads these caches, then filters records before OSM enrichment.
Records excluded by count, year, population, or other output filters do not create
mapping entries or queue tag requests. Missing way-tag entries and entries
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
`NOT_FOUND` records include `"candidateOsmWayId": null`; replace `null` with the
chosen way ID when approving them.
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
references are recorded as `NEEDS_REVIEW`. `MISSING` means lookup pending,
including a manually requested retry; `MATCHED` means an accepted way assignment. These values are defined in
`OSMWayMappingStatus`. These responses are cached for
unchanged coordinates to avoid repeating unsuccessful queries. To request a new
lookup, set the record's status to `MISSING` in its source
`<sourceId>_OSMWayMapping.json` cache and reupload it. No need to remove the old
`reverseLookupResult`: a successful response replaces it and sets the new status.
HTTP failures preserve the old response and leave `MISSING` eligible for retry.
Valid coordinates and no positive assigned `osmWayId` are still required. The
usual request limits, spacing, and processing priority apply.

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
