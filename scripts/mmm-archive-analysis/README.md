# MMM archive analysis

Find candidate CSV/JSON archive pairs for measurement years 2022 onward without
initially reading every daily upload. This is a read-only bucket analysis; it does
not change dataset configuration, raw Objects, manifests, or published data.

After the shared environment has been initialized:

```sh
source ./scripts/setup.sh
bash scripts/mmm-archive-analysis/scan.sh
```

The tool reads MMM's bucket and Object naming from `dsconfig.json`, and the OCI
namespace/configuration exported by setup. It lists dated CSV archives and checks
for companion JSON Objects with the same date. It samples the first and last
archives and the last available archive in each calendar month, then recursively
bisects intervals where a measurement year's coverage drops. Coverage tracks
unique `(code_centrale, code_canal)` records for each year and positive populated
values for `mja_jo`, `v85ma_tcj`, `vtma_tcj`, `hp_johvs_matin`, and
`hp_johvs_soir`. Peak-hour intervals are parsed for their leading numeric value.
Pre-2022 records are excluded. Duplicate keys retain the first row for coverage.
Repeated identical measurements do not inflate counts; conflicting measurements
make that archive ineligible for recommendation for the affected year. Malformed
rows with a known year outside the requested scope are skipped and reported as
warnings. Malformed rows within scope or with an unknown year exclude the file;
a loss boundary spanning an excluded file remains uncertain across that gap.

A collapse requires **both** at least 50 lost records/values and a relative loss
of at least 10%. Override with `--absolute-drop` and `--relative-drop`.
When the decline is gradual, the report retains suspect intervals even if no
individual daily step crosses the thresholds. Sparse sampling cannot guarantee
finding brief collapses followed by recoveries, nor an optimum between probes.
Use `--exhaustive` to read every archive, reusing previous downloads and summaries.

## Selection

For each measurement year, recommend an inspected archive with a matching JSON
and no conflicting duplicate station/channel keys. Maximize the total populated values across
the five fields, with equal weight for each populated cell; break ties by record
count and then newest archive date. Recommendations identify exact CSV and JSON
Object names and combine consecutive years selecting the same pair.

This maximizes availability under that stated objective, not correctness. A
candidate with more populated values can have less V85 coverage. The JSON report
includes per-metric maxima and alternatives whose metric counts are not dominated
by another candidate, plus counts of station/channel keys or populated fields
seen elsewhere but missing from the chosen file. No records or fields are merged.
Companion existence is checked; geometry identifier coverage and historical value
corrections still need validation before import. Date-suffixed archives are
included; the mutable current CSV and locally renamed copies are excluded.

## Files

All generated files default to `target/mmm-archive-analysis/`:

- `inventory.json`, `archives.json`: available bucket Objects and dated candidates.
- `downloads/`: sampled source bytes (possibly gzip despite the `.csv` suffix).
- `summaries/`: cached per-year keyed values, keyed by source identity, version,
  year scope, and inventory metadata (size, MD5 and modification time; no ETags).
- `report.md`: selected archive pairs and detected collapse boundaries.
- `report.json`: recommendations, trade-offs, lost/gained keys and gradual declines.
- `coverage.csv`: coverage counts for every inspected archive and year.

Override the directory with `--output-dir`. A local scan can run without OCI:

```sh
python3 scripts/mmm-archive-analysis/scan.py --local-dir /path/to/archives \
  --output-dir target/mmm-archive-analysis/local
```

Only files in that local directory are read; originals are not changed. Cache
identity uses local file size and nanosecond modification time. Cache files hold
measurement data, so keep them with the other analysis artifacts.

Run algorithm tests (fixtures stay inside the dedicated output directory):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/mmm-archive-analysis/tests
```
