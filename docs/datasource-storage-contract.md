# Shared datasource storage contract, version 1

This contract applies to every datasource ID in `dsconfig.json`, including MMM,
DPT30, DPT34, MRN and the future NIM. The Java models live in
`org.traficinfo.parsers.storage`, so both parsers and functions can use them.
This change defines data formats only. Existing functions, configuration,
events and storage objects continue to operate as before.

## Bucket and object names

All references are exact object names relative to the datasource's configured
input bucket. Prefixes resemble directories but are part of the object name.
Use the datasource ID for names, including MMM despite its legacy function and
bucket names. Import, review and revision IDs are opaque, unique path components;
`import-0003`, `review-0003` and `r0003` below are illustrative. Never reuse an ID
for different content, and never interpret revision numbers as years.

| Object name | Purpose | Mutable? |
| --- | --- | --- |
| `<id>_Manifest.json` | Current accepted publication | Yes, by the publisher |
| `manifests/<revision>.json` | Exact copy of a published manifest | No |
| `raw/<importId>/<inputId>.<extension>` | Retrieved source payload | No |
| `imports/<importId>.json` | Completed retrieval provenance | No |
| `candidates/<importId>/<id>_Comptage_<year>.json` | Proposed normalized yearly array | No |
| `reviews/<reviewId>/proposal.json` | Review scope and candidate references | No |
| `reviews/<reviewId>/diff.json` | Record-level evidence for the proposal | No |
| `reviews/<reviewId>/decision.json` | Explicit approval or rejection | No |
| `<id>_Reviews.json` | Discoverable review status index | Yes |
| `accepted/<revision>/<id>_Comptage_<year>.json` | Accepted normalized yearly array | No |

Only publication of the current manifest should eventually trigger GeoJSON.
Pending/rejected reviews, candidate files, archives and the review index must
not trigger publication. Unchanged yearly objects retain their existing paths
when another year changes. A revision does not need to copy every year.

JSON is UTF-8, without a BOM. `sha256`, `candidateSha256` and `proposalSha256`
are 64 lowercase hexadecimal characters computed over the **exact uncompressed
payload bytes**, including whitespace and the final newline if present. Storage
may use `Content-Encoding: gzip` without changing the checksum. This is byte
identity, not semantic JSON equality. Consumers must decode content encoding
before verification. Semantic record comparison is a separate operation.
Timestamps are ISO 8601 UTC strings ending in `Z`; Java stores them as strings
so the existing Jackson dependency needs no additional date module.

## Manifest (`DataManifest`)

`manifestVersion` and `comptageSchemaVersion` are both 1. Missing or unsupported
versions are rejected. Breaking envelope changes require a new envelope version;
breaking normalized record changes require a new `comptageSchemaVersion`.
A future migration must explicitly add support before publishing such versions.
Models currently use Jackson's default strict handling of unknown properties.

- `dataSourceId`, `revision`, `publishedAt`: accepted publication identity.
- `activeResourceId`: the single upstream resource group accepted for monitoring.
- `resources`: resource identities retained for active and inactive imports.
  Each has a `datasetUrl`, explicit `years` scope and named `inputs`.
- Each input has its retrieval `url`, `format` (`CSV` or `GEOJSON` in the
  examples), and `acceptedSchema`. This supports MMM's counts and companion
  geometry within one monitored resource group. Acquisition remains source-specific.
- `acceptedSchema` contains a positive `revision` local to that resource/input
  and the ordered, parsed CSV `headers`. It is null for non-CSV inputs.
- `acceptedYears`: year keys mapped to exact accepted object references,
  checksums, record counts, resource/import/parser provenance and approval
  references. Year keys are decimal strings in JSON and integers in Java.

The manifest is authoritative for GeoJSON input. Readers capture it once and
follow its exact references instead of listing objects with a wildcard.
References must stay within the datasource bucket. All accepted objects must
exist and match their checksums before the current manifest is replaced.

Switching resources preserves years accepted from earlier resources. Compare
only years explicitly covered by the completed import. An absent inactive year
is never a proposed deletion. Missing expected years/inputs indicate an
incomplete import and must be checked before proposing removals.

Headers are parsed CSV column names, not a literal header line. Preserve order
for evidence; validation can tolerate reordering when a parser uses column
names. Missing required names, duplicate names and renames require intervention.
Additional columns follow the parser's documented policy. Observed candidate
headers never automatically replace accepted headers. A resource switch requires
explicit acceptance even when its headers match. Import records retain exact
observed headers, allowing historical schema reconstruction after a baseline
changes. A separate `<id>_Headers.csv` is unnecessary and is not authoritative.

## Normalized yearly array (`NormalizedComptage`)

Each file is a JSON array with records for exactly its named year. The manifest
carries the array's schema version. Fields follow the current `Comptage` model:

- Identity: `codeCentrale`, `annee`, `discriminator`.
- Coverage: `directionType` and `type`, using the existing `Comptage` enums.
- Source location: `displayName`, `cityName`, `roadReference`, GeoJSON `geometry`.
- Measurements: `moyenneJourAnnuelle`, `moyenneHeureDePointeMatin`,
  `moyenneHeureDePointeSoir`, `v85Speed`, `meanSpeed`.

