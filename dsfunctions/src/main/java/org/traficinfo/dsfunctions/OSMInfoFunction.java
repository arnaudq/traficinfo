package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/** Daily report of unresolved counter-to-way mappings in Object Storage. */
public class OSMInfoFunction {
    private static final Logger LOG = LoggerFactory.getLogger(OSMInfoFunction.class);
    private static final String MAPPING_SUFFIX = "_OSMWayMapping.json";
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
                try (InputStream stream = osmCacheOSClient.getResource(name)) {
                    JsonNode mappings = mapper.readTree(stream);
                    int unknown = countUnknownEntries(mappings);
                    LOG.info("OSM way mapping cache {}: {} unknown entries out of {} entries",
                            name, unknown, mappings.size());
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

    static int countUnknownEntries(JsonNode mappings) throws IOException {
        if (mappings == null || !mappings.isObject()) {
            throw new IOException("OSM way mapping cache must be a JSON object");
        }
        int unknown = 0;
        for (JsonNode mapping : mappings) {
            JsonNode wayId = mapping.path("osmWayId");
            if (!wayId.isIntegralNumber() || !wayId.canConvertToLong() || wayId.asLong() <= 0) {
                unknown++;
            }
        }
        return unknown;
    }
}
