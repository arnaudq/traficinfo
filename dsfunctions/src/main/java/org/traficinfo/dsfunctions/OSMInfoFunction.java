package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Daily report of unresolved counter-to-way mappings in Object Storage. */
public class OSMInfoFunction {
    private static final Logger LOG = LoggerFactory.getLogger(OSMInfoFunction.class);
    private static final String MAPPING_SUFFIX = "_OSMWayMapping.json";
    private ObjectStoreClient osmCacheOSClient;

    public OSMInfoFunction() { }

    OSMInfoFunction(ObjectStoreClient osmCacheOSClient) {
        this.osmCacheOSClient = osmCacheOSClient;
    }

    @FnConfiguration
    public void config(RuntimeContext ctx) {
        String namespace = ctx.getConfigurationByKey("NAMESPACE").orElse("");
        String bucket = ctx.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        osmCacheOSClient = new ObjectStoreClient(useResourcePrincipal, namespace, bucket);
    }

    public String handleRequest(String input) {
        int caches = 0;
        int failures = 0;
        try {
            for (String name : osmCacheOSClient.listResources()) {
                if (!name.endsWith(MAPPING_SUFFIX)) {
                    continue;
                }
                try {
                    String infoName = name.substring(0, name.length() - MAPPING_SUFFIX.length())
                            + "_OSMInfo.json";
                    OSMInfoProviderImpl provider = new OSMInfoProviderImpl(osmCacheOSClient,
                            new OpenStreetMapClient(), infoName, name);
                    provider.loadCache();
                    LOG.info("OSM way mapping cache {}: {} unknown entries out of {} entries",
                            name, provider.getUnknownWayMappingCount(), provider.getWayMappingCount());
                    LOG.info("OSM info cache {}: {} entries", infoName, provider.getWayInfoCount());
                    caches++;
                } catch (Exception failure) {
                    failures++;
                    LOG.warn("Could not report OSM way mapping cache {}", name, failure);
                }
            }
            LOG.info("OSM mapping report: {} caches processed, {} failures", caches, failures);
            return failures == 0 ? "OK" : "KO";
        } catch (Exception failure) {
            LOG.warn("Could not list OSM way mapping caches", failure);
            return "KO";
        }
    }
}