`discriminator` is a stable string defined by each source adapter. Normally it
is the direction enum name; MMM can use its channel code. A source must include
any additional distinction needed to prevent collisions, such as traffic type.
Identity must be unique within an array; preprocessing will reject duplicates.
An identity change appears as removal plus addition.

Unknown numeric measurements are null; a measured zero remains zero. Adapters
must explicitly translate source-specific missing-value conventions. Do not
infer whether a legacy parser's zero is a valid observation or a sentinel.
Peak-hour strings retain existing parser conventions. Speeds use km/h.
OSM-derived `osmWayId` and `maximumSpeed` are excluded so external enrichment
cannot cause historical source diffs. Only source geometry belongs here.
These storage records are distinct from the mutable `Comptage` interface;
conversion, deterministic serialization and source-specific aggregation arrive
in subsequent steps. Hourly coverage and campaign-to-year semantics must be
settled in each source adapter before importing data.

## Import provenance (`ImportRecord`)

`importVersion` is 1. The record identifies the datasource, import, resource,
retrieval time, parser version and explicit year scope. Its named `inputs`
record the actual source URLs, raw object references, payload checksums,
optional HTTP validators/last-modified strings and observed CSV headers.
Validator and modification metadata are evidence, not acceptance criteria.
Companion files each have independent provenance. Publish a completed import
record only after all required inputs have been acquired successfully.

Fixtures use synthetic URLs and CSV headers; they do not document real NIM
column names. Raw payloads for the import examples are not bundled.

## Reviews (`ReviewProposal`, `ReviewDiff`, `ReviewDecision`, `ReviewIndex`)

Each envelope has its own version field, currently 1. Review enum values use
uppercase names in JSON.

A proposal records its review/import/datasource identity, creation time, reasons
(`INITIAL_IMPORT`, `RESOURCE_SWITCH`, `HISTORICAL_DATA_CHANGED`, `SCHEMA_CHANGED`),
and `baseManifestRevision`. The baseline is null only for the first publication.
Its `changes` map contains affected years, baseline object (null for a new year),
candidate reference/checksum/count, diff reference and addition/modification/
removal totals. `schemaChanges` is keyed by input ID within the import's resource,
with accepted headers (null for a new input) and observed headers. A schema-only
proposal may have an empty yearly changes map. The proposal checksum binds all
these fields together, including candidate checksums.

Diffs group changes by year and retain `codeCentrale`, `discriminator`,
`changedFields`, and complete normalized `before`/`after` values. Null before
means addition; null after means removal; both cannot be null. `changedFields`
contains sorted plain property names for modifications and is empty for
additions/removals. The existing `ComptageDiffer` can supply comparison evidence;
its display-oriented missing-value labels are not a patch application format.
Stored full values retain distinctions such as null versus measured zero.

A decision records `APPROVED` or `REJECTED`, reviewer identity, UTC time, comment,
the reviewed baseline and checksum of the exact immutable proposal. A reviewer
identity must come from the eventual operator interface, not a guessed value.
A changed candidate requires a new proposal. A stale baseline requires a new
comparison and review. Version 1 decisions cover an entire proposal; partial
approval requires splitting it into separate proposals first.

The mutable review index is a discovery aid with these statuses:

| Status | Meaning |
| --- | --- |
| `PENDING` | Proposal awaits a decision |
| `REJECTED` | Rejection decision exists |
| `SUPERSEDED` | Replaced by a new proposal; cannot be promoted |
| `APPROVED` | Approval exists; not yet published |
| `PUBLISHED` | A published manifest references this approval |

Decision and published-revision references are null until applicable. Durable
proposal/decision objects and manifests take precedence over index status.
An approved-but-unpublished operation can be retried without another decision.
Repeated retrieval of the same candidate against the same baseline should reuse
its pending proposal. A later decision must never overwrite an earlier one.

## Publication protocol for later implementation

1. Acquire all required inputs and write immutable provenance/candidates.
2. Validate completeness/schema/record identity; produce proposal and evidence.
3. Accept an explicit operator decision, including the initial baseline.
4. Recheck the baseline and proposal checksum. Serialize publishers per datasource;
   this design does not rely on OCI ETags or `--if-match` for updates.
5. Copy approved candidates to new immutable accepted paths; retain unchanged
   references. Update accepted headers/resource identity only when approved.
6. Write the immutable manifest archive, then replace `<id>_Manifest.json` last.
7. Update the review index to `PUBLISHED`. Interrupted publication is recoverable
   from the decision and manifest; the index alone cannot prove publication.

Publication and downstream event handling must be idempotent. These models
reject unsupported versions but do not yet implement reference-integrity checks,
checksum verification, approval enforcement, concurrency control or storage I/O.
Those responsibilities belong to the later preprocessing/promotion steps.
Keeping a manifest archive is necessary but not sufficient for rollback:
republishing earlier references must be a new audited revision.

## Examples and verification

Executable JSON examples are in `parsers/src/test/resources/storage`. The NIM
manifest retains 2022 while monitoring 2023–2024; a separate MMM manifest shows
companion inputs. The review fixtures show approval of a 2024 correction before
publication, so the current manifest still points to its previous accepted file.
Fixture checksums for normalized files and the proposal are real and are checked
by `StorageContractTest`. Example records, raw-input hashes and reviewer identities
are synthetic. No remote resources are retrieved by these tests.

Run `mvn -o -pl parsers test` for contract compatibility and parser regression tests.
