package org.traficinfo.dsfunctions;

import java.util.List;

/** Backend boundary for publishing observations together. Implementations report failures to the caller. */
@FunctionalInterface
public interface MetricsPublisher {
    void publish(List<MetricSample> samples);
}
