package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Shared, explicitly invoked source-to-yearly-publication handler. */
public class PreprocessingFunction {
    private PreprocessingSettings settings;
    private ObjectStoreClient storage;

    public PreprocessingFunction() {}

    PreprocessingFunction(PreprocessingSettings settings, ObjectStoreClient storage) {
        this.settings = settings;
        this.storage = storage;
    }

    protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
        return new ObjectStoreClient(principal, namespace, bucket);
    }

    @FnConfiguration
    public void config(RuntimeContext context) {
        settings = PreprocessingSettings.from(context);
        String bucket = context.getConfigurationByKey("BUCKETNAME").filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing BUCKETNAME"));
        storage = createObjectStoreClient(Boolean.parseBoolean(context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true")),
                context.getConfigurationByKey("NAMESPACE").orElse(""), bucket);
    }

    public String handleRequest(String input) {
        try (var logging = LoggingContext.forSource("preprocess", settings == null ? null : settings.source().dataSourceId())) {
            if (settings == null || storage == null) throw new IllegalStateException("Preprocessing function has not been configured");
            try {
                PreprocessingPublisher publisher = new PreprocessingPublisher(storage);
                if (input == null || input.isBlank()) {
                    publisher.publish(settings);
                } else {
                    ArchiveRequest request = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readValue(input, ArchiveRequest.class);
                    if (request == null || request.archive() == null) {
                        throw new IllegalArgumentException("Expected an archive preprocessing request");
                    }
                    publisher.publishArchive(request.archive(), settings);
                }
                return "OK";
            } catch (IOException failure) {
                throw new UncheckedIOException("Preprocessing failed for " + settings.source().dataSourceId(), failure);
            }
        }
    }

    /** Invocation settings override the dataset for this import only, never runtime configuration. */
    public record ArchiveRequest(PreprocessingSettings archive) {}
}
