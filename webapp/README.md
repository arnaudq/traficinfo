# TraficInfo webapp

Static map centered on metropolitan France, with on-demand traffic counts and Jawg tiles
served through the TraficInfo API Gateway. City boundaries have a solid outline and light fill;
Region boundaries have an unfilled dashed outline. Click a City or Region
outline to open its available maps, or use the keyboard-accessible map list.
For France, Cities correspond to EPCI and Regions to départements.

## Prerequisites and installation

Install Node.js 24 LTS from <https://nodejs.org/>. Node.js includes npm, the
package manager used here. The exact version used for development and GitHub
Actions is recorded in `.nvmrc`. If you use nvm, run `nvm install` and `nvm use`
inside `webapp/` to select that version.

This setup is independent of the OCI environment initialized by
`scripts/setup.sh`; that script does not install Node.js or webapp packages.

From the repository root:

```sh
cd webapp
npm ci
npm run dev
```

Open the local URL printed by Vite, usually <http://localhost:5173>.
An internet connection is required to install packages, prepare boundaries and
load map tiles and boundary layers.
Leaflet itself is bundled into the website, rather than fetched from a CDN
by the browser.

## Development commands

Run these commands from `webapp/`:

| Command                      | Purpose                                                  |
| ---------------------------- | -------------------------------------------------------- |
| `npm ci`                     | Install the versions recorded in the lockfile            |
| `npm run dev`                | Start the development server with live updates           |
| `npm run typecheck`          | Check types in the client, build scripts and tests       |
| `npm run lint`               | Lint TypeScript and tool configuration                   |
| `npm test`                   | Test configuration, territories and count data loading   |
| `npm run prepare:boundaries` | Download configured contours into the local build cache  |
| `npm run format`             | Apply consistent formatting                              |
| `npm run format:check`       | Check formatting without changing files                  |
| `npm run build`              | Generate the deployable website in `dist/`               |
| `npm run preview`            | Serve the production build locally, usually on port 4173 |

`npm run preview` requires a successful build first. Node.js and npm are
development/build tools; the published site runs entirely in the browser.

## Boundary configuration and build

The root `mapconfig.json` is the authoritative map and boundary registry. Each
map references a boundary and selects its datasources; City boundaries reference
their containing Region. A Region popup lists its child City maps and any map
attached directly to the Region. Maps without a published `uri` have no uMap link.
The client initially loads contours only, without traffic-count GeoJSON.
Choose **Afficher la carte** in a territory popup or the available-map list
to zoom to the selected boundary and load its configured source/population
layers. Departmental sources remain complete layers; counts are not clipped to
the selected city boundary.

Counts use green (≤1,000/day), blue (≤5,000/day), or red (>5,000/day)
markers bearing the original uMap car or bicycle SVG pictogram. Hover for a count label;
click a circle or focus it and press Enter/Space for source, counter ID, year,
average and the published compact history. Vehicle popups include available
speed fields.

Nearby points cluster within a 50-pixel radius, across datasources but separately
for vehicles (slate) and bicycles (purple, dashed). Cluster size increases with
point count; its number counts mapped points, not unique physical sites or
summed traffic volumes. Hover for a label and click or press Enter to zoom in.
At maximum zoom, clicking spreads overlapping points so each popup stays
accessible. Lines and polygons retain their original geometry and do not cluster.
Clusters remain active at all zoom levels to keep coincident points accessible.
Counter popups include **Plus de détails**, opening the complete published
counter history. Advanced map filters are not yet implemented; downloaded GeoJSON
stays independent of the rendered groups so future filters can rebuild clusters
from matching points without downloading again.

Detailed maps show an address/place search bar. Submit at least three characters
with Enter or **Rechercher**, then select a result to zoom to its location.
Search uses IGN's public Géoplateforme service (France), with the current map
center as a proximity preference. Queries go directly to IGN. Switching maps
or returning to territories clears the search and its marker.

Maps can optionally set `"searchProvider": "ign"` in `mapconfig.json`; IGN is the
default. To support another country, add an adapter to `src/search-providers.ts`
implementing `SearchProvider` (search and attribution), then extend the registry
schema and validators, including the uMap generator. Adapters return labels and
`[longitude, latitude]` coordinates; the search UI and navigation remain provider
independent. No other provider is bundled yet.

