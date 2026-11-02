package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Shared retrieval of configured source inputs. */
public abstract class AbstractDataSourceFunction {
    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_BUCKET_NAME = "BUCKETNAME";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractDataSourceFunction.class);

    private final String dataSourceId;
    private HttpClient httpClient;
    private ObjectStoreClient objectStore;
    private DataSourceSettings settings;

    protected AbstractDataSourceFunction(String dataSourceId) {
        this.dataSourceId = Objects.requireNonNull(dataSourceId);
    }

    protected AbstractDataSourceFunction(String dataSourceId, DataSourceSettings settings,
                                         HttpClient httpClient, ObjectStoreClient objectStore) {
        this(dataSourceId);
        if (!dataSourceId.equals(settings.dataSourceId())) {
            throw new IllegalArgumentException("Datasource ID does not match handler: " + settings.dataSourceId());
        }
        this.settings = settings;
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
        settings = DataSourceSettings.from(ctx, dataSourceId);
        String namespace = ctx.getConfigurationByKey(CONFIG_KEY_NAMESPACE).orElse("");
        String bucketName = ctx.getConfigurationByKey(CONFIG_KEY_BUCKET_NAME)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing " + CONFIG_KEY_BUCKET_NAME));
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey(CONFIG_KEY_USE_RESOURCE_PRINCIPAL).orElse("true"));
        httpClient = createHttpClient();
        objectStore = createObjectStoreClient(useResourcePrincipal, namespace, bucketName);
        LOG.info("Data source {} configured. Namespace = {}, Bucketname = {}", dataSourceId, namespace, bucketName);
    }

    public final String handleRequest(String input) {
        try (var logging = LoggingContext.forSource("retrieval", dataSourceId)) {
            if (settings == null || httpClient == null || objectStore == null) {
                throw new IllegalStateException("Datasource function has not been configured");
            }
            new SourceObjectPublisher(httpClient, objectStore).publish(settings);
            return "OK";
        }
    }
}
