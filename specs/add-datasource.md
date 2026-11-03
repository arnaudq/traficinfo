# Adding a TraficInfo datasource

This recipe covers the source investigation, parser, retrieval, preprocessing,
GeoJSON output and map integration used for DPT76. Apply only the stages requested
by the user. A parser-only request does not imply deployment, map publication or
committing changes. Start by reading the current `AGENTS.md` and inspecting the
working tree; preserve unrelated edits.

The pipeline is:

```text
Publisher input -> datasource bucket -> preprocessing -> immutable yearly records
                                                    -> manifest published last
                                                    -> GeoJSON output -> uMap layer
```

Create/update events on the last configured raw input invoke shared
preprocessing. A changed manifest then triggers shared GeoJSON output.
See the [storage contract](datasource-storage-contract.md) for the operating
assumptions and publication behavior.

## 1. Establish the source contract

Inspect actual records, metadata and any publisher data dictionary. Prefer the
publisher's dataset page for `datasetInfoUrl`, with a stable machine-readable URL
for retrieval. Separate publication/update timestamps from observation years;
read each record's observation year rather than inferring it from the filename.

Record these decisions in the parser package README:

- Source format, encoding, delimiter or GeoJSON feature structure, endpoint and
  observation years. Distinguish the source ID from the active dataset ID.
- Which fields contain traffic totals, directional counts, vehicle types,
  heavy-vehicle percentages, road references and speeds. Do not invent missing
  speeds, directions or meanings for undocumented publisher codes.
- Whether counts and locations are in one resource or require a join. Compare
  actual field lists and records before deciding that two related datasets must
  be joined. Check join multiplicity and unmatched records.
- Record count, missing geometry/measures and identifier uniqueness. Distinguish
  business station codes from technical row IDs. Repeated station codes may
  describe different locations; do not merge them without evidence.
- Coordinate reference system and coordinate order. GeoJSON geometry may already
  be WGS84 even when `x` and `y` properties remain projected coordinates. For
  ArcGIS, requesting `outSR=4326` can avoid a local transformation. Check the
  returned geometry; the map URL's `location` parameter is only the map view.
- API completeness: compare the downloaded feature count to a count query and
  inspect transfer-limit flags. Add pagination if required; the shared downloader
  stores one HTTP response and does not assemble pages.

DPT76 example: the point resource already contains traffic and geometry. Its
357 inspected records all describe 2022, with 352 located records. There are 243
station codes; none were missing in that snapshot. The chosen counter ID is
`DPT76_<FID>`. FID is unique within this publication, with no established
cross-publication stability. `Code_stat_` and `objectid` are retained as source
attributes. Counts for different FIDs sharing a station code remain separate.
These are snapshot findings, not requirements for future sources.

## 2. Implement and register the parser

Use the closest existing parser as a structural reference. For point GeoJSON,
inspect `parsers/src/main/java/org/traficinfo/parsers/dpt76/`; for ordinary CSV,
inspect `dpt30/` or `dpt34/`. Keep source-specific models independent rather than
inheriting another department's model.

Implement `Comptage` and `ComptageParser`, including Reader/file entry points and
display-name overrides. Register the source ID, parser and model class in
`GeoJsonParser.DataSource`. The registry also supports model storage and source
selection by the comparison tools.

Keep a prefixed counter ID, explicit observation year and nullable source measures.
The shared integer daily-average getter can return 0 for a missing measure, but
retain a nullable getter to distinguish missing from measured zero. Use WGS84
geometry in longitude, latitude order. Keep unlocated records with null geometry;
do not silently treat projected coordinates as longitude/latitude. Normalize road
references only where the source meaning supports it. Derive `directionType` from
the chosen measure's coverage, not the uniqueness of a station code.

Validate malformed collections/rows, required identity/year fields, duplicates
and geometry as appropriate to the source. Reject explicitly truncated responses.
Preserve existing conventions for unavailable hourly peaks, OSM IDs and speeds.
Unknown optional columns/properties need not fail parsing.

Add a small representative source fixture and tests for mappings, IDs, repeated
business codes, missing versus zero, coordinates, display overrides and shared
GeoJSON output. Use non-ASCII names/BOMs when relevant. Update registry-wide tests
that assume all fixture names end in `.csv`; do not disguise GeoJSON as CSV.
Verify `ComptageJson` round trips and comparison behavior for the new model.

