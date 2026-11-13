package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Hourly bounded reverse lookup of unresolved counter-to-way mappings in Object Storage. */
public class OSMInfoFunction {
    private static final Logger LOG = LoggerFactory.getLogger(OSMInfoFunction.class);
    private static final int MAX_LOOP_DURATION_SECONDS = 240;
    // At four reverse requests per minute (15-second spacing in OpenStreetMapClient),
    // 16 lookups use about the default 240-second loop budget across all data sources.
    private static final int MAX_WAY_MAPPINGS_PER_RUN = 16;
    private static final int MAX_SCHEDULED_LOOP_DURATION_SECONDS = 45 * 60;
    // With the same 15-second spacing, 180 lookups use about the 45-minute scheduled budget.
    private static final int MAX_SCHEDULED_WAY_MAPPINGS_PER_RUN = 180;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private int maxLoopSeconds = MAX_LOOP_DURATION_SECONDS;
    private int maxWayMappings = MAX_WAY_MAPPINGS_PER_RUN;
    private int maxScheduledLoopSeconds = MAX_SCHEDULED_LOOP_DURATION_SECONDS;
    private int maxScheduledWayMappings = MAX_SCHEDULED_WAY_MAPPINGS_PER_RUN;
    private ObjectStoreClient osmCacheOSClient;
    private OpenStreetMapClient osmClient;
    private final LongSupplier nanoTime;
    private MetricsPublisher metrics;
    private boolean reviewRoadMismatches;

    public OSMInfoFunction() {
        this(null);
    }

    OSMInfoFunction(ObjectStoreClient osmCacheOSClient) {
        this(osmCacheOSClient, new OpenStreetMapClient(), System::nanoTime);
    }

    OSMInfoFunction(ObjectStoreClient osmCacheOSClient, OpenStreetMapClient osmClient, LongSupplier nanoTime) {
        this(osmCacheOSClient, osmClient, nanoTime, samples -> {});
    }

    OSMInfoFunction(ObjectStoreClient osmCacheOSClient, OpenStreetMapClient osmClient, LongSupplier nanoTime,
                    MetricsPublisher metrics) {
        this.osmCacheOSClient = osmCacheOSClient;
        this.osmClient = osmClient;
        this.nanoTime = nanoTime;
        this.metrics = Objects.requireNonNull(metrics);
    }

    @FnConfiguration
    public void config(RuntimeContext ctx) {
        configureLimits(ctx);
        configureRoadComparison(ctx);
        metrics = FunctionMetrics.configured(ctx);
        String namespace = ctx.getConfigurationByKey("NAMESPACE").orElse("");
        String bucket = ctx.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        osmCacheOSClient = new ObjectStoreClient(useResourcePrincipal, namespace, bucket);
    }

    void configureRoadComparison(RuntimeContext ctx) {
        String value = ctx.getConfigurationByKey("REVIEW_ROAD_MISMATCHES").orElse("false");
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("REVIEW_ROAD_MISMATCHES must be true or false");
        }
        reviewRoadMismatches = Boolean.parseBoolean(value);
    }

    void configureLimits(RuntimeContext ctx) {
        maxLoopSeconds = configuredLimit(ctx, "MAX_LOOP_DURATION_SECONDS", MAX_LOOP_DURATION_SECONDS);
        maxWayMappings = configuredLimit(ctx, "MAX_WAY_MAPPINGS_PER_RUN", MAX_WAY_MAPPINGS_PER_RUN);
        maxScheduledLoopSeconds = configuredLimit(ctx, "MAX_SCHEDULED_LOOP_DURATION_SECONDS",
                MAX_SCHEDULED_LOOP_DURATION_SECONDS);
        maxScheduledWayMappings = configuredLimit(ctx, "MAX_SCHEDULED_WAY_MAPPINGS_PER_RUN",
                MAX_SCHEDULED_WAY_MAPPINGS_PER_RUN);
    }

    private static int configuredLimit(RuntimeContext ctx, String key, int defaultValue) {
        String value = ctx.getConfigurationByKey(key).orElse(Integer.toString(defaultValue));
        try {
            int limit = Integer.parseInt(value);
            if (limit > 0) {
                return limit;
            }
        } catch (NumberFormatException invalid) {
            // Report the configuration key for both invalid numbers and out-of-range values.
        }
        throw new IllegalArgumentException(key + " must be a positive integer");
    }

    public String handleRequest(String input) {
        try (var logging = LoggingContext.forSource("osminfo", null)) {
            boolean scheduled = isScheduled(input);
            // Keep limits local: a warm function instance may next receive a manual invocation.
            int loopSeconds = scheduled ? maxScheduledLoopSeconds : maxLoopSeconds;
            int wayMappings = scheduled ? maxScheduledWayMappings : maxWayMappings;
            LOG.info("OSM {} run: {} second loop budget, {} reverse lookups",
                    scheduled ? "scheduled" : "manual", loopSeconds, wayMappings);
            return new OSMMappingRefresher(osmCacheOSClient, osmClient, nanoTime, metrics, reviewRoadMismatches)
                    .refresh(loopSeconds, wayMappings);
        }
    }

    private static boolean isScheduled(String input) {
        // Tag-selected schedules supply no body. Manual invocation opts into the shorter limits.
        if (input == null || input.isBlank()) return true;
        try {
            JsonNode request = JSON.readTree(input);
            if (request != null && request.isObject()) {
                JsonNode mode = request.get("mode");
                if (mode == null) return true;
                if (mode.isTextual()) {
                    if ("scheduled".equals(mode.textValue())) return true;
                    if ("manual".equals(mode.textValue())) return false;
                }
            }
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid OSM invocation JSON", invalid);
        }
        throw new IllegalArgumentException("OSM invocation must be an object with mode manual or scheduled");
    }
}
