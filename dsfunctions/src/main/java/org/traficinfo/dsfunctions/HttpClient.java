package org.traficinfo.dsfunctions;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

public class HttpClient implements Closeable {

    private static final String HASH_PREFIX = "sha256:";
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
        if (etag != null && !unquote(etag).startsWith(HASH_PREFIX)) {
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
                    EntityTag entityTag = usableEntityTag(response);
                    if (entityTag == null) {
                        return hashResponse(response, etag);
                    }
                    return new EntityBody(entityTag.getValue(),
                            response.getLastModified(),
                            response.readEntity(InputStream.class), response, null);
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

    private static EntityTag usableEntityTag(Response response) {
        try {
            return response.getEntityTag();
        } catch (IllegalArgumentException invalid) {
            // Some publishers send an unquoted ETag. Treat it like a missing validator
            // rather than losing a successful download or forwarding a malformed tag.
            LOG.warn("Invalid HTTP ETag; using SHA-256 content change detection");
            return null;
        }
    }

    private static EntityBody hashResponse(Response response, String previousEtag) throws IOException {
        Path file = Files.createTempFile("httpclient-", ".body");
        boolean retained = false;
        try {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
            Date lastModified = response.getLastModified();
            try (InputStream input = response.hasEntity()
                    ? response.readEntity(InputStream.class) : InputStream.nullInputStream();
                 OutputStream output = new DigestOutputStream(Files.newOutputStream(file), digest)) {
                input.transferTo(output);
            }
            response.close();
            String etag = HASH_PREFIX + HexFormat.of().formatHex(digest.digest());
            if (etag.equals(unquote(previousEtag))) {
                LOG.info("entity content did not change");
                return new EntityBody(etag, lastModified, null);
            }
            EntityBody body = new EntityBody(etag, lastModified, Files.newInputStream(file), null, file);
            retained = true;
            return body;
        } finally {
            if (!retained) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static String unquote(String etag) {
        return etag != null && etag.startsWith("\"") && etag.endsWith("\"") && etag.length() >= 2
                ? etag.substring(1, etag.length() - 1) : etag;
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
        private final Path temporaryFile;

        public EntityBody(String etag, Date lastModified, InputStream inputStream) {
            this(etag, lastModified, inputStream, null, null);
        }

        private EntityBody(String etag, Date lastModified, InputStream inputStream,
                           Response response, Path temporaryFile) {
            this.etag = etag;
            this.response = response;
            this.temporaryFile = temporaryFile;
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
                try {
                    if (response != null) {
                        response.close();
                    }
                } finally {
                    if (temporaryFile != null) {
                        Files.deleteIfExists(temporaryFile);
                    }
                }
            }
        }
    }
}
