package org.traficinfo.dsfunctions;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class HttpClient implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpClient.class);

    private final Client client;
    public HttpClient() {
        client = ClientBuilder.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.MINUTES)
                .build();
    }

    public EntityBody get(URI url, String etag) throws IOException {
        Invocation.Builder builder = client.target(url).request();
        if (etag != null) {
            builder.header("If-None-Match", etag);
        }
        final Response response;
        try {
            response = builder.get();
        } catch (jakarta.ws.rs.ProcessingException failure) {
            throw new IOException("Failed to get " + url.toASCIIString(), failure);
        }
        try {
            switch (response.getStatus()) {
                case 200:
                    return new EntityBody(response.getEntityTag() == null ? null
                            : response.getEntityTag().getValue(),
                            response.getLastModified(),
                            response.readEntity(InputStream.class), response);
                case 304:
                    LOG.info("entity did not change");
                    response.close();
                    return new EntityBody(etag, null, null);
                default:
                    throw new IOException("Failed to get " + url.toASCIIString()
                            + ": HTTP " + response.getStatus());
            }
        } catch (IOException | RuntimeException failure) {
            response.close();
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            throw new IOException("Failed to read " + url.toASCIIString(), failure);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    public static class EntityBody implements Closeable {
        private final String etag;
        private final LocalDate lastModified;
        private final InputStream inputStream;
        private final Response response;

        public EntityBody(String etag, Date lastModified, InputStream inputStream) {
            this(etag, lastModified, inputStream, null);
        }

        private EntityBody(String etag, Date lastModified, InputStream inputStream, Response response) {
            this.etag = etag;
            this.response = response;
            if (lastModified != null) {
                this.lastModified = lastModified.toInstant().atZone(ZoneId.of("GMT")).toLocalDate();
            } else {
                this.lastModified = null;
            }
            this.inputStream = inputStream;
        }

        public String getEtag() {
            return etag;
        }

        public LocalDate getLastModified() {
            return lastModified;
        }

        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public void close() throws IOException {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
    }
}
