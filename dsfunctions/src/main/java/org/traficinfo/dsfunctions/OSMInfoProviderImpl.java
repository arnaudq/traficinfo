/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import com.oracle.bmc.model.BmcException;
import org.geojson.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OOSResourceMetadata;
import org.traficinfo.parsers.OSMWayInfo;
import org.traficinfo.parsers.OSMInfoProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.EnumMap;

/** Loads and refreshes OSM tags in Object Storage, with a bounded request budget. */
public class OSMInfoProviderImpl implements OSMInfoProvider {
    private static final Logger LOG = LoggerFactory.getLogger(OSMInfoProviderImpl.class);
    private static final int MAX_OSM_REQUESTS = 100;
    private static final Set<String> ROAD_HIGHWAY_TYPES = Set.of("motorway", "trunk", "primary", "secondary",
            "tertiary", "unclassified", "residential", "living_street", "service", "road", "motorway_link",
            "trunk_link", "primary_link", "secondary_link", "tertiary_link", "footway", "cycleway", "path", "pedestrian", "track");

    private final ObjectStoreClient osmCacheOSClient;
    private final OpenStreetMapClient osmClient;
    private final String osmCacheObject;
    private final Clock clock;
    private Map<Long, OSMWayInfo> cache = new LinkedHashMap<>();
    private final Set<Long> waysToRefresh = new LinkedHashSet<>();
    private final String mappingObject;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Map<String, OSMWayMapping> mappings = new LinkedHashMap<>();
    private final Set<String> missingMappings = new LinkedHashSet<>();
    private final Set<String> processedMappings = new LinkedHashSet<>();

    public OSMInfoProviderImpl(ObjectStoreClient osmCacheOSClient, OpenStreetMapClient osmClient,
                           String osmCacheObject) {
        this(osmCacheOSClient, osmClient, osmCacheObject, Clock.systemUTC());
    }

    public OSMInfoProviderImpl(ObjectStoreClient osmCacheOSClient, OpenStreetMapClient osmClient,
                           String osmCacheObject, Clock clock) {
        this(osmCacheOSClient, osmClient, osmCacheObject, null, clock);
    }

    public OSMInfoProviderImpl(ObjectStoreClient osmCacheOSClient, OpenStreetMapClient osmClient,
                               String osmCacheObject, String mappingObject) {
        this(osmCacheOSClient, osmClient, osmCacheObject, mappingObject, Clock.systemUTC());
    }

    public OSMInfoProviderImpl(ObjectStoreClient osmCacheOSClient, OpenStreetMapClient osmClient,
                               String osmCacheObject, String mappingObject, Clock clock) {
        if (osmCacheObject.equals(mappingObject)) {
            throw new IllegalArgumentException("Way mappings and way tags require different object names");
        }
        this.mappingObject = mappingObject;
        this.osmCacheOSClient = osmCacheOSClient;
        this.osmClient = osmClient;
        this.osmCacheObject = osmCacheObject;
        this.clock = clock;
    }

    /** A missing cache object starts empty; other read errors propagate. */
    public void loadCache() throws IOException {
        missingMappings.clear();
        processedMappings.clear();
        waysToRefresh.clear();
        cache = readCache(osmCacheObject, new TypeReference<LinkedHashMap<Long, OSMWayInfo>>() { });
        LOG.info("Loaded {} OSM entries from cache {}", cache.size(), osmCacheObject);
        if (mappingObject != null) {
            mappings = readCache(mappingObject, new TypeReference<LinkedHashMap<String, OSMWayMapping>>() { });
            LOG.info("Loaded {} counter-to-way mappings from {}", mappings.size(), mappingObject);
        }
    }

    private <K, V> Map<K, V> readCache(String name, TypeReference<LinkedHashMap<K, V>> type)
            throws IOException {
        try (InputStream input = osmCacheOSClient.getResource(name)) {
            Map<K, V> loaded = mapper.readValue(input, type);
            if (loaded == null) {
                throw new IOException("OSM cache must be a JSON object");
            }
            return loaded;
        } catch (BmcException failure) {
            if (failure.getStatusCode() == 404) {
                return new LinkedHashMap<>();
            }
            throw failure;
        }
    }

