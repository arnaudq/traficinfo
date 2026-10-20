package org.traficinfo.dsfunctions.dpt30;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.dsfunctions.OOSResourceMetadata;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.HttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;

/** Downloads the Gard CSV and publishes it only when its content changes. */
public class DataSourceFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_BUCKET_NAME = "BUCKETNAME";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";
    public static final String DEFAULT_BUCKET_NAME = "dsdpt30";
    public static final String CSV_OBJECT_NAME = "DPT30_Comptage.csv";
    public static final String CSV_OBJECT_ARCHIVE_NAME = "DPT30_Comptage_%s.csv";
    public static final URI CSV_URI = URI.create(
            "https://www.data.gouv.fr/api/1/datasets/r/69584212-8ff0-40d3-b82d-2d5d2890bff7");

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceFunction.class);
    private static final String CONTENT_TYPE = "text/csv";

    private HttpClient httpClient;
    private ObjectStoreClient objectStore;

    public DataSourceFunction() {
        LOG.info("DataSourceDPT30 Function booting");
    }

    DataSourceFunction(HttpClient httpClient, ObjectStoreClient objectStore) {
        this.httpClient = httpClient;
        this.objectStore = objectStore;
    }

    @FnConfiguration
    public void config(RuntimeContext ctx) {
        String namespace = ctx.getConfigurationByKey(CONFIG_KEY_NAMESPACE).orElse("");
        String bucketName = ctx.getConfigurationByKey(CONFIG_KEY_BUCKET_NAME).orElse(DEFAULT_BUCKET_NAME);
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey(CONFIG_KEY_USE_RESOURCE_PRINCIPAL).orElse("true"));
        httpClient = new HttpClient();
        objectStore = new ObjectStoreClient(useResourcePrincipal, namespace, bucketName);
        LOG.info("DataSourceDPT30 configured. Namespace = {}, Bucketname = {}", namespace, bucketName);
    }

    public String handleRequest(String input) {
        try {
            storeCsv();
            return "OK";
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to publish " + CSV_OBJECT_NAME, failure);
        }
    }

    private void storeCsv() throws IOException {
        OOSResourceMetadata previous = objectStore.getResourceMetadata(CSV_OBJECT_NAME);
        try (HttpClient.EntityBody body = httpClient.get(CSV_URI,
                previous == null ? null : previous.getETag())) {
            if (body.getInputStream() == null) {
                LOG.info("No change for {}", CSV_OBJECT_NAME);
                return;
            }
            PushbackInputStream content = new PushbackInputStream(body.getInputStream(), 1);
            int first = content.read();
            if (first == -1) {
                throw new IOException("The DPT30 CSV download is empty");
            }
            content.unread(first);

            if (previous != null) {
                String archiveName = String.format(CSV_OBJECT_ARCHIVE_NAME, previous.getLastModified());
                // Copy rather than rename: a failed archive/upload leaves the current object readable.
                try (InputStream oldCsv = objectStore.getResource(CSV_OBJECT_NAME)) {
                    objectStore.putResource(archiveName, CONTENT_TYPE, previous, oldCsv);
                }
            }

            OOSResourceMetadata metadata = new OOSResourceMetadata();
            Instant downloadedAt = Instant.now();
            metadata.setLastModified(downloadedAt.atZone(ZoneOffset.UTC).toLocalDate());
            metadata.setETag(body.getEtag());
            metadata.getMetadata().put("downloaded-at", downloadedAt.toString());
            objectStore.putResource(CSV_OBJECT_NAME, CONTENT_TYPE, metadata, content);
            LOG.info("Published {}", CSV_OBJECT_NAME);
        }
    }
}