For a single-period publication, a source name such as
`DPT76_Comptage_2022.geojson` can make its scope clear. Its retrieval archives are
`DPT76_Comptage_2022_yyyymmdd.geojson`, where the suffix is the previous download's
UTC date. This differs from the immutable preprocessed yearly object name.

## 3. Add retrieval configuration

Add an entry to `dsconfig.json` using its source ID consistently:

- `id`, `name`, `schedule`, `activeDatasetId` and `datasets`.
- Each dataset defines `years`, `datasetInfoUrl` and ordered `inputs`.
- Each input defines `url` and `contentType`; non-CSV inputs need an explicit
  `objectName`. Use `application/geo+json` for a GeoJSON counts source.
- Set `geoJsonEnabled: false` while its output handler is absent. Optional naming
  overrides are for real exceptions, not routine copies of default names.
- Use `timeoutSeconds` only when the source requires a larger retrieval timeout.

The shared `DataSourceFunction` resolves each request's `datasourceId` from
bundled configuration. No per-source retrieval handler or deployment entry is
needed. Redeploy it after configuration changes and generate the source's
retrieval schedule with its datasource-ID invocation payload.

The shared `SourceObjectPublisher` already downloads, skips unchanged content,
archives before replacement and records source metadata. `ObjectStoreClient`
handles gzip. `DataSourceSettings` and `scripts/oci-common.sh` accept `.csv`,
`.json` and `.geojson` input names. Extend shared validation deliberately if a
new format actually requires it.

Use the shared `HttpClient`: valid server ETags support conditional retrieval;
missing or malformed ETags use SHA-256 content comparison. An unquoted ArcGIS
ETag caused the DPT76 failure; its regression test belongs in the HTTP client,
not a datasource-specific downloader. This source HTTP behavior is distinct
from the repository rule against ETag-based OCI updates.

Test Fn configuration, first/unchanged/changed downloads, metadata, archive
naming, content type, download failure and archive failure with fake services.
Verify previous content is preserved when replacement cannot proceed.

## 4. Register preprocessing

Register the parser in `PreprocessingSource`. CSV sources declare delimiter and
required headers; GeoJSON sources declare the counts content type. Settings and
publication select the counts input using that expected type. MMM's companion
geometry handling remains a separate source requirement.

Reuse `PreprocessingFunction` and `PreprocessingPublisher`, rather than introducing
a parallel publisher. Respect [the shared storage contract](datasource-storage-contract.md):

- Stage inputs, validate provenance and parse only the configured year scope.
- Preserve null measures and geometry in stored source models.
- Produce deterministic yearly JSON arrays under
  `data/<revision>/<ID>_Comptage_<year>.json`.
- Retain unchanged and inactive year references, verify checksums/record counts,
  then publish `<ID>_Manifest.json` last under the shared lock.
- Keep the previous manifest usable on parsing/write/readback failure.
- Use `GEOJSON` with null `headers` for GeoJSON inputs in manifest metadata.
- Maintain `parserVersion` when stored interpretation changes.

Test publication/readback through `ManifestComptageReader`, repeated station codes,
nulls, year scope, reordered/unchanged input and failures preserving the manifest.
Test the shared Fn handler with bundled settings and datasource-ID requests. Archive preprocessing, if
requested, uses explicit inactive dataset configuration and separate source
objects; do not switch the active download merely to import historical data.

## 5. Add GeoJSON output and event routing

Register the source in `GeoJsonDataSource` with its parser. `GeoJsonFunction`
resolves the input bucket from bundled configuration at invocation time. No
source-specific handler or deployment entry is needed; redeploy the shared
function after changing its source registry.

The shared output function reads the manifest and exact yearly references. It
publishes `<ID>_TraficInfo.geojson` and `<ID>_TraficInfo_BICYCLES.geojson` in the
output bucket, including an empty bicycle collection for vehicle-only sources.
It applies shared filtering/history and cached OSM enrichment. Cache names are
`<ID>_OSMInfo.json` and `<ID>_OSMWayMapping.json` in `osminfo` by default.

