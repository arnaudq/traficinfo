package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.model.BmcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.dsfunctions.m3m.ObjectStoreClient;
import org.traficinfo.dsfunctions.m3m.OOSResourceMetadata;
import org.traficinfo.parser.OSMWayInfo;
import org.traficinfo.parser.OSMInfoProvider;

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

    private final ObjectStoreClient osmCacheClient;
    private final OpenStreetMapClient osmClient;
    private final String osmCacheObject;
    private final Clock clock;
    private Map<Long, OSMWayInfo> cache = new LinkedHashMap<>();

    public OSMInfoProviderImpl(ObjectStoreClient osmCacheClient, OpenStreetMapClient osmClient,
                           String osmCacheObject) {
        this(osmCacheClient, osmClient, osmCacheObject, Clock.systemUTC());
    }

    public OSMInfoProviderImpl(ObjectStoreClient osmCacheClient, OpenStreetMapClient osmClient,
                           String osmCacheObject, Clock clock) {
        this.osmCacheClient = osmCacheClient;
        this.osmClient = osmClient;
        this.osmCacheObject = osmCacheObject;
        this.clock = clock;
    }

    /** A missing cache object starts empty; other read errors propagate. */
    public void loadCache() throws IOException {
        try (InputStream input = osmCacheClient.getResource(osmCacheObject)) {
            Map<Long, OSMWayInfo> loaded = new ObjectMapper().readValue(input,
                    new TypeReference<LinkedHashMap<Long, OSMWayInfo>>() { });
            if (loaded == null) {
                throw new IOException("OSM cache must be a JSON object");
            }
            cache = loaded;
        } catch (BmcException failure) {
            if (failure.getStatusCode() == 404) {
                cache = new LinkedHashMap<>();
            } else {
                throw failure;
            }
        }
        LOG.info("Loaded {} OSM entries from cache {}", cache.size(), osmCacheObject);
    }

    @Override
    public OSMWayInfo get(long wayId) {
        return cache.get(wayId);
    }

    /** Refreshes up to 100 queued ways and saves successful fetches, preserving failed entries. */
    public void refreshCache(Set<Long> waysToRefresh)
            throws IOException, InterruptedException {
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
                osmCacheClient.putResource(osmCacheObject, "application/json",
                        new OOSResourceMetadata(), input);
            }
            LOG.info("OSM cache: {} entries after writing to Object Storage object {}",
                    cache.size(), osmCacheObject);
        }
    }
}
