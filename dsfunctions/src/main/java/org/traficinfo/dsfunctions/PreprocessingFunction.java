package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
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
        if (settings == null || storage == null) throw new IllegalStateException("Preprocessing function has not been configured");
        try {
            new PreprocessingPublisher(storage).publish(settings);
            return "OK";
        } catch (IOException failure) {
            throw new UncheckedIOException("Preprocessing failed for " + settings.source().dataSourceId(), failure);
        }
    }
}
