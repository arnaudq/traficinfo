# TraficInfo

Traffic count and speed information are being made available by many local and regional authorities as open data. Unfortunately, it is usually published in its raw form, as a set of csv or geojson files, with at best, a rather clumsy map.

TraficInfo (single f in french :smile:) brings traffic-count data onto public interactive maps so that anybody can answer questions like:
* How many cars are passing in my street per day ?
* Is the new generalized 30km/h default limit of my city really enforced ?
* Based on the street's traffic and average speed, which streets need a separate bike lane to become safe for cyclists  ?
* Are we moving in the right directions towards [Vision Zero](https://en.wikipedia.org/wiki/Vision_Zero) ?

The maps help readers explore traffic volumes by location and year. Select a
counter to see its measurements and history, or use the map filters to narrow
the displayed data. Coverage and available measurements depend on each source.

TraficInfo combines vehicle and bicycle counts from different
publishers into a common GeoJSON format, with annual history, daily averages
and speed measurements where available. Using OpenStreetMap, it adds road context,
including speed limits and bicycle contraflow information.


## Maps

| Map | Data sources and counts | Count per Day (yearly average) | V85 Speed | Average Speed |
| --- | --- | :---: | :---: | :---: |
| [Montpellier - France](http://umap.openstreetmap.fr/en/map/comptage-vehicules-montpellier_754880) | Montpellier Méditerranée Métropole ( :car: + :bike: ) ; <br/>Hérault department: ( :car: ) | X | X | X |
| [Nîmes - France](http://umap.openstreetmap.fr/en/map/comptage-vehicules-nimes_1478574) | Nîmes Métropole and Gard department: ( :car: ) | X |  |  |
| [Rouen - France](https://umap.openstreetmap.fr/en/map/comptage-vehicules-rouen_1475742) | Métropole Rouen Normandie ( :car: + :bike: ); <br/> Seine-Maritime department ( :car: ) | X | X | X |


## How it works

The service currently runs on Oracle Cloud Infrastructure (OCI), using cloud
functions (serverless), object storage, schedules, events,
logging, monitoring and notifications. These are pretty standard IaaS/PaaS building
blocks, available from other cloud providers. The same architecture could be used
elsewhere, but migration would require adapting the OCI SDK integrations and deployment scripts.

Processing is quite simple:
1) Scheduled retrieval functions download open datasets into object storage upon detecting changes (leveraging HTTP ETag wherever possible).
2) Changes trigger preprocessing function, which normalizes and validates the records and publishes yearly
data and an associated manifest objects in the datasource specific bucket.
3) A changed manifest then triggers GeoJSON generation for the map layers, enriching the counters with data from OpenStreetMap.

An hourly OpenStreetMap refresh maintains cached road information used to enrich
the counters.

## Development and operations

- [Website](webapp/README.md): local development, dependencies, build and
  GitHub Pages deployment of the Leaflet prototype.
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

## License

TraficInfo's original code is licensed under the GNU Affero General Public License,
version 3 only (`AGPL-3.0-only`). You may redistribute and modify it under those
terms. It is provided without warranty; see [LICENSE](LICENSE) for the full
license text.

Third-party code, datasets, map tiles and logos retain their respective licenses
and attribution requirements.
