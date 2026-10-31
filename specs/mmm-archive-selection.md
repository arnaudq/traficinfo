# MMM historical archive selection

Decision recorded on October 31, 2026.

## Selected datasets

Historical MMM exports can lose records and populated measurements in subsequent
snapshots. Use archived CSV/JSON pairs in `ds3mbucket` to retain historical data.

| Dataset ID | Measurement years | CSV Object | Companion JSON Object |
| --- | --- | --- | --- |
| `mmm-2011-2021` | 2011–2021 | `MMM_MMM_Comptage_20221031.csv` | `MMM_MMM_Comptage_20221031.json` |
| `mmm-2022-2024` | 2022–2024 | `MMM_MMM_Comptage_20250915.csv` | `MMM_MMM_Comptage_20250915.json` |

These definitions are in [`dsconfig.json`](../dsconfig.json). Archive dates
identify snapshots; dataset year scopes refer to each record's `annee` value.
The 2022–2024 decision deliberately uses one archive pair for all three years.

## Evidence and method

The supplied `ComptageDiffProgram` comparison of the October 31, 2022 and March
12, 2024 snapshots, scoped to 2011–2021, reported 586 removed records versus 18
added records, and 370 deleted versus 10 added V85 values. Field deletions include
values lost through whole-record removal. This supports retaining the established
2022 archive for that period; it is not proof of an optimum across all snapshots.

For measurement years 2022–2026, the archive analysis inspected 42 of 167 dated
CSV archives available in the bucket, through the October 29, 2026 snapshot. It
sampled monthly endpoints and recursively narrowed intervals showing coverage
losses. A detected collapse required both a loss of at least 50 records/values
and a relative loss of at least 10%. Matching JSON Objects existed for all 167
archive candidates.

Coverage uses unique `(code_centrale, annee, code_canal)` keys and counts positive
populated values in five fields, equally weighted:

- `mja_jo`: traffic daily average.
- `v85ma_tcj`: V85 speed.
- `vtma_tcj`: mean speed.
- `hp_johvs_matin`: morning peak volume.
- `hp_johvs_soir`: evening peak volume.

Peak-hour text is assessed using its leading numeric volume. Duplicate rows do
not inflate coverage; conflicting duplicate measurements make an archive
ineligible for the affected year. Missing, zero and negative values do not count.
The objective is retained populated cells, not correctness, the magnitude of
measurements, or completeness of every CSV column. Changes between two populated
values are not counted as losses.

The scan found historical losses including 2022 V85 coverage falling from 800
to 2 between the April 30 and July 27, 2026 archives, and 2024 V85 coverage
falling from 600 to zero between the September 15 and December 2, 2025 archives.
These bounds refer to available snapshots, not necessarily the actual change day.

## Comparing the three shortlisted archives

| CSV archive | 2022 populated values | 2023 populated values | 2024 populated values | Total |
| --- | ---: | ---: | ---: | ---: |
| `MMM_MMM_Comptage_20250317.csv` | 4,889 | **5,950** | 3,903 | 14,742 |
| `MMM_MMM_Comptage_20250428.csv` | **4,955** | 5,923 | 3,880 | 14,758 |
| **`MMM_MMM_Comptage_20250915.csv`** | 4,946 | 5,930 | **3,907** | **14,783** |

September 15 maximizes the total among these three archives. Selecting a
different archive for each year would retain 14,812 populated values:
April 28 for 2022, March 17 for 2023, and September 15 for 2024.

| Measurement year | Per-year reference archive date | Populated cells lost using September 15 | Populated cells gained using September 15 | Net loss |
| --- | --- | ---: | ---: | ---: |
| 2022 | 2025-04-28 | 39 | 30 | 9 |
| 2023 | 2025-03-17 | 88 | 68 | 20 |
| 2024 | 2025-09-15 | 0 | 0 | 0 |
| **Total** | | **127** | **98** | **29** |

Using one file therefore reduces the populated-value total by approximately
**0.20%**. The 127 lost and 98 gained cells occur at different station/channel
and field combinations; the net loss of 29 does not mean only 29 specific cells
are missing.

For V85 specifically, September 15 loses four populated 2022 cells relative to
April 28. For 2023, it loses three and gains eight relative to March 17. The
single-file choice accepts these trade-offs to retain the most values overall
among the shortlisted candidates without merging sources.

## Observed speed coverage after historical dataset publication

The following figures were supplied from the `MMM speed coverage` logs before
and after implementing the yearly dataset publication and historical archive
split. They describe input records and rendered GeoJSON features for
`[VEHICULES, BICYCLES]`, rather than the archive-selection score above.

| Metric | Before | After | Change |
| --- | ---: | ---: | ---: |
| Input records | 12,715 | 12,239 | −476 |
| Input records with V85 | 5,877 (46.2%) | 7,164 (58.5%) | +1,287; +12.3 percentage points |
| Input records with mean speed | 5,877 (46.2%) | 7,164 (58.5%) | +1,287; +12.3 percentage points |
| GeoJSON features | 2,202 | 2,211 | +9 |
| Features with V85 | 1,544 (70.1%) | 1,996 (90.3%) | +452; +20.2 percentage points |
| Features with mean speed | 1,544 (70.1%) | 1,996 (90.3%) | +452; +20.2 percentage points |
| Features without V85 | 658 | 215 | −443 |

The output contains **29.3% more features with speed information**, despite
processing fewer input records. This is consistent with the intended benefit
of preserving historical measurements from richer archives: a larger or newer
CSV does not necessarily retain more usable historical speed data. Identical
V85 and mean-speed counts do not establish that the same features have both.

These aggregate logs do not identify the 476 fewer input records or establish
whether their omission matters. A record-level comparison is needed to explain
that difference. The comparison also does not isolate the effect of the archive
split from other changes between runs, and populated-value coverage does not
establish measurement correctness. Percentage-point changes use the rounded
percentages reported in the logs.

## Reproducibility and limits

The tool and usage instructions are in
[`scripts/mmm-archive-analysis/`](../scripts/mmm-archive-analysis/README.md).
Generated artifacts are kept together under `target/mmm-archive-analysis/`:
`report.md`, `report.json`, `coverage.csv`, `single-archive-comparison.json`,
the inventory, downloaded sources, and cached summaries. This directory is
ignored by Git; the decision and key figures are preserved here.

Sparse sampling can miss temporary losses, recoveries and intermediate maxima.
These are the best choices under the stated objective among inspected
candidates, not proven global maxima across all 167 archives. An exhaustive run
can reuse the existing cache. Companion existence was checked; station geometry
coverage still needs validation before publication.

## Configuration versus publication

Adding these datasets defines their inputs and year scopes; it does not import
or publish them. `activeDatasetId` remains `current`. The archived input `url`
fields retain the original publisher URLs as provenance, while `objectName`
identifies the stored archive to read; those URLs are not archive download URLs.

One-off historical dataset selection and legacy metadata handling are implemented
in preprocessing. After deploying the updated handler, import these datasets
without changing `activeDatasetId`:

```sh
./scripts/invoke.sh preprocess MMM mmm-2011-2021
./scripts/invoke.sh preprocess MMM mmm-2022-2024
```

See the [archive import procedure](../dsfunctions/README.md#importing-existing-archives-once)
for deployment, validation and GeoJSON generation. The current dataset is scoped to
2025–2026 so that recurring preprocessing does not replace the selected
historical years. If an existing manifest already records `current` with the
former 2021–2026 scope, preprocessing rejects shrinking that existing dataset;
a new dataset ID is required for the narrower scope before republishing.
The manifest stores one publication per
measurement year, so later processing of an overlapping year replaces that
year's reference.
