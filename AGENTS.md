# Repository instructions

## Documentation

- Keep project specifications and design documents in `specs/`; do not create a
  parallel `docs/` directory. Keep module usage documentation in its README.

## OCI scripts

- Assume `source ./scripts/setup.sh` has been run before any other script. Use the
  shared paths and OCI settings it exports rather than repeating environment
  initialization, compartment discovery, or Object Storage namespace discovery.
- Organize scripts into shell functions.
- Do not check whether tools are installed or available before using them.
- Do not use ETags or `--if-match` for OCI updates.
- Treat script-managed function tags as authoritative: generate the complete
  desired defined-tag map rather than fetching and merging existing tags.
- Read data source settings from `dsconfig.json`, using its data source IDs
  consistently. Prefer naming patterns derived from those IDs, with config
  overrides for exceptions such as MMM's existing `3M` resource names.
- Input buckets come only from `dsconfig.json`; do not add runtime or shell
  environment overrides for datasource input buckets.
- The shared Functions application name, `3MOpenDataProcessor`, may remain
  hardcoded in scripts; do not require an `applicationName` field in each
  data source entry.

## Retrieval and preprocessing assumptions

- Schedule retrieval at most once per day for each datasource. Consecutive
  retrievals of the same datasource never overlap; do not add a retrieval lock
  to handle that scenario. This limit does not apply to the hourly OSM refresh.
- Assume input events arrive in order and are not misrouted. Do not add
  ordering or routing defences solely for those scenarios. Ordered delivery
  does not by itself imply unique delivery or serialized preprocessing.
- Input versions are snapshots sampled at an arbitrary rate, not a sequential
  changelog. Skipping intermediate versions is acceptable; processing every
  version is not required.
- A retrieval that fails at any point is retried at its next scheduled
  occurrence. Rely on that recovery model rather than adding immediate retries.
- All input files of a multi-file datasource change together. Do not design
  additional coordination specifically for companion-only changes. Individual
  downloads and Object Storage writes can still fail partway through a run.
- Non-atomic multi-file transfers and temporarily mixed input versions are
  acceptable for this service. Do not introduce a completion marker, snapshot
  protocol or all-input transaction solely to prevent them.
- Preprocessing already detects unchanged output and avoids replacing its
  manifest. Repeating parsing is acceptable; no up-front processed-revision
  checkpoint is required.
- Do not add mechanisms for lost events, downstream failures, deployment drift
  or preprocessing overlaps as part of this trigger change. Preserve existing
  preprocessing validation and publication behavior.
