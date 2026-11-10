# MMM bicycle counters: exploratory daily-data mapping

Analysis date: 2026-10-31. This is a research crosswalk; application behavior is unchanged.

## Result

The official daily-counter location file has 60 points. Comparing them with 57 annual bicycle sites (46 permanent, 11 temporary) gives **37 strong, 6 probable, 5 review and 12 unmatched daily-counter records**. The 43 strong/probable records propose 43 distinct `code_centrale` links. These confidence categories are analytical judgments, not publisher-confirmed identities.

Each of the 60 counter entries has a `website_detail_url` extracted from the site navigation. These URLs identify the corresponding detail pages; page availability and source freshness are separate from annual-match confidence.

The machine-readable [crosswalk](mmm-bicycle-counter-mapping.json) includes all points, source serials, proposed download serials, candidate distances, OSM IDs, annual station labels, unmatched records and review notes.

## Sources and limits

- [Vélocité website](https://compteurs.velocite-montpellier.fr/): display names and counter detail pages. Website-label correspondence is inferred from names except for Tanneurs/Albert 1er: a cached [site error response](https://compteurs.velocite-montpellier.fr/detail/clos-larmet) exposes the serial `XTH19101158` bound to Albert 1er. This corrects the initial uncertainty about that correspondence.
- [Official daily-counter locations](https://data.montpellier3m.fr/sites/default/files/ressources/MMM_MMM_GeolocCompteurs.geojson): Point coordinates, serials and `OSM_Line_i`. Retrieved through the web cache on October 31; the cache reports a crawl five months ago, so this is not a verified live snapshot.
- [Annual traffic export](https://data.montpellier3m.fr/sites/default/files/ressources/MMM_MMM_Comptage.json): local download `MMM_MMM_Comptage.json`, filesystem timestamp October 16, 2026. All 57 bicycle codes, including 46 permanent codes, also occur in the local October 12 CSV. The local JSON and CSV are not guaranteed to be identical snapshots. Source hashes are recorded in the crosswalk.
- [Daily-dataset documentation](https://data.montpellier3m.fr/dataset/comptages-velo-et-pieton-issus-des-compteurs-de-velo): resource filenames and six serial replacements effective July 7, 2026. Daily totals represent the preceding day; historical `_archive.json` files are available.

Our annual source supplies **LineString road geometry**, not counter points. Distances below are from the daily Point to the nearest position on that line, using a local planar approximation. A point on a long road segment does not independently prove counter identity. Geometry coordinates take precedence over the separate Latitude/Longitude properties, which sometimes differ.

## Strong and probable proposals

Strong requires matching OSM ID, compatible name, distance at most 50 m and no identified conflict. Probable proposals retain a name/location match with a changed OSM ID or a larger displacement. Neither category validates direction, population or historical continuity.

| Daily-location name | `code_centrale` | Daily serial to try | Distance (m) | Confidence |
| --- | --- | --- | ---: | --- |
| Albert 1er (Tanneurs) | `77574` | `XTH19101158` | 73.7 | probable |
| Beracasa | `78960` | `X2H19070220` | 16.4 | strong |
| Celleneuve | `180120` | `X2H20042633` | 0.7 | strong |
| Lavérune | `55912` | `X2H24042101` | 2.7 | strong |
| Vieille poste | `77317` | `ZLT25011699` | 6.7 | strong |
| Lattes 2 | `3738` | `X2H20042634` | 2.6 | strong |
| Lattes 1 | `55695` | `X2H20042635` | 3.9 | strong |
| Figuerolles | `213357` | `X2H21070344` | 0.5 | strong |
| Pavelet | `45810` | `X2H21070345` | 1.4 | probable |
| M21 Jacou | `99616` | `X2H21070346` | 11.1 | strong |
| Albert Einstein | `127425` | `X2H21070348` | 0.0 | probable |
| Lapeyronie | `139182` | `X2H21070349` | 123.1 | probable |
| M185 Arnel | `13833` | `X2H25023006` | 0.1 | strong |
| Mas de Gau | `110400` | `ZLT26043541` | 11.7 | strong |
| M5 Lèques | `71888` | `XTH21015106` | 0.4 | strong |
| Clapiers | `175423` | `XTH24072390` | 0.0 | strong |
| Saint-Jaumes nord | `143921` | `ZLT26063734` | 0.9 | strong |
| Saint-Jaumes sud | `182559` | `X2H21111121` | 1.3 | strong |
| RM65 Thomassy | `123163` | `X2H22043029` | 0.1 | strong |
| Juvignac Europe | `118585` | `X2H22043030` | 0.1 | strong |
| Castelnau - Aube-Rouge 1 | `43444` | `X2H22043031` | 0.9 | strong |
| Castelnau - Aube-Rouge 2 | `43448` | `X2H22043032` | 0.2 | strong |
| Allée de la Méditerranée | `148467` | `X2H22043033` | 27.2 | strong |
| Gerhardt 2 | `113978` | `X2H22104770` | 0.7 | strong |
| Jean Mermoz | `52371` | `X2H22104768` | 0.1 | strong |
| Pompignane1 | `142874` | `X2H22104773` | 3.9 | strong |
| Pompignane2 | `48995` | `X2H22104774` | 0.2 | strong |
| Clos l'armet | `66551` | `X2H22104767` | 19.6 | probable |
| Grabels | `75407` | `X2H22104766` | 0.1 | strong |
| Cournonsec | `89094` | `X2H22104765` | 0.2 | strong |
| Gossec | `16190` | `X2H22104769` | 0.3 | strong |
| Léon Vallois | `175319` | `X2H22104777` | 0.4 | strong |
| Georges Frêche 1 | `167044` | `ZLT26063738` | 0.2 | strong |
| Georges Frêche 2 | `143800` | `X2H22104771` | 0.1 | strong |
| Saint-Martin Cité Mion | `22646` | `COM23120111` | 1.2 | strong |
| Saint-Lazare | `63476` | `COM23120113` | 0.0 | strong |
| Quai de Cythère | `146966` | `COM23120114` | 0.1 | probable |
| Lac du Crès | `90608` | `COM23120117` | 3.7 | strong |
| Villeneuve | `105782` | `COM24010120` | 3.2 | strong |
| Université Paul Valery | `106699` | `COM24010121` | 0.0 | strong |
| Compteur Vélo Saint Georges d’Orques  | `13790` | `COM24010119` | 0.0 | strong |
| Galerie cyclable Victor Hugo | `69765` | `ZLT25112692` | 0.1 | strong |
| Galerie cyclable Frédéric Mistral | `23756` | `ZLT25112669` | 0.0 | strong |

## Cases requiring review

| Daily counter | Nearby annual code(s) | Finding |
| --- | --- | --- |
| Compteur Vélo Delmas 2 | `53588` (5.6 m), `63476` (430.2 m) | Delmas 1/2 share exactly the same published point. Candidate 53588 is 5.6 m away, but the published OSM ID points to Saint-Lazare (63476), 430.2 m away. Cannot establish separate side/channel identities. Location-file serial has Y2H; resource list uses X2H. Preserve both forms. Replacement serial follows the publisher notice effective 2026-07-07; continuity of counts not validated. |
| Compteur Vélo Delmas 1 | `53588` (5.6 m), `63476` (430.2 m) | Delmas 1/2 share exactly the same published point. Candidate 53588 is 5.6 m away, but the published OSM ID points to Saint-Lazare (63476), 430.2 m away. Do not map to Saint-Lazare merely by OSM ID. Location-file serial has Y2H; resource list uses X2H. Preserve both forms. Replacement serial follows the publisher notice effective 2026-07-07; continuity of counts not validated. |
| Compteur Vélo Gerhardt 1 | `83588` (1.4 m), `113978` (6.6 m) | Two Gerhardt lines are 1.4 m and 6.6 m away. Nearest is impair (83588); serial-to-side needs confirmation. Gerhardt 2 independently matches pair (113978). Location-file serial has Y2H; resource list uses X2H. Preserve both forms. |
| Compteur Vélo Pas du Loup 1 pair | `22382` (15.1 m) | Daily location says pair, but nearest annual site is labelled Pair - 2 and matches the OTHER serial by OSM ID. Leave both sides unresolved. |
| Compteur Vélo Pas du Loup 2 impair | `22382` (3.7 m) | OSM and distance support 22382, but the daily label says impair whereas the annual label says Pair - 2. Leave both sides unresolved. |

In particular, **do not automatically join Delmas by OSM ID**: that would attach both daily counters to Saint-Lazare, 430 m away. The closer annual station is `53588`, but the two daily sensors cannot be separated using the published coordinates.

## Daily points without an annual bicycle match

No candidate within 100 m and no equal OSM ID was found for:

| Daily counter | Source serial |
| --- | --- |
| Compteur Vélo Méric | `X2H20104132` |
| Compteur Vélo Père Soulas 1 | `X2H21070341` |
| Compteur Vélo Père Soulas 2 | `X2H21070342` |
| Compteur Vélo M17 Montferrier 1 | `X2H21070343` |
| Compteur Vélo N17 Montferrier 2 | `X2H21070347` |
| Compteur Vélo Renouvier bande cyclable | `X2H22043034` |
| Compteur Vélo Renouvier chaussée | `X2H22043035` |
| Compteur Vélo Vieussens1 | `X2H22104775` |
| Compteur Vélo Vieussens2 | `X2H22104776` |
| Compteur Vélo Sabines | `COM23120110` |
| Compteur Vélo Rue Pierre Causse | `COM23120112` |
| Compteur Vélo et Trottinette Cours Gambetta | `ZLT25112720` |

This means absent from this annual bicycle snapshot under the stated search rules; it does not prove that a counter is inactive or that no mapping exists in another snapshot. Temporary annual campaigns are not substitutes for permanent counters merely because they are nearby.

## Serial handling and integration implications

Six location-file serials start with `Y2H`; the publisher resource list instead uses `X2H` for the same numeric suffix. Both forms are preserved in the JSON. Do not construct download URLs directly from the location serial. Celleneuve was independently checked against the daily JSON: its `id` uses `X2H20042633`, its `laneId` is `734202564`, and its population is `bicycle`.

The publisher replacement notice gives:

| Old serial | Replacement serial |
| --- | --- |
| `X2H21070342` | `ZLT26063735` |
| `X2H21070351` | `ZLT26043541` |
| `X2H21111120` | `ZLT26063734` |
| `X2H20063163` | `ZLT26063736` |
| `X2H20063164` | `ZLT26063737` |
| `X2H22104772` | `ZLT26063738` |

Keep the old serial as an alias and a dated replacement relationship. The notice places old data in `MMM_EcoCompt_Archives.zip`; completeness and continuity across the replacement date have not been verified. Generated resource URLs in the crosswalk are candidates derived from the publisher list, not individually downloaded and validated.

Before integration, validate `vehicleType`, `laneId`, date coverage and direction for each selected daily resource. Some counters also count pedestrians; annual channel labels must be inspected before linking values. Daily totals and annual working-day averages (`mja_jo`) have different meanings. Avoid summing a combined channel with its component directions, or interpreting missing days as zero.

Start with strong proposals, keep probable/review records explicit, and use serial aliases across hardware replacements. A future source refresh should rerun matching and compare identifiers and metadata; this snapshot should not become an unconditional production lookup.
