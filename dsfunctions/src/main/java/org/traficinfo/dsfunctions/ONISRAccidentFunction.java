package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Manual ONISR entry point, initially importing only the fixed 2025 characteristics CSV. */
public class ONISRAccidentFunction {
    private static final Logger LOG = LoggerFactory.getLogger(ONISRAccidentFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String ACCIDENT_BUCKET = "onisrinfo";
    private static final String CSV_OBJECT = "caract-2025.csv";
    private static final int YEAR = 2025;
    private String namespace;
    private String osmBucket;
    private boolean principal;
    private boolean configured;

    protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
        return new ObjectStoreClient(principal, namespace, bucket);
    }

    @FnConfiguration
    public void config(RuntimeContext context) {
        namespace = context.getConfigurationByKey("NAMESPACE").orElse("");
        osmBucket = context.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        principal = Boolean.parseBoolean(context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        configured = true;
    }

    /** Empty input or {} imports 2025; {"action":"refresh"} rebuilds stored accident output only. */
    public String handleRequest(String input) {
        if (!configured) throw new IllegalStateException("Accident function has not been configured");
        String action = action(input);
        try (var logging = LoggingContext.forSource("accidents", "ONISR")) {
            try (var accidentStore = createObjectStoreClient(principal, namespace, ACCIDENT_BUCKET);
                 var osmStore = createObjectStoreClient(principal, namespace, osmBucket)) {
                var processor = new ONISRAccidentProcessor(accidentStore, osmStore);
                Map<String, ONISRAccidentProcessor.Statistics> result = action.equals("refresh")
                        ? processor.refresh() : processor.importCsv(CSV_OBJECT, YEAR);
                result.forEach((region, statistics) -> LOG.info("ONISR regional ID {}: {}", region, statistics));
                return "OK";
            } catch (IOException failure) {
                LOG.error("ONISR accident processing failed", failure);
                throw new UncheckedIOException("ONISR accident processing failed", failure);
            } catch (RuntimeException failure) {
                LOG.error("ONISR accident processing failed", failure);
                throw failure;
            }
        }
    }

    private static String action(String input) {
        if (input == null || input.isBlank()) return "import";
        try {
            Request request = JSON.readValue(input, Request.class);
            if (request == null) throw new IllegalArgumentException("Expected an accident request object");
            String action = request.action() == null ? "import" : request.action();
            if (!action.equals("import") && !action.equals("refresh")) {
                throw new IllegalArgumentException("Expected action import or refresh");
            }
            return action;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid accident request", invalid);
        }
    }

    public record Request(String action) {}
}
