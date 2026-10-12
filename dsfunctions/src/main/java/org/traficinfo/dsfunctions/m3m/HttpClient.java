package org.traficinfo.dsfunctions.m3m;

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

public class HttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpClient.class);

    private final Client client;
    public HttpClient() {
        client = ClientBuilder.newClient();
    }

    public EntityBody get(URI url, String etag) throws IOException {
        Invocation.Builder builder = client.target(url).request();
        if (etag != null) {
            builder.header("If-None-Match", etag);
        }
        Response response = builder.get();
        switch (response.getStatus()) {
            case 200:
                return new EntityBody(response.getEntityTag().getValue(),
                        response.getLastModified(),
                        response.readEntity(InputStream.class));
            case 304:
                LOG.info("entity did not change");
                return new EntityBody(etag, null,null);
            default:
                LOG.info("Failed to download {} - status = {}", url.toASCIIString(), response.getStatusInfo().toString());
                throw new IOException("failed to get " + url.toASCIIString() + ":" + response.getStatusInfo().toString());
        }
    }

    public static class EntityBody implements Closeable {
        private final String etag;
        private final LocalDate lastModified;
        private final InputStream inputStream;

        private EntityBody(String etag, Date lastModified, InputStream inputStream) {
            this.etag = etag;
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
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }
}