Enable GeoJSON in `dsconfig.json` when supported. `scripts/setup-events.sh`
watches the last configured active input for preprocessing and
`<ID>_Manifest.json` for GeoJSON generation. Put companion inputs before the
counts input. Archives and yearly Objects do not match these exact-name rules.
Existing GeoJSON rule names retain `ComptageCsvToGeoJson` for compatibility.

Test plain/gzip publication reads and gzip output, distinct counter features,
missing geometry/count filtering, OSM integration, and invalid manifests/missing
or corrupt yearly objects preventing output replacement. Extend Fn inheritance
checks and fake-OCI deployment/invocation/event tests. Verify event object,
bucket, target function and repeated setup behavior.

## 6. Add selected map layers and branding

Edit `mapconfig.json`, not only generated `.umap` files. Add the new source to the
requested map with its supported populations. DPT76 adds a vehicles layer to MRN;
the existing MRN vehicle and bicycle layers remain. Do not add an empty bicycle
layer just because the shared output function publishes that file. Update the
map description to credit the added publisher; preserve its view unless requested.

Regenerate the selected map with `scripts/generate-map-templates.sh <MAP_ID>` and
verify it using `--check <MAP_ID>`. Confirm layer URLs, order, popup placeholders
and links to the active dataset. Update `map-templates/README.md` for composition
changes. The map popup resolves `<ID>_Logo.png` in the shared output bucket.
If the user already supplied that logo manually, reuse it. Configure optional
publisher branding URLs only when a supported retrieval workflow needs them.

The generated file is ready to import into uMap; generating it does not change
the live map. Import/publication is a separate user request or authorized step.
The existing map README describes a uMap filter-save workaround when relevant.

## 7. Validate and hand over

Run checks appropriate to the requested stages:

```sh
mvn -o -pl parsers test
# For functions or pipeline changes, the reactor command also tests parsers:
mvn -o -pl dsfunctions -am test
python3 -m unittest discover -s tests -p test_oci_scripts.py -k '<source-id-lowercase>' -v
# For generator changes, use its existing test suite:
python3 -m unittest discover -s scripts/tests -p test_generate_map_templates.py -v
./scripts/generate-map-templates.sh --check <MAP_ID>
git diff --check
```

Do not run both Maven commands redundantly when the reactor suite already covers
the change. Do not run a literal placeholder: use actual IDs/test selectors.
For shared script changes, include existing-source compatibility checks. If the
broader suite fails, compare against unchanged code to establish the baseline;
report verified existing failures rather than assuming they are unrelated.
Where possible, run a downloaded source snapshot through the complete local
pipeline using fake storage, checking output counts without touching OCI.

Report implemented stages, test results and remaining manual steps. Keep module
usage documentation in its README and project designs in `specs/`. Commit only
when requested, staging the intended files explicitly.

## 8. Deployment and first publication, when requested

Assume `source ./scripts/setup.sh` has already been run. Use its exported paths,
compartment and namespace; do not repeat environment or OCI discovery. Follow
`AGENTS.md` for scripts and complete defined scheduling tags. Retrieval schedules
select the shared function with a datasource-ID payload. Preprocessing is also
shared and included in argument-free deployment. Installing code locally is not
a cloud deployment.

Replace `ID` below with the source ID. Review shared setup previews: the setup
scripts reconcile all configured sources, not just the new one.

```sh
./scripts/setup-buckets.sh --dry-run
./scripts/setup-buckets.sh
./scripts/deploy-functions.sh ds
./scripts/deploy-functions.sh preprocess
./scripts/deploy-functions.sh geojson
./scripts/invoke.sh ds ID
./scripts/invoke.sh preprocess ID
./scripts/invoke.sh geojson ID
./scripts/setup-events.sh --dry-run
./scripts/setup-events.sh
# Reconcile schedules when needed:
./scripts/setup-schedules.sh --dry-run
./scripts/setup-schedules.sh
```

Manual GeoJSON invocation provides the initial output even if its manifest was
published before the Events rule existed. Future manifest updates can trigger
output once the rule is configured. Last-input events trigger preprocessing
after source changes. Input buckets come from `dsconfig.json` for both functions
and event rules. Confirm expected raw objects, yearly records, manifest and output
before importing a generated uMap file.
