package org.traficinfo.dsfunctions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Coordinates one refresh run across all sources; per-source mapping logic stays in OSMInfoProviderImpl. */
final class OSMMappingRefresher {
    private static final Logger LOG = LoggerFactory.getLogger(OSMMappingRefresher.class);
    private static final String MAPPING_SUFFIX = "_OSMWayMapping.json";
    private final ObjectStoreClient osmCacheOSClient;
    private final OpenStreetMapClient osmClient;
    private final LongSupplier nanoTime;
    private final MetricsPublisher metrics;
    private final boolean reviewRoadMismatches;
    private final List<OSMWayMappingReviewStore> submissionStores;
    private final Map<OSMWayMappingStatus, OSMWayMappingReviewStore> reviewStores = new LinkedHashMap<>();
    private final Map<String, OSMInfoProviderImpl> osmProvidersMap = new TreeMap<>();
    private int caches;
    private int failures;
    private boolean completeReview;

    OSMMappingRefresher(ObjectStoreClient osmCacheOSClient, OpenStreetMapClient osmClient,
                        LongSupplier nanoTime, MetricsPublisher metrics, boolean reviewRoadMismatches) {
        this.osmCacheOSClient = osmCacheOSClient;
        this.osmClient = osmClient;
        this.nanoTime = nanoTime;
        this.metrics = metrics;
        this.reviewRoadMismatches = reviewRoadMismatches;
        submissionStores = List.of(
                new OSMWayMappingReviewStore(osmCacheOSClient, OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME),
                new OSMWayMappingReviewStore(osmCacheOSClient, OSMWayMappingReviewStore.NOT_FOUND_MERGED_OBJECT_NAME));
        for (OSMWayMappingStatus status : List.of(OSMWayMappingStatus.NEEDS_REVIEW, OSMWayMappingStatus.NOT_FOUND)) {
            reviewStores.put(status, new OSMWayMappingReviewStore(osmCacheOSClient, status));
        }
    }

    String refresh(int maxLoopSeconds, int maxWayMappings) {
        osmClient.clearReverseLookupCache();
        try {
            var reviewedDocuments = loadReviews();
            var resources = discoverResources(reviewedDocuments);
            loadProviders(resources, reviewedDocuments);
            var allocations = allocateWork(resources, maxWayMappings);
            completeReview = failures == 0;
            refreshProviders(resources, allocations, maxLoopSeconds);
        } catch (Exception failure) {
            failures++;
            LOG.warn("Could not load OSM review document or list mapping caches", failure);
        }
        logMappingStatuses();
        saveReviews();
        deleteProcessedSubmissions();
        LOG.info("OSM mapping report: {} caches processed, {} failures", caches, failures);
        if (failures > 0) {
            throw new IllegalStateException("OSM mapping refresh failed with " + failures + " failure(s); see invocation logs");
        }
        publishMappingMetrics(osmProvidersMap);
        return "OK";
    }

    private List<Map<String, Map<String, OSMWayMapping>>> loadReviews() throws IOException {
        List<Map<String, Map<String, OSMWayMapping>>> reviewedDocuments = new ArrayList<>();
        for (OSMWayMappingReviewStore submission : submissionStores) {
            Map<String, Map<String, OSMWayMapping>> reviewed = submission.loadApprovals();
            reviewedDocuments.add(reviewed);
            if (submission.wasLoaded()) LOG.info("Loaded OSM approval submission {}", submission.getObjectName());
        }
        for (OSMWayMappingReviewStore reviewStore : reviewStores.values()) {
            reviewStore.load(); // Generated outputs are read only for change detection, never as decisions.
        }
        return reviewedDocuments;
    }

    private List<String> discoverResources(List<Map<String, Map<String, OSMWayMapping>>> reviewedDocuments) {
        List<String> resources = new ArrayList<>(osmCacheOSClient.listResources());
        // New sources may have only requests, without a mapping cache yet.
        for (String name : List.copyOf(resources)) {
            if (name.endsWith(OSMRequests.SUFFIX)) {
                String cacheName = name.substring(0, name.length() - OSMRequests.SUFFIX.length()) + MAPPING_SUFFIX;
                if (!resources.contains(cacheName)) resources.add(cacheName);
            }
        }
        for (Map<String, Map<String, OSMWayMapping>> reviewed : reviewedDocuments) {
            reviewed.forEach((source, records) -> {
                String cacheName = source + MAPPING_SUFFIX;
                if (!records.isEmpty() && !resources.contains(cacheName)) resources.add(cacheName);
            });
        }
        // Vary processing order so the same caches are not always handled first.
        Collections.shuffle(resources);
        return resources;
    }

    private void loadProviders(List<String> resources,
                               List<Map<String, Map<String, OSMWayMapping>>> reviewedDocuments) {
        // Load every source and save approvals before the bounded API loop. This also makes
        // the final review document complete for sources skipped by the API time budget.
        for (String osResourceName : resources) {
            if (!osResourceName.endsWith(MAPPING_SUFFIX)) {
                continue;
            }
            String datasourceName = osResourceName.substring(0, osResourceName.length() - MAPPING_SUFFIX.length());
            try (var sourceLogging = LoggingContext.forSource("osminfo", datasourceName)) {
                OSMInfoProviderImpl osmProvider = new OSMInfoProviderImpl(osmCacheOSClient,
                        osmClient, datasourceName + "_OSMInfo.json", osResourceName,
                        Clock.systemUTC(), reviewRoadMismatches);
                try {
                    osmProvider.loadCache();
                    osmProvider.mergeRequests(osmProvider.loadRequests(datasourceName));
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
    }

    private Map<String, Integer> allocateWork(List<String> resources, int maxWayMappings) {
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
        return reverseLookupAllocations;
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

    private void refreshProviders(List<String> resources, Map<String, Integer> reverseLookupAllocations,
                                  int maxLoopSeconds) {
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
    }

    private void logMappingStatuses() {
        for (Map.Entry<String, OSMInfoProviderImpl> entry : osmProvidersMap.entrySet()) {
            String datasourceName = entry.getKey();
            try (var sourceLogging = LoggingContext.forSource("osminfo", datasourceName)) {
                OSMInfoProviderImpl osmProvider = entry.getValue();
                LOG.info("OSM mapping statuses for {} processed records: {}", datasourceName,
                        osmProvider.getProcessedWayMappingStatusCounts());
                LOG.info("OSM mapping statuses for {} all records: {}", datasourceName, osmProvider.getWayMappingStatusCounts());
            }
        }
    }

    private void saveReviews() {
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
    }

    private void deleteProcessedSubmissions() {
        if (failures == 0 && completeReview) {
            for (OSMWayMappingReviewStore submission : submissionStores) {
                if (!submission.wasLoaded()) continue;
                try {
                    osmCacheOSClient.deleteResource(submission.getObjectName());
                    LOG.info("Deleted processed OSM approval submission {}", submission.getObjectName());
                } catch (Exception failure) {
                    failures++;
                    LOG.warn("Could not delete processed OSM approval submission {}", submission.getObjectName(), failure);
                }
            }
        }
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
