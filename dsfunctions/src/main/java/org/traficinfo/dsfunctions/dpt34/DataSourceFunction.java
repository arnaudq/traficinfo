package org.traficinfo.dsfunctions.dpt34;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.dsfunctions.OOSResourceMetadata;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.HttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/** Downloads the Hérault CSV and publishes it only when its content changes. */
public class DataSourceFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_BUCKET_NAME = "BUCKETNAME";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";
    public static final String DEFAULT_BUCKET_NAME = "dsdpt34";
    public static final String CSV_OBJECT_NAME = "DPT34_Comptage.csv";
    public static final String CSV_OBJECT_ARCHIVE_NAME = "DPT34_Comptage_%s.csv";
    public static final String CONTENT_SHA256 = "content-sha256";
    public static final URI CSV_URI = URI.create(
            "https://www.herault-data.fr/api/explore/v2.1/catalog/datasets/"
                    + "trafic-de-l-herault-donnees-de-comptage/exports/csv?use_labels=true&delimiter=%3B");

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceFunction.class);
    private static final String CONTENT_TYPE = "text/csv";

    private HttpClient httpClient;
    private ObjectStoreClient objectStore;

    public DataSourceFunction() {
        LOG.info("DataSourceDPT34 Function booting");
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
        LOG.info("DataSourceDPT34 configured. Namespace = {}, Bucketname = {}", namespace, bucketName);
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
        Path csv = Files.createTempFile("dpt34ds-", ".csv");
        try {
            MessageDigest digest = sha256();
            // The endpoint has no ETag: always download, hashing uncompressed bytes.
            try (HttpClient.EntityBody body = httpClient.get(CSV_URI, null)) {
                if (body.getInputStream() == null) {
                    throw new IOException("The DPT34 CSV download has no body");
                }
                try (OutputStream output = new DigestOutputStream(Files.newOutputStream(csv), digest)) {
                    if (body.getInputStream().transferTo(output) == 0) {
                        throw new IOException("The DPT34 CSV download is empty");
                    }
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            if (previous != null && hash.equals(previous.getMetadata().get(CONTENT_SHA256))) {
                LOG.info("No change for {}", CSV_OBJECT_NAME);
                return;
            }

            if (previous != null) {
                String archiveName = String.format(CSV_OBJECT_ARCHIVE_NAME, archiveDate(previous));
                // Copy rather than rename: a failed archive/upload leaves the current object readable.
                try (InputStream oldCsv = objectStore.getResource(CSV_OBJECT_NAME)) {
                    objectStore.putResource(archiveName, CONTENT_TYPE, previous, oldCsv);
                }
            }

            OOSResourceMetadata metadata = new OOSResourceMetadata();
            Instant downloadedAt = Instant.now();
            metadata.setLastModified(downloadedAt.atZone(ZoneOffset.UTC).toLocalDate());
            metadata.getMetadata().put(CONTENT_SHA256, hash);
            metadata.getMetadata().put("downloaded-at", downloadedAt.toString());
            try (InputStream content = Files.newInputStream(csv)) {
                objectStore.putResource(CSV_OBJECT_NAME, CONTENT_TYPE, metadata, content);
            }
            LOG.info("Published {} with SHA-256 {}", CSV_OBJECT_NAME, hash);
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    private static String archiveDate(OOSResourceMetadata metadata) {
        if (metadata.getLastModified() != null) {
            return metadata.getLastModified();
        }
        // Older DPT34 objects stored only the download timestamp.
        String downloadedAt = metadata.getMetadata().get("downloaded-at");
        Instant date = downloadedAt == null ? Instant.now() : Instant.parse(downloadedAt);
        return date.atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

}
