package org.traficinfo.dsfunctions;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** One observed value, independent of the monitoring backend. Counts are snapshots, not increments. */
public record MetricSample(String name, double value, String unit, Instant timestamp,
                           Map<String, String> dimensions) {
    public MetricSample {
        Objects.requireNonNull(name);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(timestamp);
        dimensions = Map.copyOf(dimensions);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Metric value must be finite");
    }
}
