package org.traficinfo.parsers.history;

import org.geojson.GeoJsonObject;
import org.traficinfo.parsers.Comptage;

import java.util.List;
import java.util.Map;

/**
 * Complete public history for one datasource, serialized as {@code history/<dataSourceId>.json}.
 *
 * <p>The containment hierarchy is {@code CounterHistory -> Counter -> Campaign -> Count}.
 * Counters hold site metadata; campaigns group measurements from the same exercise;
 * counts describe individual populations and directional coverage. Dataset attribution
 * is shared at this root and referenced by each campaign's dataset ID.
 * This model is independent of the source-specific {@link Comptage} storage classes.
 *
 * @param schemaVersion version of this public JSON format
 * @param dataSourceId configured datasource ID, which scopes counter identifiers
 * @param sourceRevision private manifest revision from which this history was built
 * @param publishedAt export timestamp as an ISO 8601 UTC instant
 * @param datasets attribution keyed by dataset ID
 * @param counters complete retained histories keyed by exact string {@code codeCentrale}
 */
public record CounterHistory(int schemaVersion, String dataSourceId, String sourceRevision,
                             String publishedAt, Map<String, Dataset> datasets,
                             Map<String, Counter> counters) {
    /**
     * Shared attribution for a source dataset, referenced by {@link Campaign#datasetId()}.
     *
     * @param url publisher's dataset information URL
     */
    public record Dataset(String url) {}

    /**
     * One counting site's identity, latest known metadata and complete campaign history.
     * Metadata is selected field by field from the latest nonblank/available stored values;
     * it does not describe the site's historical state in every campaign.
     *
     * @param codeCentrale datasource-scoped string ID, identical to the enclosing map key
     * @param displayName site display name, or null when unavailable
     * @param cityName municipality name, or null when unavailable
     * @param roadReference official road reference, or null when unavailable
     * @param geometry latest known GeoJSON geometry using longitude/latitude coordinates, or null
     * @param osmWayId latest known positive OSM way ID as a decimal string, or null
     * @param campaigns campaigns ordered by reporting year, measurement period and campaign ID
     */
    public record Counter(String codeCentrale, String displayName, String cityName,
                          String roadReference, GeoJsonObject geometry, String osmWayId,
                          List<Campaign> campaigns) {}

    /**
     * One measurement exercise or annual aggregate for a counter.
     * A year can contain multiple campaigns. Directional and supplied combined counts
     * from the same campaign belong together; their values must not be summed together.
     *
     * @param id deterministic campaign ID, unique within its counter
     * @param year source reporting year, not a claim of full-year measurement coverage
     * @param startDate known measurement start date as {@code YYYY-MM-DD}, or null
     * @param endDate known measurement end date as {@code YYYY-MM-DD}, or null
     * @param label source campaign description, or null
     * @param datasetId key of the campaign's attribution in {@link CounterHistory#datasets()}
     * @param countingMode permanent or temporary operation during this campaign, or UNKNOWN
     * @param counts canonical measurements ordered by count ID
     */
    public record Campaign(String id, int year, String startDate, String endDate,
                           String label, String datasetId, CountingMode countingMode, List<Count> counts) {}

    /** Counting operation or explicit publisher exclusion, independent of sampling completeness. */
    public enum CountingMode {
        /** Source explicitly identifies continuous or permanent counting. */
        PERMANENT,
        /** Source identifies a temporary, occasional or rotating counting exercise. */
        TEMPORARY,
        /** Retained source information does not establish the counting mode. */
        UNKNOWN,
        /** Publisher marks the record as unusable; excluded before assembling public campaigns. */
        DISREGARDED
    }

    /** Meaning of a count's daily-average denominator, as established by its source. */
    public enum AverageBasis {
        /** Publisher-supplied annual daily average. */
        ANNUAL,
        /** Daily average associated with a measurement campaign. */
        CAMPAIGN,
        /** Derived mean over observed days with all required hourly observations. */
        OBSERVED_COMPLETE_DAYS,
        /** Available source information does not establish the averaging basis. */
        UNKNOWN
    }

    /**
     * Source-defined channel identifying an individual direction of measurement.
     * Channel identifiers and labels are not inferred compass bearings.
     *
     * @param id source channel or direction ID, preserved as a string
     * @param label source direction description, or null when unavailable
     */
    public record Direction(String id, String label) {}

    /**
     * Sampling coverage supporting one count, when retained by the source model.
     * Date bounds describe observations, not assumed year-round counter operation.
     *
     * @param firstDate first represented date as {@code YYYY-MM-DD}, or null
     * @param lastDate last represented date as {@code YYYY-MM-DD}, or null
     * @param observedDays number of represented dates, including incomplete days, or null
     * @param completeDays number of days with all required valid hourly slots, or null
     * @param observedHours number of usable, unambiguous hourly slots, or null
     * @param conflictingHours number of ambiguous hourly slots excluded from aggregation, or null
     */
    public record Coverage(String firstDate, String lastDate, Integer observedDays,
                           Integer completeDays, Integer observedHours, Integer conflictingHours) {}

    /**
     * Canonical measurements for one population and directional scope within a campaign.
     * Numeric measurements are nullable integers; source decimals are rounded to the nearest
     * integer with half values rounded up. Peak text retains the available source representation.
     * Legacy primitive fields whose blank and zero values are indistinguishable are
     * normalized by {@link Canonicalizer} before constructing this record.
     * A supplied combined count is retained independently of its directional components.
     *
     * @param id deterministic ID distinguishing channel/coverage and population within the campaign
     * @param population measured traffic population, including UNKNOWN when not established
     * @param directionType unidirectional, bidirectional combined, or unknown coverage
     * @param direction individual source direction, or null for combined/unidentified directions
     * @param dailyAverage passages per day, or null
     * @param dailyAverageBasis source-specific interpretation of the daily average
     * @param morningPeakAverage passages per hour during the morning peak, or null
     * @param morningPeakText available source representation of the morning peak, or null
     * @param eveningPeakAverage passages per hour during the evening peak, or null
     * @param eveningPeakText available source representation of the evening peak, or null
     * @param v85Speed 85th-percentile measured speed in km/h, or null
     * @param meanSpeed mean measured speed in km/h, or null
     * @param coverage observation coverage, or null when unavailable
     */
    public record Count(String id, Comptage.Type population, Comptage.DirectionType directionType,
                        Direction direction, Integer dailyAverage, AverageBasis dailyAverageBasis,
                        Integer morningPeakAverage, String morningPeakText,
                        Integer eveningPeakAverage, String eveningPeakText,
                        Integer v85Speed, Integer meanSpeed, Coverage coverage) {}
}