    /** Missing request objects start empty; only the producer writes them. */
    public OSMRequests loadRequests(String sourceId) throws IOException {
        try (InputStream input = osmCacheOSClient.getResource(sourceId + OSMRequests.SUFFIX)) {
            OSMRequests requests = mapper.readValue(input, OSMRequests.class);
            if (requests == null) throw new IOException("OSM requests must be a JSON object");
            return requests;
        } catch (BmcException failure) {
            if (failure.getStatusCode() == 404) return OSMRequests.empty();
            throw failure;
        }
    }

    /** Publish a complete source-owned snapshot, including empty sections to clear old requests. */
    void saveRequests(String sourceId, OSMRequests requests) throws IOException {
        try (InputStream input = new ByteArrayInputStream(mapper.writeValueAsBytes(requests))) {
            osmCacheOSClient.putResource(sourceId + OSMRequests.SUFFIX, "application/json", new OOSResourceMetadata(), input);
        }
        LOG.info("Published OSM requests for {}: {} reverse lookups, {} way-tag refreshes",
                sourceId, requests.reverseLookups().size(), requests.wayTagRefreshes().size());
    }

    /** The OSM function alone merges producer requests into persistent caches. */
    public void mergeRequests(OSMRequests requests) throws IOException {
        boolean mappingsChanged = false;
        for (var entry : requests.reverseLookups().entrySet()) {
            var location = entry.getValue();
            if (getCachedWayId(entry.getKey()) == null) mappingsChanged = true;
            Point point = location.latitude() == null || location.longitude() == null
                    ? null : new Point(location.longitude(), location.latitude());
            Long wayId = getWayId(entry.getKey(), point, location.sourceRoadReference());
            if (wayId != null) getWayInfo(wayId);
        }
        requests.wayTagRefreshes().forEach(this::getWayInfo);
        if (mappingsChanged) saveMappings();
    }

    Long getCachedWayId(String counterId) {
        return mappedWayId(mappings.get(counterId));
    }

    OSMWayInfo getCachedWayInfo(long wayId) {
        return cache.get(wayId);
    }

    boolean needsWayTagRefresh(long wayId) {
        OSMWayInfo info = cache.get(wayId);
        return info == null || info.needsRefresh(clock.instant());
    }

    /** Number of cached OSM ways, including entries without a maximum speed tag. */
    public int getWayInfoCount() {
        return cache.size();
    }

    /** Total number of entries in the counter-to-way mapping cache. */
    public int getWayMappingCount() {
        return mappings.size();
    }

    /** Number of cached mappings without a positive integral way ID, including review candidates. */
    public int getUnknownWayMappingCount() {
        return (int) mappings.values().stream().filter(mapping -> mappedWayId(mapping) == null).count();
    }

    /** Records eligible now: MISSING requests a fresh lookup; other cached outcomes remain reusable. */
    public int getPendingReverseLookupCount() {
        if (mappingObject == null) {
            return 0;
        }
        return (int) mappings.values().stream().filter(OSMInfoProviderImpl::needsReverseLookup).count();
    }

    /** Status counts across every loaded mapping, including records not attempted this run. */
    public Map<OSMWayMappingStatus, Integer> getWayMappingStatusCounts() {
        return statusCounts(mappings.values());
    }

    /** Final statuses of unique records attempted this run, including HTTP failures. */
    public Map<OSMWayMappingStatus, Integer> getProcessedWayMappingStatusCounts() {
        return statusCounts(processedMappings.stream().map(mappings::get).toList());
    }

    private static Map<OSMWayMappingStatus, Integer> statusCounts(Collection<OSMWayMapping> records) {
        Map<OSMWayMappingStatus, Integer> counts = new EnumMap<>(OSMWayMappingStatus.class);
        for (OSMWayMappingStatus status : OSMWayMappingStatus.values()) {
            counts.put(status, 0);
        }
        for (OSMWayMapping mapping : records) {
            OSMWayMappingStatus status = mapping == null ? OSMWayMappingStatus.MISSING : mapping.getStatus();
            counts.merge(status, 1, Integer::sum);
        }
        return counts;
    }

    /** Mappings with the requested status, keyed by counter ID; the cache itself stays private. */
    public Map<String, OSMWayMapping> getWayMappingsByStatus(OSMWayMappingStatus status) {
        Map<String, OSMWayMapping> review = new LinkedHashMap<>();
        mappings.forEach((id, mapping) -> {
            if (mapping != null && mapping.getStatus() == status) {
                review.put(id, mapper.convertValue(mapping, OSMWayMapping.class));
            }
        });
        return review;
    }

