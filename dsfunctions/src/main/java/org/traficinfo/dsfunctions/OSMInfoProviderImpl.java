package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Loads and refreshes OSM tags in Object Storage, with a bounded request budget. */
public class OSMInfoProviderImpl implements OSMInfoProvider {
    private static final Logger LOG = LoggerFactory.getLogger(OSMInfoProviderImpl.class);
    private static final int MAX_OSM_REQUESTS = 100;

    private final ObjectStoreClient osmCacheOSClient;
    private final OpenStreetMapClient osmClient;
    private final String osmCacheObject;
    private final Clock clock;
    private Map<Long, OSMWayInfo> cache = new LinkedHashMap<>();
    private final Set<Long> waysToRefresh = new LinkedHashSet<>();
    private final String mappingObject;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, ObjectNode> mappings = new LinkedHashMap<>();
    private final Set<String> missingMappings = new LinkedHashSet<>();

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
        waysToRefresh.clear();
        cache = readCache(osmCacheObject, new TypeReference<LinkedHashMap<Long, OSMWayInfo>>() { });
        LOG.info("Loaded {} OSM entries from cache {}", cache.size(), osmCacheObject);
        if (mappingObject != null) {
            mappings = readCache(mappingObject, new TypeReference<LinkedHashMap<String, ObjectNode>>() { });
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

    @Override
    public Long getWayId(String centraleId, Point point, String roadReference) {
        if (mappingObject == null || centraleId == null || centraleId.isBlank()) {
            return null;
        }
        ObjectNode mapping = mappings.get(centraleId);
        JsonNode id = mapping == null ? null : mapping.get("osmWayId");
        if (id != null && id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0) {
            return id.asLong();
        }
        missingMappings.add(centraleId);
        String now = clock.instant().toString();
        if (mapping == null) {
            mapping = mapper.createObjectNode();
            mapping.putNull("osmWayId");
            mapping.put("status", "MISSING");
            mapping.put("firstSeen", now);
            mappings.put(centraleId, mapping);
        }
        Double latitude = point == null || point.getCoordinates() == null ? null : point.getCoordinates().getLatitude();
        Double longitude = point == null || point.getCoordinates() == null ? null : point.getCoordinates().getLongitude();
        if (latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
            mapping.put("latitude", latitude);
            mapping.put("longitude", longitude);
        } else if (!mapping.has("latitude") && !mapping.has("longitude")) {
            mapping.putNull("latitude");
            mapping.putNull("longitude");
        }
        if (roadReference != null && !roadReference.isBlank()) {
            mapping.put("sourceRoadReference", roadReference);
        } else if (!mapping.has("sourceRoadReference")) {
            mapping.putNull("sourceRoadReference");
        }
        mapping.put("lastSeen", now);
        return null;
    }

    private void saveMappings() throws IOException {
        if (mappingObject == null) {
            return;
        }
        LOG.info("OSM way mappings: {} counters missing a usable way ID", missingMappings.size());
        byte[] json = mapper.writeValueAsBytes(mappings);
        try (InputStream input = new ByteArrayInputStream(json)) {
            osmCacheOSClient.putResource(mappingObject, "application/json", new OOSResourceMetadata(), input);
        }
        LOG.info("Stored {} counter-to-way entries in {}", mappings.size(), mappingObject);
    }

    @Override
    public OSMWayInfo get(long wayId) {
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
