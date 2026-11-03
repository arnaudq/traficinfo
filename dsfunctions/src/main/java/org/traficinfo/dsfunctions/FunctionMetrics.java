package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared runtime wiring; SDK clients are opened only when an invocation has metrics to publish. */
final class FunctionMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(FunctionMetrics.class);

    private FunctionMetrics() {}

    static MetricsPublisher configured(RuntimeContext context) {
        String compartment = context.getConfigurationByKey("METRICS_COMPARTMENT_ID").orElse("");
        String namespace = context.getConfigurationByKey("METRICS_NAMESPACE").orElse("traficinfo");
        boolean principal = Boolean.parseBoolean(
                context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        return samples -> {
            if (samples.isEmpty()) return;
            if (compartment.isBlank()) {
                LOG.warn("Skipping metrics: METRICS_COMPARTMENT_ID is not configured");
                return;
            }
            try (var publisher = OciMetricsPublisher.create(principal, compartment, namespace)) {
                publisher.publish(samples);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not publish metrics to OCI Monitoring", failure);
            }
        };
    }
}
