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
  road reference is compared with OSM's `ref`, allowing formatting differences
  such as `D0011` versus `D 11`. If a source reference is provided, a missing or
  differing OSM reference leaves a candidate for review instead of assigning
  the way automatically.

Reverse geocoding can return a nearby different road or another kind of object.
The response is therefore a candidate to validate, not proof of the correct road.
Existing positive way IDs are preserved.

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
results, and fetches tags for newly resolved ways. GeoJSON parsing itself does
not perform reverse lookups.

Unsuitable or absent road results are recorded as `NOT_FOUND`; ambiguous road
references are recorded as `NEEDS_REVIEW`. These responses are cached for
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