    /** Apply explicit manual approvals to current review candidates and persist before any API calls. */
    public void applyReviewedWayMappings(Map<String, OSMWayMapping> reviewed) throws IOException {
        // Validate the whole batch before changing the cache.
        for (Map.Entry<String, OSMWayMapping> entry : reviewed.entrySet()) {
            OSMWayMapping decision = entry.getValue();
            if (decision == null) {
                throw new IOException("Null OSM review record: " + entry.getKey());
            }
            if (decision.getStatus() == OSMWayMappingStatus.MATCHED
                    && (decision.getCandidateOsmWayId() == null || decision.getCandidateOsmWayId() <= 0)) {
                throw new IOException("Manual MATCHED record requires a positive candidateOsmWayId: " + entry.getKey());
            }
            if (decision.getStatus() == OSMWayMappingStatus.MATCHED) {
                OSMWayMapping current = mappings.get(entry.getKey());
                if (current == null) {
                    throw new IOException("Manual MATCHED record has no corresponding cached counter: " + entry.getKey());
                }
                if (mappedWayId(current) == null && current.getStatus() != OSMWayMappingStatus.NEEDS_REVIEW
                        && current.getStatus() != OSMWayMappingStatus.NOT_FOUND) {
                    throw new IOException("Manual approval requires an existing review outcome: " + entry.getKey());
                }
            }
        }
        int approved = 0;
        for (Map.Entry<String, OSMWayMapping> entry : reviewed.entrySet()) {
            OSMWayMapping decision = entry.getValue();
            OSMWayMapping current = mappings.get(entry.getKey());
            // On retry, keep an assigned way and recover any tag fetch that was interrupted.
            if (decision.getStatus() == OSMWayMappingStatus.MATCHED && mappedWayId(current) != null) {
                getWayInfo(current.getOsmWayId());
                continue;
            }
            // A stale review must not replace a way that has already been assigned.
            if (decision.getStatus() != OSMWayMappingStatus.MATCHED || current == null
                    || (current.getStatus() != OSMWayMappingStatus.NEEDS_REVIEW
                        && current.getStatus() != OSMWayMappingStatus.NOT_FOUND) || mappedWayId(current) != null) {
                continue;
            }
            current.setOsmWayId(decision.getCandidateOsmWayId());
            current.setStatus(OSMWayMappingStatus.MATCHED);
            current.setCandidateOsmWayId(null);
            current.setReviewReason(null);
            current.setLastUpdated(clock.instant().toString());
            getWayInfo(current.getOsmWayId());
            processedMappings.add(entry.getKey());
            approved++;
        }
        if (approved > 0) {
            saveMappings();
            LOG.info("Applied {} manual OSM approvals to {}", approved, mappingObject);
        }
    }

    private static Long mappedWayId(OSMWayMapping mapping) {
        Long id = mapping == null ? null : mapping.getOsmWayId();
        return id != null && id > 0 ? id : null;
    }

    @Override
    public Long getWayId(String centraleId, Point point, String roadReference) {
        if (mappingObject == null || centraleId == null || centraleId.isBlank()) {
            return null;
        }
        OSMWayMapping mapping = mappings.get(centraleId);
        Long wayId = mappedWayId(mapping);
        if (wayId != null) {
            return wayId;
        }
        missingMappings.add(centraleId);
        String now = clock.instant().toString();
        if (mapping == null) {
            mapping = new OSMWayMapping();
            mapping.setStatus(OSMWayMappingStatus.MISSING);
            mapping.setFirstSeen(now);
            mappings.put(centraleId, mapping);
        }
        Double latitude = point == null || point.getCoordinates() == null ? null : point.getCoordinates().getLatitude();
        Double longitude = point == null || point.getCoordinates() == null ? null : point.getCoordinates().getLongitude();
        if (latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
            mapping.setLatitude(latitude);
            mapping.setLongitude(longitude);
        }
        if (roadReference != null && !roadReference.isBlank()) {
            mapping.setSourceRoadReference(roadReference);
        }
        mapping.setLastSeen(now);
        return null;
    }

