package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.RuntimeContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Manual publication of map features and casualty summaries for the configured accident datasets. */
public class AccidentPublicationFunction extends TraficInfoBaseFunction {
    private List<AccidentsConfig.Source> sources;
    private String outputBucket;

    public AccidentPublicationFunction() { super("ALL"); }

    @Override
    protected void configure(RuntimeContext context) {
        sources = loadSources();
        outputBucket = context.getConfigurationByKey("BUCKETNAME_OUT").orElse("traficinfo");
    }

    protected List<AccidentsConfig.Source> loadSources() { return AccidentsConfig.bundled(); }

    @Override
    protected String processRequest(String input, Invocation invocation) {
        if (namespace() == null) throw new IllegalStateException("Publication function is not configured");
        try (var outputStore = createObjectStoreClient(outputBucket)) {
            for (var source : sources) {
                invocation.datasource(source.id());
                try (var logging = LoggingContext.forSource("accident-publication", source.id());
                     var accidentStore = createObjectStoreClient(source.sourceBucket())) {
                    new AccidentPublicationPublisher(accidentStore, outputStore).publish(source.id());
                }
            }
            return "OK";
        } catch (IOException failure) {
            throw new UncheckedIOException("Accident publication failed", failure);
        }
    }
}
