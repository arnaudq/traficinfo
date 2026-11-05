# TraficInfo

Traffic count and speed information are being made available by many local and regional authorities as open data. Unfortunately, it is usually published in its raw form, as a set of csv or geojson files, with at best, a rather clumsy map.

TraficInfo brings public traffic-count data onto interactive maps so that anybody can answer questions like:
* how many cars are passing in my street per day,
* is the new generalized 30km/h default limit and zero vision of my city a reality ?
* which streets would need a separate bike lane based on the street's trafic and average speed ?

It combines vehicle and bicycle counts from different
publishers into a common GeoJSON format, with annual history, daily averages
and speed measurements where available. OpenStreetMap data adds road context,
including speed limits and bicycle contraflow information.

The maps help readers explore traffic volumes by location and year. Select a
counter to see its measurements and history, or use the map filters to narrow
the displayed data. Coverage and available measurements depend on each source.

## Maps

| Area | Data sources and counts | Map |
| --- | --- | --- |
| Montpellier and Hérault | Montpellier Méditerranée Métropole: vehicles and bicycles; Hérault department: vehicles | [Open map](http://umap.openstreetmap.fr/en/map/comptage-vehicules-montpellier_754880) |
| Nîmes and Gard | Nîmes Métropole and Gard department: vehicles | [Open map](http://umap.openstreetmap.fr/en/map/comptage-vehicules-nimes_1478574) |
| Rouen and Seine-Maritime | Métropole Rouen Normandie: vehicles and bicycles; Seine-Maritime department: vehicles | [uMap template](map-templates/MRN.umap); no public map URL is recorded yet |

[Map configuration](mapconfig.json) records the maps, their selected data
sources and published URLs. Maps use uMap with remote GeoJSON layers, so data
can be refreshed without rebuilding the map.

## How it works

Scheduled retrieval downloads open datasets into object storage. Changes trigger
preprocessing, which normalizes and validates the records and publishes yearly
data. A changed manifest then triggers GeoJSON generation for the map layers.
An hourly OpenStreetMap refresh maintains cached road information used to enrich
the counters.

The service currently runs on Oracle Cloud Infrastructure (OCI), using cloud
functions, object storage, schedules, events, identity and access management,
logging, monitoring and notifications. These are common IaaS/PaaS building
blocks available from other cloud providers. The same architecture could run
elsewhere, but migration would require adapting the OCI SDK integrations,
authentication and deployment scripts.

## Development and operations

- [Setup and operations](specs/setup.md): environment, OCI resources, deployment,
  event rules, schedules, alerts and script checks.
- [Adding a datasource](specs/add-datasource.md): source inspection, parsers,
  retrieval, preprocessing, GeoJSON output and uMap layers. A reusable
  [`traficinfo-add-datasource` skill](specs/skills/traficinfo-add-datasource/SKILL.md)
  follows the same workflow.
- [Data source functions](dsfunctions/README.md) and [parsers](parsers/README.md):
  module usage and behavior.
- [Map templates](map-templates/README.md): generation, customization and import
  into uMap.
- [Storage contract](specs/datasource-storage-contract.md),
  [GeoJSON properties](specs/geojson-feature-properties.md) and
  [OpenStreetMap data](specs/osm-data.md): data formats and enrichment.
- [Monitoring and alerts](specs/monitoring.md): processing metrics, failure
  notifications and OSM review reminders.
