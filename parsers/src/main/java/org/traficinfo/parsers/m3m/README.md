# Montpellier Méditerranée Métropole traffic counts

We get traffic-count data from [Montpellier Méditerranée Métropole Open Data](https://data.montpellier3m.fr/dataset/comptage-vehicules-particuliers-de-montpellier). The dataset covers vehicle and bicycle counts on the metropolitan road and cycle network. The publisher provides CSV and geographic formats, lists a daily publication frequency, and distributes the data under the Open Database License (ODbL).

This document summarizes an analysis of `MMM_MMM_Comptage_20261011.csv` and `MMM_MMM_Comptage_20261012.csv`, downloaded on successive days. Unless stated otherwise, statistics below refer to the **October 12, 2026 export**. They are snapshot results, not live statistics.

## Records, sites and channels

The export contains **38 fields and 2,543 distinct counting sites**, with records spanning 2010–2026. A counting site is a location where traffic is measured, identified here by `id_centrale`. One site can have several years and channels of data.

In these exports, each row is uniquely identified by both `objectid` and the combination `(id_centrale, annee, code_canal)`. Identifier stability across future exports is undocumented. The Java parser currently binds `code_centrale`, a separate station code, rather than `id_centrale`.

- `code_canal = Z` represents combined directions (`Sens cumulés`).
- Other channel codes include `0`, `1`, `v`, `w` and `6`. Their meaning depends on the site's direction labels.
- `{0,1,Z}` describes two directional channels plus their combined total. It does not mean three lanes.
- Do not sum directional rows together with their combined row: that double-counts traffic.

## Fields and units

The publisher documents the traffic measures. Interpretations of internal identifiers and metadata follow the field names and observed values where the publisher supplies no definition.

| Fields | Meaning |
| --- | --- |
| `objectid` | Export record identifier. |
| `id_centrale`, `code_centrale`, `libelle_centrale` | Site identifier, separate source-system station code, and human-readable site description. |
| `annee` | Year associated with the statistics. |
| `libelle_groupage`, `label_sens_circu` | Channel/grouping and traffic-direction labels. Usually identical in this export. |
| `type_compteur` | Counter category: permanent, temporary (`ponctuel`), rotating campaign (`tournant`), traffic-signal (`feux`), or bicycle counter. |
| `type_comptage` | In this export: one-direction or two-direction counting, or `NR` (presumably not recorded). |
| `nb_canaux`, `list_canaux` | Number and list of available channels, including aggregate channels. |
| `nb_annee`, `list_annee` | Number and list of years associated with the site. |
| `mja_jo`, `mja_johvs`, `mja_tcj` | All-vehicle daily averages for working days, working days excluding school holidays, and all day types, respectively. |
| `mja_pl_tcj`, `mja_pl_johvs` | Heavy-vehicle daily averages for all day types and working days excluding school holidays. |
| `hp_johvs_matin`, `hp_johvs_soir` | Morning and afternoon/evening peak-hour volumes, with the corresponding interval stored as text. |
| `hpm_0709_johvs`, `hpm_pl_0709_johvs` | All-vehicle and heavy-vehicle hourly averages for 07:00–09:00. |
| `hcj_0916_johvs`, `hcj_pl_0916_johvs` | All-vehicle and heavy-vehicle hourly averages for 09:00–16:00. |
| `hps_1619_johvs`, `hps_pl_1619_johvs` | All-vehicle and heavy-vehicle hourly averages for 16:00–19:00. |
| `hcn_1907_johvs`, `hcn_pl_1907_johvs` | All-vehicle and heavy-vehicle hourly averages for 19:00–07:00. |
| `tir_tcj` | Percentage of users exceeding the speed limit, according to the publisher. The threshold used is not supplied. |
| `v85ma_tcj` | 85th-percentile speed across all day types. |
| `vtma_tcj` | Mean speed across all day types. |
| `descriptif_periode`, `time_days` | Measurement-period description and end date minus start date in days. |
| `name`, `highway`, `nom_commune`, `osm_id` | Associated road/path name, OpenStreetMap classification, municipality, and OpenStreetMap feature identifier. |

`MJA` denotes an annual daily average, `PL` heavy vehicles, `JO` working days, `JOHVS` working days excluding school holidays, and `TCJ` all day types. All eight fixed-time hourly fields use JOHVS days. Speeds are interpreted as km/h; the dataset description does not explicitly state their unit.

The hourly interpretation is supported by the data: the four all-vehicle hourly averages, weighted by their respective durations of 2, 7, 3 and 12 hours, approximately reconstruct `mja_johvs`. A peak value such as `313 (17h15 - 18h15)` represents a volume during that reported hour, which can differ from the fixed time bands.

## GeoJSON aggregation rules

These rules describe the application's output, rather than the publisher's statistics. The parser groups records by `code_centrale`. Eligible records cover the current year and the preceding ten years and have `mja_jo` strictly between 0 and 300,000.

For each station, the latest eligible year supplies the GeoJSON properties. The row with the highest `mja_jo` supplies the traffic count and other main properties. Speeds are selected independently across all eligible channels in that year:

- `v85_speed` is the maximum `v85ma_tcj`.
- `mean_speed` is the maximum `vtma_tcj`.
- Missing speeds do not replace positive values. Each speed property is omitted if no positive value is available in the selected year; older years provide no fallback.

For example, station `472` in `2022` with channels `0`, `1` and `Z`, V85 values `53`, `52` and blank, and mean speeds `34`, `32` and blank produces `"v85_speed": "53"` and `"mean_speed": "34"`, regardless of which channel has the highest eligible traffic count. These are maxima across channels, not recalculated statistics for combined traffic; the two maxima can come from different rows.

Regression coverage is in [`GeoJsonParserTest`](../../../../../test/java/org/traficinfo/parsers/GeoJsonParserTest.java), including the three-channel example and exclusion of older-year speeds.

## Updates between successive exports

Between October 11 and October 12, data changed at **43 sites**. Updates include extended measurement periods ending on October 9, recalculated traffic averages and speed statistics, and corrections to historical heavy-vehicle measurements.

### Which sites update regularly?

Permanent counters are the main source of observed daily updates: **42 of 60 permanent-counter sites (70%)** had at least one changed row in this comparison. One rotating-counter site also changed, bringing the total to 43. Two successive snapshots do not establish a guaranteed update schedule for any individual site.

| `type_compteur` | Changed sites / all sites |
| --- | ---: |
| `compteur permanent` | 42 / 60 |
| `compteur tournant` | 1 / 47 |
| `compteur feux` | 0 / 78 |
| `compteur permanent vélo` | 0 / 46 |
| `compteur ponctuel` | 0 / 2,301 |
| `compteur ponctuel vélo` | 0 / 11 |

All observed changes to 2026 records concern `compteur permanent`, but some records of that type remained unchanged. Counter type alone cannot determine whether a record changes. Historical corrections and future updates can also affect other types; compare record contents to detect changes reliably.

## Speed-data coverage

A site is counted as populated if at least one row in the selected period has a nonblank value. Each `id_centrale` is counted once, regardless of the number of channels.

| Scope | Measure | Sites | Percentage |
| --- | --- | ---: | ---: |
| All years, all municipalities | Populated `tir_tcj` | 1,721 / 2,543 | 67.68% |
| All years, all municipalities | Populated `v85ma_tcj` | 1,976 / 2,543 | 77.70% |
| All years, all municipalities | Populated `vtma_tcj` | 1,976 / 2,543 | 77.70% |
| All years, all municipalities | All three populated on the same row | 1,721 / 2,543 | 67.68% |
| 2026, all municipalities | Populated `tir_tcj` | 311 / 507 | 61.34% |
| 2026, all municipalities | `v85ma_tcj >= 40 km/h` on at least one channel | 217 / 507 | 42.80% |
| 2026, Montpellier only | `v85ma_tcj >= 40 km/h` on at least one channel | 117 / 365 | 32.05% |

The last two denominators include all sites with records in the selected year and municipality, including sites without speed measurements.

## Montpellier: highest reported speeding percentages in 2026

There are 199 Montpellier sites with a populated 2026 `tir_tcj`. The table selects each site's highest value across channels, then sorts by that value descending and numeric `id_centrale` ascending for ties. Both speeds come from the same selected row. Three sites tie at 87%; one falls outside the top 20.

| `id_centrale` | Site | Channel | `tir_tcj` | V85 (km/h) | Mean speed (km/h) |
| --- | --- | --- | ---: | ---: | ---: |
| 636 | Rue du Professeur Blayac | 1 | 99% | 69 | 58 |
| 75610 | Avenue Albert Dubout (CPEV) | 1 | 99% | 64 | 54 |
| 83967 | Route de Mende | 0 | 99% | 57 | 47 |
| 2008 | Avenue Albert Dubout | 1 | 97% | 49 | 42 |
| 84082 | Avenue Etienne Mehul | 1 | 97% | 70 | 63 |
| 75495 | Rue du Pont de Laverune | 1 | 96% | 60 | 49 |
| 714 | Avenue de la Liberte (CPEV) | 1 | 95% | 70 | 62 |
| 933 | Avenue du Pont Trinquat | 1 | 94% | 53 | 43 |
| 539 | Avenue Albert Einstein | 1 | 93% | 61 | 49 |
| 619 | Avenue Pablo Neruda | 0 | 93% | 77 | 65 |
| 2012 | Avenue du Professeur Etienne Antonelli | 0 | 93% | 51 | 43 |
| 52191 | Avenue des Etats du Languedoc | 0 | 90% | 46 | 37 |
| 1116 | Rue du Faubourg Saint Jaumes (CPEV) | 0 | 89% | 48 | 39 |
| 83975 | Rue Marius Pepita | 1 | 89% | 48 | 38 |
| 83966 | Avenue Nina Simone | 1 | 88% | 49 | 39 |
| 83979 | Rue Antoine Laurent Jussieu | 1 | 88% | 51 | 40 |
| 84013 | Rue Martin Luther King | 0 | 88% | 49 | 39 |
| 84053 | Rue de Fontcouverte | 0 | 88% | 47 | 37 |
| 450 | Avenue de Monsieur Teste | 0 | 87% | 53 | 41 |
| 611 | Rue des Bouisses | 0 | 87% | 47 | 38 |

These are reported percentages with an undocumented threshold, not independently verified rates of exceeding the current posted limit. Neither the CSV nor the published definitions supplies the threshold used for `tir_tcj`. Mean speed and V85 cannot reconstruct the exact percentage above a chosen speed limit. Confirmation requires the source calculation or a response from the publisher; the dataset contact is `OpenData3M@montpellier3m.fr`.

## Data-quality and interpretation limits

- A blank value means no value was supplied, not zero. Some channel labels identify pedestrians; check the measured population as well as the counter category.
- Despite annual-average field names, measurements can cover a short campaign or an incomplete year. The annualisation procedure is undocumented.
- For every populated period in this snapshot, `time_days` equals end date minus start date. It does not prove the number of valid observation days.
- Some `list_annee` values are 50 characters long and lack a closing brace, suggesting truncation during export.
- Missing street names can reflect unnamed OpenStreetMap segments. `highway` describes network classification, not speed limit or lane count. See the [OpenStreetMap definition](https://wiki.openstreetmap.org/wiki/Key:highway).
- Counter type and historical update behavior are useful context, but neither guarantees that a future row will change or remain unchanged.
