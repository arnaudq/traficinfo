package org.traficinfo.dsfunctions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;

/** Downloads configured inputs in order, preserving the current Object if an archive fails. */
public final class SourceObjectPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(SourceObjectPublisher.class);
    private final HttpClient http;
    private final ObjectStoreClient storage;

    public SourceObjectPublisher(HttpClient http, ObjectStoreClient storage) {
        this.http = http;
        this.storage = storage;
    }

    public void publish(DataSourceSettings settings) {
        for (DataSourceSettings.Input input : settings.inputs()) {
            try {
                publish(settings, input);
            } catch (IOException failure) {
                throw new UncheckedIOException("Failed to publish " + input.objectName(), failure);
            }
        }
    }

    private void publish(DataSourceSettings settings, DataSourceSettings.Input input) throws IOException {
        OOSResourceMetadata previous = storage.getResourceMetadata(input.objectName());
        // A validator belongs to a URL and resource, not to the destination Object name.
        boolean sameSource = previous != null
                && input.url().equals(previous.getMetadata().get("source-url"))
                && settings.resourceId().equals(previous.getMetadata().get("resource-id"))
                && settings.dataSourceId().equals(previous.getMetadata().get("datasource-id"));
        try (HttpClient.EntityBody body = http.get(URI.create(input.url()),
                sameSource ? previous.getETag() : null)) {
            if (body.getInputStream() == null) {
                if (!sameSource) throw new IOException("Unchanged response without a matching stored source");
                LOG.info("No change for {}", input.objectName());
                return;
            }
            PushbackInputStream content = new PushbackInputStream(body.getInputStream(), 1);
            int first = content.read();
            if (first == -1) throw new IOException("The " + settings.dataSourceId() + " download is empty");
            content.unread(first);
            if (previous != null) {
                try (InputStream old = storage.getResource(input.objectName())) {
                    storage.putResource(input.archiveName(previous.getLastModified()),
                            input.contentType(), previous, old);
                }
            }
            Instant downloadedAt = Instant.now();
            OOSResourceMetadata metadata = new OOSResourceMetadata();
            metadata.setETag(body.getEtag());
            metadata.setLastModified(downloadedAt.atZone(ZoneOffset.UTC).toLocalDate());
            metadata.getMetadata().put("downloaded-at", downloadedAt.toString());
            metadata.getMetadata().put("source-url", input.url());
            metadata.getMetadata().put("resource-id", settings.resourceId());
            metadata.getMetadata().put("datasource-id", settings.dataSourceId());
            storage.putResource(input.objectName(), input.contentType(), metadata, content);
            LOG.info("Published {}", input.objectName());
        }
    }
}
