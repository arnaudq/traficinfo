package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.time.Instant;
import java.time.ZoneOffset;

/** Shared download, change detection, archival and publication for CSV-only sources. */
public abstract class AbstractCsvDataSourceFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_BUCKET_NAME = "BUCKETNAME";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractCsvDataSourceFunction.class);
    private static final String CONTENT_TYPE = "text/csv";

    private final CsvDataSource source;

    private HttpClient httpClient;
    private ObjectStoreClient objectStore;

    protected AbstractCsvDataSourceFunction(CsvDataSource source) {
        this.source = Objects.requireNonNull(source);
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
        try {
            storeCsv();
            return "OK";
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to publish " + source.getCsvObjectName(), failure);
        }
    }

    private void storeCsv() throws IOException {
        OOSResourceMetadata previous = objectStore.getResourceMetadata(source.getCsvObjectName());
        try (HttpClient.EntityBody body = httpClient.get(source.getCsvUri(),
                previous == null ? null : previous.getETag())) {
            if (body.getInputStream() == null) {
                LOG.info("No change for {}", source.getCsvObjectName());
                return;
            }
            PushbackInputStream content = new PushbackInputStream(body.getInputStream(), 1);
            int first = content.read();
            if (first == -1) {
                throw new IOException("The " + source.getDataSourceId() + " CSV download is empty");
            }
            content.unread(first);

            if (previous != null) {
                String archiveName = String.format(source.getCsvArchiveNamePattern(), previous.getLastModified());
                // Copy rather than rename: a failed archive/upload leaves the current object readable.
                try (InputStream oldCsv = objectStore.getResource(source.getCsvObjectName())) {
                    objectStore.putResource(archiveName, CONTENT_TYPE, previous, oldCsv);
                }
            }

            OOSResourceMetadata metadata = new OOSResourceMetadata();
            Instant downloadedAt = Instant.now();
            metadata.setLastModified(downloadedAt.atZone(ZoneOffset.UTC).toLocalDate());
            metadata.setETag(body.getEtag());
            metadata.getMetadata().put("downloaded-at", downloadedAt.toString());
            objectStore.putResource(source.getCsvObjectName(), CONTENT_TYPE, metadata, content);
            LOG.info("Published {}", source.getCsvObjectName());
        }
    }
}
