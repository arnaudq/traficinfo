# uMap configuration

Configuration of the [Montpellier traffic map](https://umap.openstreetmap.fr/en/map/comptage-vehicules-particuliers-de-montpellier_754880).

All UI paths start in **Edit mode**:

- **Map settings** means **Map advanced properties**.
- **Layer settings** means **Manage layers → pencil beside the traffic layer**.
- **Manage Fields** and **Manage filters** refer to the traffic layer, not the map's global fields.
- In JSON locations, `L` abbreviates `layers[0].properties`.

| Setting | JSON location | Current value | How to set it in the uMap UI |
|---|---|---|---|
| Map title | `properties.name` | Comptage véhicules particuliers de Montpellier | **Edit map name and caption → Name** |
| Description | `properties.description` | `Carte basée sur les données de https://data.montpellier3m.fr/dataset/comptage-vehicules-particuliers-de-montpellier`, followed by a blank line and `Mise à jour journalière` | **Edit map name and caption → Description** |
| Animated transitions | `properties.easing` | `true` | **Map settings → Default properties → Animated transitions → On** |
| Background | `properties.tilelayer` | OSM Light (jawgmaps) | **Change tilelayers → OSM Light (jawgmaps)** |
| Popup content | `properties.popupContentTemplate` | Content of [umapPopupContentTemplate.txt](parsers/src/main/resources/umapPopupContentTemplate.txt) | **Map settings → Default interaction options → Popup content template**; paste the file's content |
| Layer name | `L.name` | Comptage véhicules particuliers de Montpellier | **Layer settings → Name** |
| Label visibility | `L.showLabel` | `null` — On hover | **Layer settings → Interaction options → Display label → On hover** |
| Remote data URL | `L.remoteData.url` | https://objectstorage.eu-frankfurt-1.oraclecloud.com/n/frivwhhfdcjn/b/traficinfo/o/MMM_TraficInfo.geojson | **Layer settings → Remote data → URL** |
| Remote format | `L.remoteData.format` | `geojson` | **Layer settings → Remote data → Format → GeoJSON** |
| Counter ID field | `L.fields[0]` | `code_centrale`: **Short text** (`String`) | **Layer settings → Manage Fields → `code_centrale` → Field Type → Short text** |
| Name field | `L.fields[1]` | `name`: **Short text** (`String`) | **Manage Fields → `name` → Field Type → Short text** |
| Description field | `L.fields[2]` | `description`: **Text** (`Text`) | **Manage Fields → `description` → Field Type → Text** |
| Year field | `L.fields[3]` | `annee`: **Short text** (`String`) | **Manage Fields → `annee` → Field Type → Short text** |
| Annual daily average field | `L.fields[4]` | `moyenne_jour_annuelle`: **Number** | **Manage Fields → `moyenne_jour_annuelle` → Field Type → Number** |
| Morning peak field | `L.fields[5]` | `moyenne_pointe_matin`: **Short text** (`String`) | **Manage Fields → `moyenne_pointe_matin` → Field Type → Short text** |
| Evening peak field | `L.fields[6]` | `moyenne_pointe_soir`: **Short text** (`String`) | **Manage Fields → `moyenne_pointe_soir` → Field Type → Short text** |
| Legacy speed field | `L.fields[7]` | `vitesse_pratiquee`: **Short text** (`String`) | **Manage Fields → `vitesse_pratiquee` → Field Type → Short text** |
| History field | `L.fields[8]` | `moyenne_annuelle_history`: **Short text** (`String`) | **Manage Fields → `moyenne_annuelle_history` → Field Type → Short text** |
| V85 field | `L.fields[9]` | `v85_speed`: **Number** | **Manage Fields → `v85_speed` → Field Type → Number** |
| Mean speed field | `L.fields[10]` | `mean_speed`: **Number** | **Manage Fields → `mean_speed` → Field Type → Number** |
| Speed-limit field | `L.fields[11]` | `maximum_speed`: **Number** | **Manage Fields → `maximum_speed` → Field Type → Number** |
| Year filter | `L.filters[0]` | Field: `annee`; widget: **Multiple choices** | **Manage filters → Add filter → Filter on: `annee` → Widget: Multiple choices** |
| V85 filter | `L.filters[1]` | Field: `v85_speed`; widget: **Min/Max**; label: **Vitesse pratiquée (V85)** | **Manage filters → Add filter → Filter on: `v85_speed` → Widget: Min/Max → enter label** |
| Speed-limit filter | `L.filters[2]` | Field: `maximum_speed`; widget: **Multiple choices**; label: **Limitation de vitesse** | **Manage filters → Add filter → Filter on: `maximum_speed` → Widget: Multiple choices → enter label** |
| Annual daily average filter | `L.filters[]` (entry with `fieldKey: moyenne_jour_annuelle`) | Field: `moyenne_jour_annuelle`; widget: **Min/Max** | **Manage filters → Add filter → Filter on: `moyenne_jour_annuelle` → Widget: Min/Max** |

Save the map after changing its settings. Field and filter array indices may change; use the field names to identify entries if their order changes.
