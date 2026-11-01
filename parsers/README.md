# Parsers

## GeoJSON counter types

Each exported feature includes `type` (`VEHICULES` or `BICYCLES`), taken from
the same latest record used for the displayed count. The default `writeGeoJson` and `parseAndMerge` overloads
export vehicles only. Pass `Comptage.Type.BICYCLES` to the population-specific
`writeGeoJson` overload for bicycles. Each call writes one FeatureCollection.

The publishing functions write `<sourceId>_TraficInfo.geojson` for vehicles and
`<sourceId>_TraficInfo_BICYCLES.geojson` for bicycles. uMap can filter on `type`.
The map templates use fixed relative icon URLs (`/uploads/pictogram/car.svg`
and `/uploads/pictogram/bicycle.svg`) per population.

The model also supports `PIETONS_VELOS` (mixed pedestrian and bicycle counts)
and `UNKNOWN`; these remain excluded by the export's existing type filter.

## Comparing MMM snapshots

Run the test-source main class `org.traficinfo.parsers.ComptageDiffProgram`
with these arguments:

```text
before.csv after.csv [2020,2022,2024 [output.json]]
```

The optional third argument selects the exact years to compare on both sides;
omitting it uses the configured default years (currently 2011–2021). Spaces around years and repeated years are
accepted. The optional output path is the fourth argument and defaults to
`~/Downloads/diff.json`. With no arguments, the program uses its configured
snapshot filenames in Downloads.

Records match by central code, year and MMM channel code. Duplicate keys log a
warning and retain the first record. `ComptageDiffer.IGNORED_FIELDS` currently
contains only `altStreetName`; changes to that field alone produce no diff.
The full source objects retain the field when other changes are reported.

The printed report contains overall and yearly counts for records and for
`meanSpeed`, `moyenneHeureDePointeMatin`, `moyenneHeureDePointeSoir`,
`moyenneJourAnnuelle`, and `v85Speed`. Field additions, modifications and deletions
are counted separately, including values supplied by newly added records and
lost through deleted records. Nulls, blank text and numeric zero represent missing
values; changing between two present values counts as a modification.

The JSON remains grouped by year. Each group contains record counts,
`fieldSummary` with added/modified/deleted counts for the five fields, and a
`changes` array. `changedFields` is included only when both source records exist,
with `+` for added values and `-` for deleted values.

Run the module tests with `mvn -o -pl parsers test`.

## Auditing missing count measurements

Run the test-source main class `org.traficinfo.parsers.ComptageMeasurementProgram`
with `datasource file` arguments, for example `MRN ~/Downloads/MRN_Comptage.csv`.
With no arguments it uses that MRN file. Supported source IDs are `MMM`, `DPT34`,
`DPT30`, `MRN` and `NIM` (enum names such as `M3MV2` also work).
Compile with `mvn -o -pl parsers test-compile`, then run the main class with the
module's test runtime classpath, for example through your IDE.

The report groups parsed records by `VEHICULES`, `BICYCLES`, `PIETONS_VELOS` and
`UNKNOWN`, with totals, positive-count records, missing-count records and their
percentage. A significant count means a strictly positive shared daily average
or morning/evening peak volume. MRN also checks all source TV/UVP TMJA, TMJO,
HPM and HPS volumes. Blanks, zero and negative volumes do not qualify; speeds,
percentages, evolution indicators and hour labels alone do not qualify.
`No annual avg` separately counts records without a positive shared annual daily
average (MRN's `TV_TMJA`), even when another volume exists. Other sources are
checked through the shared model's daily average and peaks only.

The audit includes every parsed record across all years and types, without
merging repeated sites or applying map filters. Rows skipped by the source
parser are excluded; parser warnings remain visible in the console.
