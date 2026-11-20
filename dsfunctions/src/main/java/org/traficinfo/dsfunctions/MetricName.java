package org.traficinfo.dsfunctions;

/**
 * Application metric names published in the configured TraficInfo namespace.
 * Wire names are kept explicit because monitoring queries and alarms use them.
 * All metrics have unit {@code count}; only {@link #FUNCTION_FAILURES} counts
 * events. The other metrics are snapshots and should normally use {@code max()},
 * rather than {@code sum()}, when querying repeated publications.
 */
public enum MetricName {
    /**
     * Final published GeoJSON feature count, after filtering and grouping.
     * Dimensions: {@code datasourceId}, {@code population}. Includes zero counts.
     */
    GEO_JSON_FEATURES("GeoJsonFeatures"),
    /**
     * Annual input records for an exported population before record filtering.
     * Dimensions: {@code datasourceId}, {@code population}, {@code year}.
     */
    GEO_JSON_RECORDS_BEFORE_FILTERING("GeoJsonRecordsBeforeFiltering"),
    /**
     * Annual records passing rendering filters, before geometry selection and grouping.
     * Dimensions: {@code datasourceId}, {@code population}, {@code year}.
     */
    GEO_JSON_RECORDS_AFTER_FILTERING("GeoJsonRecordsAfterFiltering"),
    /**
     * All cached counter-to-way mappings in each status after a successful refresh,
     * including mappings not attempted during that invocation.
     * Dimensions: {@code datasourceId}, {@code status}.
     */
    OSM_WAY_MAPPINGS("OSMWayMappings"),
    /**
     * One event per invocation whose handler catches an application exception.
     * Dimensions: {@code applicationId}, {@code resourceId},
     * {@code resourceDisplayName}, {@code datasourceId}. Use {@code sum()} to count
     * failures. Successful processing and recoverable per-record errors emit no event.
     * If publishing this event fails, the original exception is rethrown so OCI's
     * built-in invocation error monitoring remains available.
     */
    FUNCTION_FAILURES("FunctionFailures");

    private final String metricName;

    MetricName(String metricName) {
        this.metricName = metricName;
    }

    /** @return the exact metric name used by the monitoring backend and alarm queries */
    public String metricName() {
        return metricName;
    }
}
