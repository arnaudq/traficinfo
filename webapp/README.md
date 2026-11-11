# TraficInfo webapp

Minimal static single-page prototype: a TraficInfo header and a Leaflet map
centered on metropolitan France, with OpenStreetMap France's `osmfr` tiles.

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
An internet connection is required to install packages and load map tiles.
Leaflet itself is bundled into the website, rather than fetched from a CDN
by the browser.

## Development commands

Run these commands from `webapp/`:

| Command                | Purpose                                                  |
| ---------------------- | -------------------------------------------------------- |
| `npm ci`               | Install the versions recorded in the lockfile            |
| `npm run dev`          | Start the development server with live updates           |
| `npm run lint`         | Catch common JavaScript mistakes                         |
| `npm run format`       | Apply consistent formatting                              |
| `npm run format:check` | Check formatting without changing files                  |
| `npm run build`        | Generate the deployable website in `dist/`               |
| `npm run preview`      | Serve the production build locally, usually on port 4173 |

`npm run preview` requires a successful build first. Node.js and npm are
development/build tools; the published site runs entirely in the browser.

## Source layout

- `index.html`: page structure and application entry point.
- `src/main.js`: imports styles and starts the application.
- `src/map.js`: creates the Leaflet map and base layer.
- `src/styles.css`: application styling.
- `vite.config.js`: build settings, including relative asset URLs for Pages.
- `eslint.config.js` and `.prettierrc.json`: code checking and formatting.

Use descriptive names and straightforward JavaScript functions. Add modules
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

Only changes under `webapp/` or to the workflow itself trigger it. Java-only
changes do not redeploy the website. When configuration files such as
`mapconfig.json` become build inputs, add their paths to both workflow filters.
The current prototype does not consume them.

Vite generates relative asset URLs so the same output works under a project
path such as `/traficinfo/` or at the root of a custom domain. The application
currently has a single page and requires no client-side routing rules.

The prototype displays only the base map; it does not yet load traffic layers,
`mapconfig.json` or `dsconfig.json`.

Keep the visible map attribution and follow the provider's
[usage policy](https://www.openstreetmap.fr/usage/).
Tile URL and zoom levels follow the
[OpenStreetMap France tile documentation](https://wiki.openstreetmap.org/wiki/FR:Serveurs/tile.openstreetmap.fr).
