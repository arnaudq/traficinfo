package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.time.Instant;
import java.util.Objects;

/** Hourly bounded reverse lookup of unresolved counter-to-way mappings in Object Storage. */
public class OSMInfoFunction {
    private static final Logger LOG = LoggerFactory.getLogger(OSMInfoFunction.class);
    private static final String MAPPING_SUFFIX = "_OSMWayMapping.json";
    private static final int MAX_LOOP_DURATION_SECONDS = 180;
    // At four reverse requests per minute (15-second spacing in OpenStreetMapClient),
    // 12 lookups use about the default 180-second loop budget across all data sources.
    private static final int MAX_WAY_MAPPINGS_PER_RUN = 12;
    private int maxLoopSeconds = MAX_LOOP_DURATION_SECONDS;
    private int maxWayMappings = MAX_WAY_MAPPINGS_PER_RUN;
    private ObjectStoreClient osmCacheOSClient;
    private OpenStreetMapClient osmClient;
    private final LongSupplier nanoTime;
    private MetricsPublisher metrics;

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
        metrics = FunctionMetrics.configured(ctx);
        String namespace = ctx.getConfigurationByKey("NAMESPACE").orElse("");
        String bucket = ctx.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        osmCacheOSClient = new ObjectStoreClient(useResourcePrincipal, namespace, bucket);
    }

    void configureLimits(RuntimeContext ctx) {
        maxLoopSeconds = configuredLimit(ctx, "MAX_LOOP_DURATION_SECONDS", MAX_LOOP_DURATION_SECONDS);
        maxWayMappings = configuredLimit(ctx, "MAX_WAY_MAPPINGS_PER_RUN", MAX_WAY_MAPPINGS_PER_RUN);
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

    /** Share the run budget evenly, redistributing shares that exceed a source's demand. */
    static Map<String, Integer> allocateReverseLookups(Map<String, Integer> demand, int budget) {
        Map<String, Integer> allocations = new LinkedHashMap<>();
        List<String> active = new ArrayList<>();
        demand.forEach((datasourceName, count) -> {
            allocations.put(datasourceName, 0);
            if (count > 0) {
                active.add(datasourceName);
            }
        });
        int remaining = budget;
        while (remaining > 0 && !active.isEmpty()) {
            int share = Math.max(1, remaining / active.size());
            for (String datasourceName : active) {
                int allocated = allocations.get(datasourceName);
                int additional = Math.min(remaining, Math.min(share, demand.get(datasourceName) - allocated));
                allocations.put(datasourceName, allocated + additional);
                remaining -= additional;
                if (remaining == 0) {
                    break;
                }
            }
            active.removeIf(datasourceName -> allocations.get(datasourceName).equals(demand.get(datasourceName)));
        }
        return allocations;
    }

    public String handleRequest(String input) {
        try (var logging = LoggingContext.forSource("osminfo", null)) {
            return refreshMappings();
        }
    }

    private String refreshMappings() {
        osmClient.clearReverseLookupCache();
        int caches = 0;
        int failures = 0;
        boolean completeReview = false;
        Map<OSMWayMappingStatus, OSMWayMappingReviewStore> reviewStores = new LinkedHashMap<>();
        for (OSMWayMappingStatus status : List.of(OSMWayMappingStatus.NEEDS_REVIEW, OSMWayMappingStatus.NOT_FOUND)) {
            reviewStores.put(status, new OSMWayMappingReviewStore(osmCacheOSClient, status));
        }
        Map<String, OSMInfoProviderImpl> osmProvidersMap = new TreeMap<>();
        try {
            List<Map<String, Map<String, OSMWayMapping>>> reviewedDocuments = new ArrayList<>();
            for (OSMWayMappingReviewStore reviewStore : reviewStores.values()) {
                reviewedDocuments.add(reviewStore.load());
            }
            List<String> resources = new ArrayList<>(osmCacheOSClient.listResources());
            // Vary processing order so the same caches are not always handled first.
            Collections.shuffle(resources);
            // Load every source and save approvals before the bounded API loop. This also makes
            // the final review document complete for sources skipped by the API time budget.
            for (String osResourceName : resources) {
                if (!osResourceName.endsWith(MAPPING_SUFFIX)) {
                    continue;
                }
                String datasourceName = osResourceName.substring(0, osResourceName.length() - MAPPING_SUFFIX.length());
                try (var sourceLogging = LoggingContext.forSource("osminfo", datasourceName)) {
                    OSMInfoProviderImpl osmProvider = new OSMInfoProviderImpl(osmCacheOSClient,
                            osmClient, datasourceName + "_OSMInfo.json", osResourceName);
                    try {
                        osmProvider.loadCache();
                        for (Map<String, Map<String, OSMWayMapping>> reviewed : reviewedDocuments) {
                            osmProvider.applyReviewedWayMappings(reviewed.getOrDefault(datasourceName, Map.of()));
                        }
                        osmProvidersMap.put(datasourceName, osmProvider);
                        LOG.info("OSM way mapping cache {}: {} unknown entries out of {} entries",
                                osResourceName, osmProvider.getUnknownWayMappingCount(), osmProvider.getWayMappingCount());
                        LOG.info("OSM info cache {}_OSMInfo.json: {} entries", datasourceName, osmProvider.getWayInfoCount());
                    } catch (Exception failure) {
                        failures++;
                        LOG.warn("Could not load or apply reviews to OSM way mapping cache {}", osResourceName, failure);
                    }
                }
            }
            // Preserve shuffled resource order when sharing indivisible remainder requests.
            Map<String, Integer> pendingReverseLookups = new LinkedHashMap<>();
            for (String osResourceName : resources) {
                if (osResourceName.endsWith(MAPPING_SUFFIX)) {
                    String datasourceName = osResourceName.substring(0, osResourceName.length() - MAPPING_SUFFIX.length());
                    OSMInfoProviderImpl osmProvider = osmProvidersMap.get(datasourceName);
                    if (osmProvider != null) {
                        pendingReverseLookups.put(datasourceName, osmProvider.getPendingReverseLookupCount());
                    }
                }
            }
            LOG.info("OSM reverse lookup demand by data source: {}", pendingReverseLookups);
            LOG.info("OSM reverse lookup demand: {} records across {} data sources with pending lookups",
                    pendingReverseLookups.values().stream().mapToInt(Integer::intValue).sum(),
                    pendingReverseLookups.values().stream().filter(count -> count > 0).count());
            Map<String, Integer> reverseLookupAllocations = allocateReverseLookups(pendingReverseLookups, maxWayMappings);
            LOG.info("OSM reverse lookup allocation (run limit {}): {}", maxWayMappings, reverseLookupAllocations);
            completeReview = failures == 0;
            long started = nanoTime.getAsLong();
            for (String osResourceName : resources) {
                // Check only between resources, allowing the current bounded batch to finish and save.
                if (nanoTime.getAsLong() - started >= TimeUnit.SECONDS.toNanos(maxLoopSeconds)) {
                    LOG.info("OSM resource loop reached its {} second budget; remaining caches wait for the next run",
                            maxLoopSeconds);
                    break;
                }
                if (!osResourceName.endsWith(MAPPING_SUFFIX)) {
                    continue;
                }
                String datasourceName = osResourceName.substring(0, osResourceName.length() - MAPPING_SUFFIX.length());
                try (var sourceLogging = LoggingContext.forSource("osminfo", datasourceName)) {
                    OSMInfoProviderImpl osmProvider = osmProvidersMap.get(datasourceName);
                    if (osmProvider == null) {
                        LOG.info("Skipping OSM resource {} for data source {}: no loaded OSM provider",
                                osResourceName, datasourceName);
                        continue;
                    }
                    try {
                        osmProvider.refreshWayMappings(reverseLookupAllocations.getOrDefault(datasourceName, 0));
                        osmProvider.refreshWayInfo();
                        caches++;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        failures++;
                        LOG.warn("OSM mapping refresh interrupted", interrupted);
                        break;
                    } catch (Exception failure) {
                        failures++;
                        // Do not replace the review file if mapping persistence may have failed.
                        completeReview = false;
                        LOG.warn("Could not refresh OSM way mapping cache {}", osResourceName, failure);
                    }
                }
            }
        } catch (Exception failure) {
            failures++;
            LOG.warn("Could not load OSM review document or list mapping caches", failure);
        }
        for (Map.Entry<String, OSMInfoProviderImpl> entry : osmProvidersMap.entrySet()) {
            String datasourceName = entry.getKey();
            try (var sourceLogging = LoggingContext.forSource("osminfo", datasourceName)) {
                OSMInfoProviderImpl osmProvider = entry.getValue();
                LOG.info("OSM mapping statuses for {} processed records: {}", datasourceName,
                        osmProvider.getProcessedWayMappingStatusCounts());
                LOG.info("OSM mapping statuses for {} all records: {}", datasourceName, osmProvider.getWayMappingStatusCounts());
            }
        }
        if (completeReview) {
            for (var storeEntry : reviewStores.entrySet()) {
                OSMWayMappingReviewStore reviewStore = storeEntry.getValue();
                Map<String, Map<String, OSMWayMapping>> review = new TreeMap<>();
                osmProvidersMap.forEach((datasourceName, osmProvider) ->
                        review.put(datasourceName, osmProvider.getWayMappingsByStatus(storeEntry.getKey())));
                try {
                    OSMWayMappingReviewStore.SaveResult result = reviewStore.save(review);
                    LOG.info("{} OSM review document {}: {} records across {} data sources; {} added, {} changed, {} removed",
                            result.written() ? "Stored" : "Skipped PUT for unchanged",
                            reviewStore.getObjectName(),
                            review.values().stream().mapToInt(Map::size).sum(), review.size(),
                            result.added(), result.changed(), result.removed());
                } catch (Exception failure) {
                    failures++;
                    LOG.warn("Could not store OSM review document {}", reviewStore.getObjectName(), failure);
                }
            }
        } else {
            LOG.warn("Preserving existing OSM review documents because not all mapping caches were loaded and saved successfully");
        }
        LOG.info("OSM mapping report: {} caches processed, {} failures", caches, failures);
        if (failures > 0) {
            throw new IllegalStateException("OSM mapping refresh failed with " + failures + " failure(s); see invocation logs");
        }
        publishMappingMetrics(osmProvidersMap);
        return "OK";
    }

    private void publishMappingMetrics(Map<String, OSMInfoProviderImpl> providers) {
        Instant timestamp = Instant.now();
        var samples = providers.entrySet().stream().flatMap(source ->
                source.getValue().getWayMappingStatusCounts().entrySet().stream().map(status -> new MetricSample(
                        "OSMWayMappings", status.getValue(), "count", timestamp,
                        Map.of("datasourceId", source.getKey(), "status", status.getKey().name())))).toList();
        if (samples.isEmpty()) return;
        try {
            metrics.publish(samples);
        } catch (RuntimeException failure) {
            LOG.warn("Could not publish OSM mapping metrics", failure);
        }
    }
}
