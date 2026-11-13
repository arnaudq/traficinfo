# TraficInfo webapp

Static boundary overview centered on metropolitan France, with OpenStreetMap
France's `osmfr` tiles. City boundaries have a solid outline and light fill;
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
| `npm test`                   | Test configuration, bounds and territory relationships   |
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
load the entire national dataset or fetch count layers. Extra départements
published by the boundary function remain hidden until added to the registry.
Individual contour failures leave other contours and the map list usable.

See the [map specification](../specs/map-templates.md#webapp-territory-boundaries)
for publication and interaction requirements. Bounds and datasource selections
are retained for a subsequent release that loads counts for a selected map.

## Source layout

- `index.html`: page structure and application entry point.
- `src/main.ts`: imports styles and starts the application.
- `src/map.ts`: creates the Leaflet map and base layer.
- `src/overview.ts`: loads overview layers, popups and the accessible map list.
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
path such as `/traficinfo/` or at the root of a custom domain. The application
has a map page and a static licences page; neither needs client-side routing rules.

## Licences and source access

The map footer links to `licences.html` and to the Git revision used to build
the website. The licences page also links to a source archive at that revision,
the source of the installed Leaflet version, and the complete licence texts.

The Vite plugin in `build/legal-notices.ts` reads the root `LICENSE` and the
installed Leaflet package's `LICENSE`. It publishes them as
`licenses/AGPL-3.0.txt` and `licenses/Leaflet.txt` during development and copies
them into `dist/` on each build. Do not maintain separate handwritten copies.
Git must be available when starting the development server or building the site.

In GitHub Actions, the source link identifies the checked-out deployment commit.
For local development or builds, it identifies the latest local commit;
uncommitted edits are not included in the linked source. Commit and push the
changes before publishing a build. Restart the development server after changing
the licence texts or checking out another commit.

Boundary attribution is displayed on the map. `dsconfig.json` is used only for
build-time reference validation and is not published to the website.

Keep the visible map attribution and follow the provider's
[usage policy](https://www.openstreetmap.fr/usage/).
Tile URL and zoom levels follow the
[OpenStreetMap France tile documentation](https://wiki.openstreetmap.org/wiki/FR:Serveurs/tile.openstreetmap.fr).