Switching maps removes previous counts; **Retour aux territoires** unloads them
and restores the France view. Contours remain independently available. Successful
downloads are cached for the page session, and requests completed after leaving
a map cannot restore its layers. Individual layer failures leave successful
layers visible and identify unavailable sources in the status. Select the map
again to retry failed downloads; refresh the page to fetch updated cached data.

`npm run dev` and `npm run build` automatically run `prepare:boundaries`. This
downloads only configured contours from the public bucket to the ignored
`.cache/boundaries/` directory and validates their identifiers and geometry.
Publish those files with `BoundaryFunction` before building the website.

The Vite plugin reads the prepared local files, calculates each territory's
`bounds` as `[west, south, east, north]`, and emits `dist/mapconfig.json`. The
public copy keeps map selections and boundary relationships, omitting the
repository schema reference and uMap property overrides. The root registry is
not modified. Missing/invalid geometry or broken registry references fail the
build. To build again offline from already prepared files, run `npm exec vite build`.

At runtime, the browser reads the site's public configuration and downloads its
configured contours directly from the bucket. It does not list bucket objects,
load the entire national dataset or fetch count layers before map selection.
Extra départements published by the boundary function remain hidden until added
to the registry.
Individual contour failures leave other contours and the map list usable.

See the [map specification](../specs/map-templates.md#webapp-territory-boundaries)
for publication and interaction requirements. Bounds and datasource selections
drive the on-demand count view.

## Direct map URLs

Open a map directly with its ID, for example `/MMM`, or its optional `path`
from `mapconfig.json`. MMM sets `"path": "Montpellier"`, so `/Montpellier`
opens the same detailed map. IDs remain usable when a path is configured.
Paths are case-sensitive single segments using letters A–Z/a–z, digits,
underscores and hyphens, starting with a letter or digit. They must be unique
across all map IDs and paths; `assets`, `licenses` and `src` are reserved.
Selecting a map updates the address to its configured path (or ID), and browser
Back/Forward restores map selection. **Retour aux territoires** returns to the
site root. A directory slash may be added by the static host.

The build emits an entry page for every ID and configured path, so direct visits
and refreshes work on GitHub Pages without rewrite rules. URLs include the site
prefix when hosted below a project path, for example `/traficinfo/Montpellier/`.
Assets, configuration and the licences link resolve from the site root.
Rebuild and deploy after adding or changing paths.

## Counter details and complete history

`counter.html?source=MMM&id=4584&type=VEHICULES&map=MMM` opens the selected
counter's history. `source` and `id` are required; `type` selects the initial
population and optional `map` provides the return link to the originating map.
IDs are preserved as strings and encoded in popup links. The static HTML entry
point supports direct visits and refreshes on GitHub Pages, including project
paths. Accident popups do not receive counter-history links.

The page loads `history/<source>.json` relative to the configured
`geoJsonBaseUrl`, using the canonical schema defined by the parsers module.
Publish it with `HistoryPublicationFunction` before opening counter histories;
see the [export instructions](../dsfunctions/README.md#canonical-counter-history-export).
Only the chosen datasource's file is fetched. A shared promise caches the file
for the page session; changing population, direction or year uses the same data.
New page visits can reuse the browser's HTTP cache, subject to the export's
five-minute cache lifetime. Failed downloads remain retryable.

The metadata shows whether the counter is permanent, temporary, unknown or
varies between campaigns, plus the full year range across all populations and
directions. This range stays unchanged when filters change.

**Graphiques** and **Tableau** tabs switch locally between an annual chart and
exact campaign measurements. The chart uses years on the horizontal axis,
count bars against the left passages/day axis, and mean-speed/V85 lines against
the right km/h axis. Multiple campaigns within a year have separate marks;
missing years or speeds break lines. The table includes numeric/textual peak-hour
measurements and coverage where available. Tabs support arrow keys, Home and End,
retain the chosen view across filter changes, and trigger no extra download.
Dataset attribution and the publication date are also displayed. Missing years and
missing measurements are shown explicitly, separately from measured zero.
Multiple campaigns in one year remain distinct. Combined directions are selected
initially when available and are never added to their directional components.
Population, direction and year controls filter measurements locally.

The client validates schema/version, datasource identity, campaign references,
unique IDs and nullable integer measurements before rendering. Unknown counters,
empty selections and unavailable or invalid histories have explicit states.
Descriptions and peak text are rendered as text, and attribution links accept
only HTTP(S) URLs. Tables scroll horizontally on smaller screens.

uMap popup links and embedding remain future work.

## Source layout

- `index.html`: page structure and application entry point.
- `src/main.ts`: imports styles and starts the application.
- `src/map.ts`: creates the Leaflet map and base layer.
- `src/config.ts`: public basemap URL, zoom limits and provider attribution.
- `src/map-navigation.ts`: map URL resolution and preferred paths.
- `build/map-pages.ts`: static direct map pages and development routes.
- `src/overview.ts`: loads overview layers and connects map selection controls.
- `src/count-data.ts`: framework-independent layer URLs, validation and download cache.
- `counter.html` and `src/counter-page.ts`: standalone counter-history view, chart,
  local filters, exact measurements and coverage.
- `src/history-data.ts`: canonical history types, validation, source download cache,
  series selection and encoded popup URLs.
- `src/counter-page.css`: counter-details layout and chart styles.
- `src/count-map.ts`: Leaflet count-layer lifecycle and counter popups.
- `src/count-clusters.ts`: population-specific clustering and point icons.
- `src/icons/`: the original car and bicycle Osmic pictograms, downloaded from
  <https://umap.openstreetmap.fr/uploads/pictogram/car.svg> and
  <https://umap.openstreetmap.fr/uploads/pictogram/bicycle.svg>. uMap's
  <https://umap.openstreetmap.fr/en/pictogram/json/> attributes both to Osmic.
  Vite bundles them locally; marker icons require no requests to uMap.
- `src/dom.ts`: shared text-only HTML element creation.
- `src/territories.ts`: boundary URLs, map relationships and geometry validation.
- `src/types.ts`: shared map, boundary, public configuration and GeoJSON types.
- `tsconfig.json`: strict type checking without emitting JavaScript.
- `src/styles.css`: application styling.
- `licences.html`: licences, third-party attribution and access to source code.
- `build/legal-notices.ts`: source revision links and licence publication.
- `build/prepare-boundaries.ts` and `build/map-config.ts`: prepared geometry and
  generated public map configuration.
- The footers in `index.html` and `licences.html` embed the GitHub Octicons SVG
  directly. Its licence is included in `public/licenses/Octicons.txt`, which
  Vite copies into the built site. GitHub logo usage also follows its
  [brand guidelines](https://brand.github.com/foundations/logo).
- `vite.config.ts`: build settings, including relative asset URLs for Pages.
- `eslint.config.js` and `.prettierrc.json`: code checking and formatting.

Use descriptive names and straightforward TypeScript functions. Add modules
when a feature needs its own responsibility; avoid clever abbreviations and
code-size shortcuts. JavaScript minification is disabled in the production
build to keep the generated code readable during prototyping.

## Dependencies and versions

`package.json` declares exact versions of direct dependencies and the commands
above. `package-lock.json` records the complete installed dependency tree.
Commit both files together when updating packages. `.npmrc` makes new direct
dependencies use exact versions by default.

`node_modules/` (installed packages) and `dist/` (generated output) are ignored
by Git. Use `npm ci` after a fresh checkout or a dependency update.

To add or update a dependency deliberately:

```sh
npm install leaflet@<version>
npm install --save-dev vite@<version>
```

Replace `<version>` with the chosen release. Review changes, run lint,
format checks and a build, then verify the map in a browser. `npm outdated`
lists available updates; `npm audit` reports known dependency advisories.
Review findings before applying fixes, especially major upgrades.

The client, build plugins and tests use TypeScript. Node.js 24 runs the build
scripts and tests with its built-in type stripping; Vite compiles the browser
code to JavaScript. Use explicit `.ts` extensions and `import type` for type-only
imports. Keep syntax compatible with `erasableSyntaxOnly` (no runtime enums or
parameter properties). Type stripping does not check types: `npm run typecheck`
runs the compiler, and both `npm run build` and CI require it to pass. Downloaded
JSON still passes runtime validation.

The application starts at version `0.1.0`; optional release tags can use
`webapp-v0.1.0` independently of Java releases. Each Actions deployment is also
associated with its Git commit.

## GitHub Pages

The repository workflow `.github/workflows/deploy-webapp.yml` runs installation,
linting, formatting checks and a production build on relevant pull requests.
Pushes to `main` also publish **only `webapp/dist/`** to GitHub Pages. It can
also be run manually on `main` from the Actions tab.

Enable it in the repository's **Settings → Pages → Build and deployment →
Source → GitHub Actions**. The workflow does not change this repository setting.

Changes under `webapp/`, to the root `mapconfig.json`, `dsconfig.json`, `LICENSE`,
or to the workflow itself trigger it. Java-only changes do not redeploy the website.
The build needs anonymous access to the published boundary objects. Refreshing
bucket geometry alone does not trigger Pages deployment; run the workflow
manually when its generated bounds need updating.

Vite generates relative asset URLs so the same output works under a project
path such as `/traficinfo/` or at the root of a custom domain. The build includes
a map overview, direct map entry pages and a static licences page; no server
routing rules are required.

## Licences and source access

The map footer links to `licences.html` and to the Git revision used to build
the website. The licences page also links to a source archive at that revision,
the source of the installed Leaflet version, and the complete licence texts.

The Vite plugin in `build/legal-notices.ts` reads the root `LICENSE` and the
installed Leaflet package's `LICENSE` and Leaflet.markercluster's `MIT-LICENCE.txt`. It publishes them as
`licenses/AGPL-3.0.txt`, `licenses/Leaflet.txt` and `licenses/Leaflet.markercluster.txt` during development and copies
them into `dist/` on each build. Do not maintain separate handwritten copies.
Git must be available when starting the development server or building the site.

In GitHub Actions, the source link identifies the checked-out deployment commit.
For local development or builds, it identifies the latest local commit;
uncommitted edits are not included in the linked source. Commit and push the
changes before publishing a build. Restart the development server after changing
the licence texts or checking out another commit.

Boundary attribution is displayed on the map. `dsconfig.json` is used only for
build-time reference validation and is not published to the website.

The basemap URL in `src/config.ts` uses the public tile template from
`tofu -chdir=infra/environments/dev output -raw tile_url_template` (run from the
repository root). The gateway chooses the Jawg style and injects its access
token; no Jawg token belongs in the webapp configuration or build output. If
the gateway is replaced, update this public URL and rebuild the webapp.

Keep the visible Jawg Maps and OpenStreetMap attribution. See
[Jawg's Leaflet integration](https://www.jawg.io/docs/integration/leaflet/)
for provider attribution requirements.

## Accident layers

Map selections may use `{"id":"ONISR_DPT34","types":["ACCIDENTS"]}` alongside
count selections. Accident IDs refer to public `<id>_Accidents.geojson` files,
independently of the count datasource registry. MMM selects ONISR_DPT34, NIM
ONISR_DPT30, MRN ONISR_DPT76, and REN ONISR_DPT35. The REN map also selects
Rennes Métropole vehicle counts (`REN`). A source cannot mix ACCIDENTS with count
populations. The public registry preserves this selection for the browser.

Accidents use separate fatal/non-fatal clusters and exclamation-mark icons, with dark red for
recorded deaths, light orange for recorded injuries, and grey when neither is recorded.
Hovering an accident shows “Accident : x tués, x blessés”; the injured total
combines hospitalized and lightly injured participants across all categories.
Popups show accident ID, date/time, year, way ID and recorded participant totals
by injury severity. The layer control offers **Accidents mortels** (enabled by
default) and **Accidents non mortels** (disabled by default), alongside
vehicle-count and bicycle-count layers. Fatal accidents have at least one
recorded `KILLED` participant; all others, including unknown severity, belong
to the non-fatal layer. Both groups use the same downloaded GeoJSON; switching
visibility does not download it again. Visibility choices persist when switching
maps during the page session. Departmental accident data, like
count data, remains complete and is not clipped to the selected city boundary.

Publish the accident GeoJSON with the accident-publication function before
using the layers. Rebuild/deploy the webapp to apply mapconfig.json changes.
