# Repository instructions

## OCI scripts

- Assume `source ./setup.sh` has been run before any other script. Use the
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
- The shared Functions application name, `3MOpenDataProcessor`, may remain
  hardcoded in scripts; do not require an `applicationName` field in each
  data source entry.
