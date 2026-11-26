package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.RuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;

/** One preprocessing entry point for input events and explicit datasource requests. */
public class PreprocessingFunction extends TraficInfoBaseFunction {
    private static final Logger LOG = LoggerFactory.getLogger(PreprocessingFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DataSourceRegistry registry;

    public PreprocessingFunction() { super("UNKNOWN"); }

    protected DataSourceRegistry loadRegistry() { return DataSourceRegistry.bundled(); }

    @Override
    protected void configure(RuntimeContext context) {
        registry = loadRegistry();
    }

    @Override
    protected String processRequest(String input, Invocation invocation) {
        Request request;
        try {
            JsonNode body = JSON.readTree(input == null ? "" : input);
            if (body == null || !body.isObject()) throw new IllegalArgumentException("Expected a preprocessing request");
            request = body.has("eventType") ? eventRequest(body) : JSON.treeToValue(body, Request.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid preprocessing request", invalid);
        }
        if (request == null || request.datasourceId() == null || request.datasourceId().isBlank()) {
            throw new IllegalArgumentException("datasourceId is required");
        }
        invocation.datasource(request.datasourceId());
        var source = registry.source(request.datasourceId());
        var active = registry.preprocessing(request.datasourceId(), null);
        var archive = request.datasetId() == null ? null : registry.preprocessing(request.datasourceId(), request.datasetId());
        try (var logging = LoggingContext.forSource("preprocess", request.datasourceId())) {
            try (ObjectStore storage = createObjectStoreClient(source.bucket())) {
                var publisher = new PreprocessingPublisher(storage);
                if (archive != null) publisher.publishArchive(archive, active);
                else publisher.publish(active);
                return "OK";
            } catch (IOException failure) {
                LOG.error("Preprocessing failed", failure);
                throw new UncheckedIOException("Preprocessing failed for " + request.datasourceId(), failure);
            } catch (RuntimeException failure) {
                LOG.error("Preprocessing failed", failure);
                throw failure;
            }
        }
    }

    private Request eventRequest(JsonNode body) {
        String type = body.path("eventType").asText();
        if (!type.equals("com.oraclecloud.objectstorage.createobject") && !type.equals("com.oraclecloud.objectstorage.updateobject")) {
            throw new IllegalArgumentException("Unsupported preprocessing event type");
        }
        String name = body.path("data").path("resourceName").asText();
        var details = body.path("data").path("additionalDetails");
        if (!namespace().isBlank() && !namespace().equals(details.path("namespace").asText())) {
            throw new IllegalArgumentException("Event does not match configured namespace");
        }
        String id = registry.datasourceForInput(details.path("bucketName").asText(), name);
        return new Request(id, null);
    }

    public record Request(String datasourceId, String datasetId) {}
}
