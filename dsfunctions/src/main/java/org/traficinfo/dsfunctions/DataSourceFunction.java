package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** One deployed retrieval function; each invocation selects one bundled datasource. */
public class DataSourceFunction extends TraficInfoBaseFunction {
    private static final Logger LOG = LoggerFactory.getLogger(DataSourceFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DataSourceRegistry registry;

    public DataSourceFunction() { super("UNKNOWN"); }

    protected DataSourceRegistry loadRegistry() {
        return DataSourceRegistry.bundled();
    }

    protected HttpClient createHttpClient() {
        return new HttpClient();
    }

    @Override
    protected void configure(RuntimeContext context) {
        registry = loadRegistry();
    }

    @Override
    protected String processRequest(String input, Invocation invocation) {
        Request request;
        try {
            request = JSON.readValue(input == null ? "" : input, Request.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Expected {\"datasourceId\":\"...\"}", invalid);
        }
        if (request == null || request.datasourceId() == null || request.datasourceId().isBlank()) {
            throw new IllegalArgumentException("datasourceId is required");
        }
        invocation.datasource(request.datasourceId());
        var source = registry.source(request.datasourceId());
        try (var logging = LoggingContext.forSource("retrieval", request.datasourceId())) {
            try (HttpClient http = createHttpClient();
                 ObjectStoreClient storage = createObjectStoreClient(source.bucket())) {
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
