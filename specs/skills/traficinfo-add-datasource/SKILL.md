---
name: traficinfo-add-datasource
description: Add or extend traffic-count data sources in the TraficInfo repository, from publisher inspection and parser registration through retrieval, preprocessing, GeoJSON output and selected uMap layers. Use for a new department or traffic-count publisher, or a requested subset of that integration.
---

# Add a TraficInfo datasource

Work in the user's TraficInfo checkout. Read its current `AGENTS.md` and
`specs/add-datasource.md` before changing the pipeline. The recipe is maintained
in the repository; resolve its path from the checkout root, not this installed
skill's directory. If the checkout is not available, obtain its location rather
than assuming an unrelated project has the same structure.

Apply the requested stages only. Parser-only, retrieval-only and preprocessing
requests are valid partial integrations. Implementing a function does not imply
cloud deployment, live uMap edits or committing changes.

## Decision points

- Inspect actual source records and metadata. Prefer the publisher's main page
  for the dataset link; use a machine endpoint for retrieval. Separate observed
  years from update timestamps. Check complete record counts and pagination.
- Verify identity uniqueness and missing values. A repeated station code can
  represent different points. Choose and document a prefixed counter ID; do not
  assume a technical FID is stable across future publications. DPT76's FID choice
  was explicitly scoped to its sole available 2022 dataset.
- Distinguish geometry from projected coordinate attributes. A GeoJSON endpoint
  may provide all traffic data and WGS84 points without a CSV or dataset join.
  Keep missing measures and geometry distinct from zero values.
- Reuse the three existing shared stages: `DataSourceFunction`,
  `PreprocessingFunction`/`PreprocessingPublisher`, and
  `GeoJsonFunction`/`GeoJsonPublisher`. Use source-specific parsers/models and the shared
  yearly manifest contract; do not build a parallel storage pipeline.

## Repository wiring

Follow the recipe's stage-specific file references and tests:

1. Parser/model and `GeoJsonParser.DataSource` registration; package README and
   representative fixture. Account for registry tests that assume CSV fixtures.
2. `dsconfig.json` source/dataset/inputs and redeployment of shared retrieval.
   Source URLs belong in configuration, not Java fallback constants.
3. `PreprocessingSource` registration and expected counts content type. Keep CSV
   header validation for CSV; use GeoJSON parsing for GeoJSON. Preserve immutable
   yearly records, checksum verification and publish the manifest last.
4. `GeoJsonDataSource` registration and redeployment of shared GeoJSON. For a manifest
   event workflow, route the last configured raw input to shared preprocessing
   and `<ID>_Manifest.json` to GeoJSON. Companion inputs precede counts.
5. Requested `mapconfig.json` populations and regenerated selected `.umap` file.
   Use `<ID>_Logo.png` in existing popups; reuse a manually supplied logo.

DPT76 is the concrete GeoJSON example; DPT30/DPT34 illustrate CSV inputs and MMM
has companion geometry. Treat their historical decisions as examples, not
universal source requirements. The shared HTTP client already falls back to
SHA-256 for absent or malformed source ETags, including unquoted ArcGIS tags.

## Completion

Run the appropriate existing suites and focused source tests. Verify registry
serialization, publication failure behavior and event filters where touched.
Compare broader failures to a baseline before classifying them as pre-existing.
Regenerate/check map artifacts rather than editing them alone. Report which
stages are ready and which are deployed. Raw-input events invoke shared
preprocessing, and changed manifests invoke shared GeoJSON. Follow the recipe
for initial publication order when deployment is requested.