    /** Resolves a bounded batch; cached outcomes are reused until coordinates change or status is reset to MISSING. */
    public void refreshWayMappings(int maximumMappings) throws IOException, InterruptedException {
        if (mappingObject == null || maximumMappings <= 0) {
            return;
        }
        List<Map.Entry<String, OSMWayMapping>> incomplete = new ArrayList<>();
        for (Map.Entry<String, OSMWayMapping> entry : mappings.entrySet()) {
            OSMWayMapping mapping = entry.getValue();
            if (needsReverseLookup(mapping)) {
                incomplete.add(entry);
            }
        }
        // Try entries never attempted first, then the oldest HTTP failures, to avoid starving later counters.
        incomplete.sort(Comparator.comparing(entry -> entry.getValue().getLastLookupAt(),
                Comparator.nullsFirst(Comparator.naturalOrder())));
        int attempted = 0;
        int resolved = 0;
        int failed = 0;
        boolean changed = false;
        try {
            for (Map.Entry<String, OSMWayMapping> entry : incomplete) {
                OSMWayMapping mapping = entry.getValue();
                if (attempted >= maximumMappings) {
                    break;
                }
                attempted++;
                processedMappings.add(entry.getKey());
                changed = true;
                mapping.setLastLookupAt(clock.instant().toString());
                try {
                    JsonNode result = osmClient.reverseLookup(mapping.getLatitude(), mapping.getLongitude());
                    mapping.setLastLookupAt(clock.instant().toString());
                    mapping.setLastLookupLatitude(mapping.getLatitude());
                    mapping.setLastLookupLongitude(mapping.getLongitude());
                    mapping.setReverseLookupResult(result);
                    applyReverseResult(mapping, result);
                    LOG.info("OSM reverse mapping {}: status={}, wayId={}, candidateWayId={}, sourceRef={}, osmRef={}, reason={}",
                            entry.getKey(), mapping.getStatus(), mapping.getOsmWayId(), mapping.getCandidateOsmWayId(),
                            mapping.getSourceRoadReference(), mapping.getOsmRoadReference(), mapping.getReviewReason());
                    Long wayId = mappedWayId(mapping);
                    if (wayId != null) {
                        getWayInfo(wayId);
                        resolved++;
                    }
                } catch (IOException failure) {
                    failed++;
                    LOG.warn("Could not reverse lookup counter {} at coordinates {}, {}: {}",
                            entry.getKey(), mapping.getLatitude(), mapping.getLongitude(), failure.getMessage());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (changed) {
                saveMappings();
            }
        }
        LOG.info("OSM way mapping cache {}: {} attempted, {} resolved, {} failed, {} still unknown",
                mappingObject, attempted, resolved, failed, getUnknownWayMappingCount());
    }

    private static boolean needsReverseLookup(OSMWayMapping mapping) {
        return mappedWayId(mapping) == null && hasCoordinates(mapping)
                && (mapping.getStatus() == OSMWayMappingStatus.MISSING || !hasCachedReverseResult(mapping));
    }

    private static boolean hasCoordinates(OSMWayMapping mapping) {
        return mapping != null && mapping.getLatitude() != null && mapping.getLongitude() != null
                && Double.isFinite(mapping.getLatitude()) && Double.isFinite(mapping.getLongitude())
                && Math.abs(mapping.getLatitude()) <= 90 && Math.abs(mapping.getLongitude()) <= 180;
    }

    private static boolean hasCachedReverseResult(OSMWayMapping mapping) {
        return mapping.getReverseLookupResult() != null
                && Objects.equals(mapping.getLatitude(), mapping.getLastLookupLatitude())
                && Objects.equals(mapping.getLongitude(), mapping.getLastLookupLongitude());
    }

    private static void applyReverseResult(OSMWayMapping mapping, JsonNode result) {
        Long wayId = reverseWayId(result.path("osm_id"));
        // Nominatim can classify a road way as an administrative boundary while retaining its highway tag.
        boolean road = "way".equals(result.path("osm_type").asText())
                && (("highway".equals(result.path("category").asText())
                        && ROAD_HIGHWAY_TYPES.contains(result.path("type").asText()))
                    || ROAD_HIGHWAY_TYPES.contains(result.path("extratags").path("highway").asText()));
        mapping.setLastUpdated(mapping.getLastLookupAt());
        mapping.setDistanceMeters(null);
        if (!road || wayId == null) {
            mapping.setStatus(OSMWayMappingStatus.NOT_FOUND);
            mapping.setCandidateOsmWayId(null);
            mapping.setOsmRoadReference(null);
            mapping.setReviewReason("no_road_way");
            return;
        }
        String reference = result.path("namedetails").path("ref").asText(null);
        mapping.setCandidateOsmWayId(wayId);
        mapping.setOsmRoadReference(reference);
        String source = mapping.getSourceRoadReference();
        if (source != null && !source.isBlank()
                && (reference == null || !sameRoadReference(source, reference))) {
            mapping.setStatus(OSMWayMappingStatus.NEEDS_REVIEW);
            mapping.setReviewReason(reference == null ? "missing_osm_ref" : "different_ref");
            return;
        }
        mapping.setOsmWayId(wayId);
        mapping.setStatus(OSMWayMappingStatus.MATCHED);
        mapping.setCandidateOsmWayId(null);
        mapping.setReviewReason(null);
    }

    private static Long reverseWayId(JsonNode id) {
        if (id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0) {
            return id.asLong();
        }
        if (id.isTextual() && id.asText().matches("[0-9]+")) {
            try {
                long value = Long.parseLong(id.asText());
                return value > 0 ? value : null;
            } catch (NumberFormatException invalidId) {
                return null;
            }
        }
        return null;
    }

    private static boolean sameRoadReference(String source, String osmReference) {
        for (String reference : osmReference.split(";")) {
            if (normalizeRoadReference(source).equals(normalizeRoadReference(reference))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeRoadReference(String reference) {
        return reference.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "")
                .replaceAll("([A-Z])0+(?=[0-9])", "$1");
    }

    /**
     * Persist seeded mappings without performing reverse lookups or way-tag HTTP requests.
     * Use {@link #refreshCache()} instead to also process queued way-tag HTTP requests.
     */
    public void saveWayMappings() throws IOException {
        saveMappings();
    }

    private void saveMappings() throws IOException {
        if (mappingObject == null) {
            return;
        }
        LOG.info("OSM way mappings: {} unresolved entries; {} counters looked up without a usable ID this run",
                getUnknownWayMappingCount(), missingMappings.size());
        byte[] json = mapper.writeValueAsBytes(mappings);
        try (InputStream input = new ByteArrayInputStream(json)) {
            osmCacheOSClient.putResource(mappingObject, "application/json", new OOSResourceMetadata(), input);
        }
        LOG.info("Stored {} counter-to-way entries in {}", mappings.size(), mappingObject);
    }

    @Override
    public OSMWayInfo getWayInfo(long wayId) {
        OSMWayInfo info = cache.get(wayId);
        if (wayId > 0 && (info == null || info.needsRefresh(clock.instant()))) {
            waysToRefresh.add(wayId);
        }
        return info;
    }

    /** Refreshes up to 100 queued ways and saves successful fetches, preserving failed entries. */
    public void refreshCache()
            throws IOException, InterruptedException {
        saveMappings();
        refreshWayInfo();
    }

    /** Processes queued way IDs and persists successful tag fetches without rewriting mappings. */
    public void refreshWayInfo() throws IOException, InterruptedException {
        LOG.info("OSM cache: {} entries need refresh", waysToRefresh.size());
        int requests = 0;
        int found = 0;
        for (Long wayId : waysToRefresh) {
            if (requests >= MAX_OSM_REQUESTS) {
                break;
            }
            requests++;
            try {
                Map<String, String> tags = osmClient.getWayTags(wayId);
                cache.put(wayId, new OSMWayInfo(clock.instant(), tags));
                found++;
            } catch (IOException failure) {
                LOG.warn("Could not refresh OSM way {}: {}", wayId, failure.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
        LOG.info("OSM cache refresh: {} entries found from {} requests", found, requests);
        if (found > 0) {
            byte[] json = new ObjectMapper().writeValueAsBytes(cache);
            try (InputStream input = new ByteArrayInputStream(json)) {
                osmCacheOSClient.putResource(osmCacheObject, "application/json",
                        new OOSResourceMetadata(), input);
            }
            LOG.info("OSM cache: {} entries after writing to Object Storage object {}",
                    cache.size(), osmCacheObject);
        }
    }
}
