package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.RuntimeContext;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * OCI Function entry point for publishing one datasource's complete canonical history.
 *
 * <p>Accepts a JSON {@link Request}, resolves its private input bucket from the bundled
 * datasource registry, and delegates to {@link HistoryPublisher}. The public output bucket
 * is configured by {@code BUCKETNAME_OUT}, defaulting to {@code traficinfo}. Storage clients
 * are opened and closed per invocation; {@link TraficInfoBaseFunction} supplies configuration
 * and the shared failure-reporting boundary. Invocation is explicit, without a schedule or event rule.
 */
public class HistoryPublicationFunction extends TraficInfoBaseFunction {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DataSourceRegistry registry;
    private String outputBucket;

    public HistoryPublicationFunction() { super("UNKNOWN"); }

    protected DataSourceRegistry loadRegistry() { return DataSourceRegistry.bundled(); }

    @Override
    protected void configure(RuntimeContext context) {
        registry = loadRegistry();
        outputBucket = context.getConfigurationByKey("BUCKETNAME_OUT").orElse("traficinfo");
    }

    @Override
    protected String processRequest(String input, Invocation invocation) {
        Request request;
        try {
            request = JSON.readValue(input == null ? "" : input, Request.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Expected a history publication request with datasourceId", invalid);
        }
        if (request == null || request.datasourceId() == null || request.datasourceId().isBlank()) {
            throw new IllegalArgumentException("datasourceId is required");
        }
        invocation.datasource(request.datasourceId());
        var source = registry.source(request.datasourceId());
        PreprocessingSource.forId(request.datasourceId());
        try (var logging = LoggingContext.forSource("history", request.datasourceId());
             var inputs = createObjectStoreClient(source.bucket());
             var outputs = createObjectStoreClient(outputBucket)) {
            new HistoryPublisher(inputs, outputs).publish(request.datasourceId());
            return "OK";
        } catch (IOException failure) {
            throw new UncheckedIOException("History publication failed for " + request.datasourceId(), failure);
        }
    }

    /**
     * Explicit export selection; storage locations are resolved from configuration, not the request.
     *
     * @param datasourceId configured datasource whose published history should be exported
     */
    public record Request(String datasourceId) {}
}
