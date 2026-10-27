package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/** Shared download, change detection, archival and publication for CSV-only sources. */
public abstract class AbstractCsvDataSourceFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_BUCKET_NAME = "BUCKETNAME";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractCsvDataSourceFunction.class);

    private final CsvDataSource source;

    private HttpClient httpClient;
    private ObjectStoreClient objectStore;
    private DataSourceSettings settings;

    protected AbstractCsvDataSourceFunction(CsvDataSource source) {
        this.source = Objects.requireNonNull(source);
        settings = new DataSourceSettings(source.getDataSourceId(), "current", List.of(
                new DataSourceSettings.Input(source.getCsvUri().toString(), source.getCsvObjectName(), "text/csv")));
        LOG.info("Data source {} function booting", source.getDataSourceId());
    }

    protected AbstractCsvDataSourceFunction(CsvDataSource source, HttpClient httpClient,
                                          ObjectStoreClient objectStore) {
        this(source);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.objectStore = Objects.requireNonNull(objectStore);
    }

    protected ObjectStoreClient createObjectStoreClient(boolean useResourcePrincipal,
                                                       String namespace, String bucket) {
        return new ObjectStoreClient(useResourcePrincipal, namespace, bucket);
    }

    protected HttpClient createHttpClient() {
        return new HttpClient();
    }

    @FnConfiguration
    public final void config(RuntimeContext ctx) {
        settings = DataSourceSettings.from(ctx, settings);
        String namespace = ctx.getConfigurationByKey(CONFIG_KEY_NAMESPACE).orElse("");
        String bucketName = ctx.getConfigurationByKey(CONFIG_KEY_BUCKET_NAME).orElse(source.getDefaultInputBucket());
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey(CONFIG_KEY_USE_RESOURCE_PRINCIPAL).orElse("true"));
        httpClient = createHttpClient();
        objectStore = createObjectStoreClient(useResourcePrincipal, namespace, bucketName);
        LOG.info("Data source {} configured. Namespace = {}, Bucketname = {}",
                source.getDataSourceId(), namespace, bucketName);
    }

    public final String handleRequest(String input) {
        new SourceObjectPublisher(httpClient, objectStore).publish(settings);
        return "OK";
    }
}
