package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Manual publication of map features and casualty summaries for the configured accident datasets. */
public class AccidentPublicationFunction {
    private List<AccidentsConfig.Source> sources;
    private String namespace;
    private String outputBucket;
    private boolean principal;

    @FnConfiguration
    public void config(RuntimeContext context) {
        sources = loadSources();
        namespace = context.getConfigurationByKey("NAMESPACE").orElse("");
        outputBucket = context.getConfigurationByKey("BUCKETNAME_OUT").orElse("traficinfo");
        principal = Boolean.parseBoolean(context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
    }

    protected List<AccidentsConfig.Source> loadSources() { return AccidentsConfig.bundled(); }

    protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
        return new ObjectStoreClient(principal, namespace, bucket);
    }

    public String handleRequest(String input) {
        if (namespace == null) throw new IllegalStateException("Publication function is not configured");
        try (var outputStore = createObjectStoreClient(principal, namespace, outputBucket)) {
            for (var source : sources) {
                try (var logging = LoggingContext.forSource("accident-publication", source.id());
                     var accidentStore = createObjectStoreClient(principal, namespace, source.sourceBucket())) {
                    new AccidentPublicationPublisher(accidentStore, outputStore).publish(source.id());
                }
            }
            return "OK";
        } catch (IOException failure) {
            throw new UncheckedIOException("Accident publication failed", failure);
        }
    }
}
