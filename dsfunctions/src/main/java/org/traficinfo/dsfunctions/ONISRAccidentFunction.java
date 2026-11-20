package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Manual ONISR entry point importing the explicitly configured yearly publications. */
public class ONISRAccidentFunction extends TraficInfoBaseFunction {
    private static final Logger LOG = LoggerFactory.getLogger(ONISRAccidentFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String ACCIDENT_BUCKET = "onisrinfo";
    private String osmBucket;
    private boolean configured;

    public ONISRAccidentFunction() { super("ONISR"); }

    @Override
    protected void configure(RuntimeContext context) {
        osmBucket = context.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        configured = true;
    }

    @Override
    protected String processRequest(String input, Invocation invocation) {
        if (!configured) throw new IllegalStateException("Accident function has not been configured");
        String action = action(input);
        try (var logging = LoggingContext.forSource("accidents", "ONISR")) {
            try (var accidentStore = createObjectStoreClient(ACCIDENT_BUCKET);
                 var osmStore = createObjectStoreClient(osmBucket)) {
                var processor = new ONISRAccidentProcessor(accidentStore, osmStore);
                Map<String, ONISRAccidentProcessor.Statistics> result = action.equals("refresh")
                        ? processor.refresh() : processor.importYears(ONISRYearInputs.configured());
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
