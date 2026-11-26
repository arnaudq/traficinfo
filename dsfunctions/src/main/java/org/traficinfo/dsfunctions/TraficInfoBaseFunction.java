package org.traficinfo.dsfunctions;

import org.traficinfo.dsfunctions.oci.ObjectStoreClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.RuntimeContext;
import com.fnproject.fn.api.FnConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared configuration and request boundary for all deployed functions.
 * Subclasses implement {@link #configure(RuntimeContext)} and
 * {@link #processRequest(String, Invocation)}; the final entry points ensure
 * handled application failures are reported without failing the OCI invocation.
 * Metric or error-response failures preserve the original exception, allowing the
 * built-in OCI alarm to take over (and permitting caller retries in that case).
 * Configuration failures and JVM errors remain runtime failures.
 */
public abstract class TraficInfoBaseFunction {
    private static final Logger LOG = LoggerFactory.getLogger(TraficInfoBaseFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String defaultDatasourceId;
    private boolean configured;
    private String namespace;
    private boolean useResourcePrincipal;
    private String metricsCompartmentId;
    private String metricsNamespace;
    private MetricsPublisher metrics = samples -> {
        throw new IllegalStateException("Metrics have not been configured");
    };
    private Map<String, String> dimensions = Map.of("applicationId", "UNKNOWN", "resourceId", "UNKNOWN",
            "resourceDisplayName", "UNKNOWN");

    /** @param defaultDatasourceId datasource label used until processing selects a specific source */
    protected TraficInfoBaseFunction(String defaultDatasourceId) {
        this.defaultDatasourceId = defaultDatasourceId;
    }

    /** Initializes shared namespace, authentication and monitoring before function-specific configuration. */
    @FnConfiguration
    public final void config(RuntimeContext context) {
        configured = false;
        namespace = context.getConfigurationByKey("NAMESPACE").orElse("");
        useResourcePrincipal = Boolean.parseBoolean(
                context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        metricsCompartmentId = context.getConfigurationByKey("METRICS_COMPARTMENT_ID").orElse("");
        metricsNamespace = context.getConfigurationByKey("METRICS_NAMESPACE").orElse("traficinfo");
        metrics = createMetricsPublisher();
        dimensions = Map.of("applicationId", context.getAppID(), "resourceId", context.getFunctionID(),
                "resourceDisplayName", context.getFunctionName());
        configure(context);
        configured = true;
    }

    /** @return the Object Storage namespace (empty if unset), or null before configuration */
    protected final String namespace() {
        return namespace;
    }

    /** @return whether OCI clients authenticate with a resource principal; defaults to true */
    protected final boolean useResourcePrincipal() {
        return useResourcePrincipal;
    }

    /**
     * Creates a storage client for the selected bucket using this function's
     * configured namespace and authentication mode.
     * The caller owns the client and must close it when its work is complete.
     * Subclasses may override this factory to supply a different client implementation.
     */
    protected ObjectStore createObjectStoreClient(String bucket) {
        return new ObjectStoreClient(useResourcePrincipal(), namespace(), bucket);
    }

    /**
     * Supplies the common metrics backend. Clients are created only for nonempty batches
     * and closed after publication. Missing configuration and rejected samples must
     * throw, so failure reporting can fall back to the original application exception.
     */
    protected MetricsPublisher createMetricsPublisher() {
        String compartment = metricsCompartmentId;
        String metricNamespace = metricsNamespace;
        boolean principal = useResourcePrincipal();
        return samples -> {
            if (samples.isEmpty()) return;
            if (compartment.isBlank()) {
                throw new IllegalStateException("METRICS_COMPARTMENT_ID is not configured");
            }
            try (var publisher = OciMetricsPublisher.create(principal, compartment, metricNamespace)) {
                publisher.publish(samples);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not publish metrics to OCI Monitoring", failure);
            }
        };
    }

    /**
     * Publishes ordinary processing observations without failing successful work.
     * Failure events bypass this best-effort method and use the backend directly.
     */
    protected void publishMetrics(List<MetricSample> samples) {
        if (samples.isEmpty()) return;
        try {
            metrics.publish(samples);
        } catch (RuntimeException failure) {
            LOG.warn("Could not publish processing metrics", failure);
        }
    }

    /** Configures function-specific resources. Exceptions here remain OCI runtime failures. */
    protected abstract void configure(RuntimeContext context);

    /** Executes application processing inside the shared exception-reporting boundary. */
    protected abstract String processRequest(String input, Invocation invocation);

    /**
     * Returns the processing result, or an error response after publishing a failure event.
     * Rethrows the original application exception if reporting fails.
     */
    public final String handleRequest(String input) {
        if (!configured) throw new IllegalStateException("Function has not been configured");
        // State belongs to this invocation, never to a warm function instance.
        Invocation invocation = new Invocation(defaultDatasourceId);
        try {
            return processRequest(input, invocation);
        } catch (RuntimeException original) {
            LOG.error("Function {} failed for datasource {}", dimensions.get("resourceDisplayName"),
                    invocation.datasourceId, original);
            try {
                var error = JSON.createObjectNode();
                error.put("success", false);
                var details = error.putObject("error");
                details.put("type", original.getClass().getName());
                details.put("message", original.getMessage());
                var causes = details.putArray("causes");
                for (Throwable cause = original.getCause(); cause != null; cause = cause.getCause()) {
                    var entry = causes.addObject();
                    entry.put("type", cause.getClass().getName());
                    entry.put("message", cause.getMessage());
                }
                String response = JSON.writeValueAsString(error);
                var sampleDimensions = new java.util.HashMap<>(dimensions);
                sampleDimensions.put("datasourceId", invocation.datasourceId);
                metrics.publish(List.of(new MetricSample(MetricName.FUNCTION_FAILURES.metricName(),
                        1, "count", Instant.now(), sampleDimensions)));
                return response;
            } catch (Exception reportingFailure) {
                LOG.error("Could not report application failure; rethrowing original exception", reportingFailure);
                throw original;
            }
        }
    }

    /** Datasource selected during parsing, retained after logging scopes and resources close. */
    protected static final class Invocation {
        private String datasourceId;

        private Invocation(String datasourceId) {
            this.datasourceId = datasourceId;
        }

        /** Sets the datasource identified during processing for any subsequent failure event. */
        public void datasource(String id) {
            datasourceId = id;
        }
    }
}
