package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** One deployed retrieval function; each invocation selects one bundled datasource. */
public class DataSourceFunction {
    private static final Logger LOG = LoggerFactory.getLogger(DataSourceFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DataSourceRegistry registry;
    private String namespace;
    private boolean useResourcePrincipal;

    public DataSourceFunction() {}

    protected DataSourceRegistry loadRegistry() {
        return DataSourceRegistry.bundled();
    }

    protected HttpClient createHttpClient() {
        return new HttpClient();
    }

    protected ObjectStoreClient createObjectStoreClient(boolean principal, String ns, String bucket) {
        return new ObjectStoreClient(principal, ns, bucket);
    }

    @FnConfiguration
    public void config(RuntimeContext context) {
        registry = loadRegistry();
        namespace = context.getConfigurationByKey("NAMESPACE").orElse("");
        useResourcePrincipal = Boolean.parseBoolean(context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
    }

    public String handleRequest(String input) {
        if (registry == null) throw new IllegalStateException("Datasource function has not been configured");
        Request request;
        try {
            request = JSON.readValue(input == null ? "" : input, Request.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Expected {\"datasourceId\":\"...\"}", invalid);
        }
        if (request == null || request.datasourceId() == null || request.datasourceId().isBlank()) {
            throw new IllegalArgumentException("datasourceId is required");
        }
        var source = registry.source(request.datasourceId());
        try (var logging = LoggingContext.forSource("retrieval", request.datasourceId())) {
            try (HttpClient http = createHttpClient();
                 ObjectStoreClient storage = createObjectStoreClient(useResourcePrincipal, namespace, source.bucket())) {
                new SourceObjectPublisher(http, storage).publish(source.settings());
                return "OK";
            } catch (IOException | RuntimeException failure) {
                LOG.error("Retrieval failed", failure);
                throw new IllegalStateException("Retrieval failed for " + request.datasourceId(), failure);
            }
        }
    }

    public record Request(String datasourceId) {}
}
