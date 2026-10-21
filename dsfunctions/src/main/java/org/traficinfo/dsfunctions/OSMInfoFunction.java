package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Hourly bounded reverse lookup of unresolved counter-to-way mappings in Object Storage. */
public class OSMInfoFunction {
    private static final Logger LOG = LoggerFactory.getLogger(OSMInfoFunction.class);
    private static final String MAPPING_SUFFIX = "_OSMWayMapping.json";
    private static final int MAX_LOOP_DURATION_SECONDS = 120;
    private static final int MAX_WAY_MAPPINGS_PER_RESOURCE = 5;
    private int maxLoopSeconds = MAX_LOOP_DURATION_SECONDS;
    private int maxWayMappings = MAX_WAY_MAPPINGS_PER_RESOURCE;
    private ObjectStoreClient osmCacheOSClient;
    private OpenStreetMapClient osmClient;
    private final LongSupplier nanoTime;

    public OSMInfoFunction() {
        this(null);
    }

    OSMInfoFunction(ObjectStoreClient osmCacheOSClient) {
        this(osmCacheOSClient, new OpenStreetMapClient(), System::nanoTime);
    }

    OSMInfoFunction(ObjectStoreClient osmCacheOSClient, OpenStreetMapClient osmClient, LongSupplier nanoTime) {
        this.osmCacheOSClient = osmCacheOSClient;
        this.osmClient = osmClient;
        this.nanoTime = nanoTime;
    }

    @FnConfiguration
    public void config(RuntimeContext ctx) {
        configureLimits(ctx);
        String namespace = ctx.getConfigurationByKey("NAMESPACE").orElse("");
        String bucket = ctx.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        osmCacheOSClient = new ObjectStoreClient(useResourcePrincipal, namespace, bucket);
    }

    void configureLimits(RuntimeContext ctx) {
        maxLoopSeconds = configuredLimit(ctx, "MAX_LOOP_DURATION_SECONDS", MAX_LOOP_DURATION_SECONDS);
        maxWayMappings = configuredLimit(ctx, "MAX_WAY_MAPPINGS_PER_RESOURCE", MAX_WAY_MAPPINGS_PER_RESOURCE);
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
        int caches = 0;
        int failures = 0;
        int loadedCaches = 0;
        long listedCaches = 0;
        Map<String, Map<OSMWayMappingStatus, Integer>> processedStatuses = new TreeMap<>();
        Map<String, Map<OSMWayMappingStatus, Integer>> allStatuses = new TreeMap<>();
        try {
            List<String> resources = new ArrayList<>(osmCacheOSClient.listResources());
            listedCaches = resources.stream().filter(name -> name.endsWith(MAPPING_SUFFIX)).count();
            // Vary processing order so the same caches are not always handled first.
            Collections.shuffle(resources);
            long started = nanoTime.getAsLong();
            for (String name : resources) {
                // Check only between resources, allowing the current bounded batch to finish and save.
                if (nanoTime.getAsLong() - started >= TimeUnit.SECONDS.toNanos(maxLoopSeconds)) {
                    LOG.info("OSM resource loop reached its {} second budget; remaining caches wait for the next run",
                            maxLoopSeconds);
                    break;
                }
                if (!name.endsWith(MAPPING_SUFFIX)) {
                    continue;
                }
                String prefix = name.substring(0, name.length() - MAPPING_SUFFIX.length());
                String infoName = prefix + "_OSMInfo.json";
                OSMInfoProviderImpl provider = new OSMInfoProviderImpl(osmCacheOSClient,
                        osmClient, infoName, name);
                boolean loaded = false;
                try {
                    provider.loadCache();
                    loaded = true;
                    loadedCaches++;
                    LOG.info("OSM way mapping cache {}: {} unknown entries out of {} entries",
                            name, provider.getUnknownWayMappingCount(), provider.getWayMappingCount());
                    LOG.info("OSM info cache {}: {} entries", infoName, provider.getWayInfoCount());
                    provider.refreshWayMappings(maxWayMappings);
                    provider.refreshWayInfo();
                    caches++;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOG.warn("OSM mapping refresh interrupted", interrupted);
                    return "KO";
                } catch (Exception failure) {
                    failures++;
                    LOG.warn("Could not report OSM way mapping cache {}", name, failure);
                } finally {
                    if (loaded) {
                        processedStatuses.put(prefix, provider.getProcessedWayMappingStatusCounts());
                        allStatuses.put(prefix, provider.getWayMappingStatusCounts());
                    }
                }
            }
            LOG.info("OSM mapping report: {} caches processed, {} failures", caches, failures);
            return failures == 0 ? "OK" : "KO";
        } catch (Exception failure) {
            LOG.warn("Could not list OSM way mapping caches", failure);
            return "KO";
        } finally {
            processedStatuses.forEach((prefix, counts) -> {
                LOG.info("OSM mapping statuses for {} processed records: {}", prefix, counts);
                LOG.info("OSM mapping statuses for {} all records: {}", prefix, allStatuses.get(prefix));
            });
            if (listedCaches > loadedCaches) {
                LOG.info("OSM status totals exclude {} mapping caches not loaded because of the time budget or load errors",
                        listedCaches - loadedCaches);
            }
        }
    }
}
