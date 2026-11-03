# GeoJSON feature properties

Status: Implemented

## Current properties

The shared GeoJSON generator currently writes the following properties on counter
features. This table describes the existing implementation as of 2026-11-03.

| Property | Content |
|---|---|
| `code_centrale` | Counter identifier |
| `data_source` | Source ID, e.g. `MMM` |
| `name` | Annual daily average as text, with `⇄` for bidirectional counts |
| `description` | Counter/street display name; falls back to the original feature name, then the counter identifier |
| `annee` | Measurement year |
| `type` | `VEHICULES`, `BICYCLES`, `PIETONS_VELOS`, or `UNKNOWN` |
| `moyenne_jour_annuelle` | Annual daily average, as a number |
| `moyenne_pointe_matin` | Morning peak average, when available |
| `moyenne_pointe_soir` | Evening peak average, when available |
| `v85_speed` | 85th-percentile speed in km/h, as text; present when positive |
| `mean_speed` | Mean speed in km/h, as text; present when positive |
| `maximum_speed` | OSM speed limit in km/h, as text; present when positive |
| `bicycle_contraflow_allowed` | `"yes"` or `"no"` from cached OSM tags; omitted when way information is unavailable |
| `moyenne_annuelle_history` | Up to three years of averages, e.g. `1200 (2026) 1100 (2025)` |
| `_umap_options` | Object containing `color`: `Green` for ≤1,000/day, `Blue` for >1,000 and ≤5,000/day, otherwise `Red` |

When reusing input GeoJSON features, the generator preserves existing `gid`,
`code_centrale`, and `name:q` properties. Other input properties are removed before
the generated properties are added.

The generated features contain no OSM way ID or raw one-way tags, including
`oneway:bicycle`. Bicycle contraflow is exposed through the derived property above.

Implementation references:

- [GeoJsonParser.java](../parsers/src/main/java/org/traficinfo/parsers/GeoJsonParser.java)
- [GeoJsonUmapOptions.java](../parsers/src/main/java/org/traficinfo/parsers/GeoJsonUmapOptions.java)

## Bicycle contraflow

The following property is supported for all generated counter types and data
sources:

| Property | Values | Content |
|---|---|---|
| `bicycle_contraflow_allowed` | `"yes"` / `"no"`, or omitted | Whether the associated cached OSM way explicitly permits bicycles to travel in both directions on an otherwise one-way road |

`Comptage` and all datasource implementations store this as nullable
`Boolean bicycleContraflowAllowed`, accessed through
`getBicycleContraflowAllowed()` and `setBicycleContraflowAllowed(Boolean)`.
Its default is `null`. GeoJSON serialization maps `true` to `"yes"`, `false`
to `"no"`, and `null` to an omitted property.

Derive the value from the associated OSM way's cached `oneway` and
`oneway:bicycle` tags:

| `oneway` | `oneway:bicycle` | `bicycle_contraflow_allowed` |
|---|---|---|
| `yes` | `no` | `"yes"` |
| `-1` | `no` | `"yes"` |
| `yes` or `-1` | Any other value or absent | `"no"` |
| `no`, absent, or any other value | Any value or absent | `"no"` |

`oneway=-1` describes a one-way road whose permitted direction is opposite to the
OSM way's drawn direction; the bicycle exception still permits contraflow.

If the feature has no associated OSM way or no cached way information, keep the
counter value `null` and omit the property. With cached way information, `"no"`
means the two cached tags do not establish a bicycle contraflow exception,
including when either tag is missing; it does not establish a legal prohibition.

Use the existing cache lookup and refresh lifecycle. Recompute the property when
generating features, including reused input features. Do not infer this property
from the counter's direction type, implied one-way restrictions, legacy
`cycleway=opposite*` tags, or other OSM tags.

For the MMM cache inspected on 2026-11-03, the 59 ways tagged
`oneway:bicycle=no` include 53 with `oneway=yes`, three with `oneway=no`, and
three without `oneway`. The rule therefore yields `"yes"` for 53 of
those ways and `"no"` for the other six.

## Map filter

All generated map templates declare `bicycle_contraflow_allowed` as a `Boolean`
field at map and layer level. The map-level filter uses the `Switch` (Yes/No)
widget with the label “Double sens cyclable autorisé”. uMap interprets missing
values as No for this filter, so the No selection also includes features without
cached OSM way information.

OSM references: [oneway](https://wiki.openstreetmap.org/wiki/Key:oneway) and
[oneway:bicycle](https://wiki.openstreetmap.org/wiki/Key:oneway:bicycle).
