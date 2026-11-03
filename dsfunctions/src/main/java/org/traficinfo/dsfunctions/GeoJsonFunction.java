package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/** One deployed GeoJSON function, selected by datasource ID or manifest event. */
public class GeoJsonFunction {
    private static final Logger LOG = LoggerFactory.getLogger(GeoJsonFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DataSourceRegistry registry;
    private String namespace;
    private String outputBucket;
    private String osmBucket;
    private boolean principal;
    private MetricsPublisher metrics;

    protected DataSourceRegistry loadRegistry() { return DataSourceRegistry.bundled(); }

    protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
        return new ObjectStoreClient(principal, namespace, bucket);
    }

    protected OpenStreetMapClient createOpenStreetMapClient() { return new OpenStreetMapClient(); }

    protected void publishMetrics(List<MetricSample> samples) {
        metrics.publish(samples);
    }

    @FnConfiguration
    public void config(RuntimeContext context) {
        registry = loadRegistry();
        namespace = context.getConfigurationByKey("NAMESPACE").orElse("");
        outputBucket = context.getConfigurationByKey("BUCKETNAME_OUT").orElse("traficinfo");
        osmBucket = context.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        principal = Boolean.parseBoolean(context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        metrics = FunctionMetrics.configured(context);
    }

    public String handleRequest(String input) {
        if (registry == null) throw new IllegalStateException("GeoJSON function has not been configured");
        Request request;
        try {
            JsonNode body = JSON.readTree(input == null ? "" : input);
            if (body == null || !body.isObject()) throw new IllegalArgumentException("Expected a GeoJSON request");
            request = body.has("eventType") ? eventRequest(body) : JSON.treeToValue(body, Request.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid GeoJSON request", invalid);
        }
        if (request == null || request.datasourceId() == null || request.datasourceId().isBlank()) {
            throw new IllegalArgumentException("datasourceId is required");
        }
        var source = registry.geojson(request.datasourceId());
        String inputBucket = registry.source(request.datasourceId()).bucket();
        try (var logging = LoggingContext.forSource("geojson", request.datasourceId())) {
            try (var inputs = createObjectStoreClient(principal, namespace, inputBucket);
                 var outputs = createObjectStoreClient(principal, namespace, outputBucket);
                 var osm = createObjectStoreClient(principal, namespace, osmBucket);
                 var osmClient = createOpenStreetMapClient()) {
                var provider = new OSMInfoProviderImpl(osm, osmClient,
                        source.getOsmCacheObjectName(), source.getOsmWayMappingObjectName());
                return new GeoJsonPublisher(source, inputs, outputs, provider, this::publishMetrics).publish();
            } catch (Exception failure) {
                LOG.error("GeoJSON generation failed", failure);
                throw new IllegalStateException("GeoJSON generation failed for " + request.datasourceId(), failure);
            }
        }
    }

    private Request eventRequest(JsonNode event) {
        String type = event.path("eventType").asText();
        if (!type.equals("com.oraclecloud.objectstorage.createobject")
                && !type.equals("com.oraclecloud.objectstorage.updateobject")) {
            throw new IllegalArgumentException("Unsupported GeoJSON event type");
        }
        String name = event.path("data").path("resourceName").asText();
        String suffix = "_Manifest.json";
        if (!name.endsWith(suffix)) throw new IllegalArgumentException("Expected a datasource manifest");
        String datasourceId = name.substring(0, name.length() - suffix.length());
        var details = event.path("data").path("additionalDetails");
        if (!registry.source(datasourceId).bucket().equals(details.path("bucketName").asText())
                || (!namespace.isBlank() && !namespace.equals(details.path("namespace").asText()))) {
            throw new IllegalArgumentException("Event does not match configured datasource storage");
        }
        return new Request(datasourceId);
    }

    public record Request(String datasourceId) {}
}
