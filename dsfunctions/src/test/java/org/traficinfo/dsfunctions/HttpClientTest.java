package org.traficinfo.dsfunctions;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class HttpClientTest {

    @Test
    public void downloadsWithoutEtagOrLastModified() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] csv = "Commune;Année\nBÉZIERS;2025\n".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> content = new AtomicReference<>(csv);
        AtomicReference<String> conditionalHeader = new AtomicReference<>();
        server.createContext("/csv", exchange -> {
            conditionalHeader.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            byte[] bytes = content.get();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try (HttpClient client = new HttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/csv");
            OOSResourceMetadata metadata = new OOSResourceMetadata();
            String hash = "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(csv));
            try (HttpClient.EntityBody body = client.get(uri, null)) {
                assertEquals(hash, body.getEtag());
                metadata.setETag(body.getEtag());
                assertNull(body.getLastModified());
                assertArrayEquals(csv, body.getInputStream().readAllBytes());
            }
            assertNull(conditionalHeader.get());
            try (HttpClient.EntityBody body = client.get(uri, metadata.getETag())) {
                assertEquals(hash, body.getEtag());
                assertNull(body.getInputStream());
            }
            assertNull(conditionalHeader.get());
            try (HttpClient.EntityBody body = client.get(uri, hash)) {
                assertNull(body.getInputStream());
            }
            assertNull(conditionalHeader.get());
            byte[] changed = "new content".getBytes(StandardCharsets.UTF_8);
            content.set(changed);
            try (HttpClient.EntityBody body = client.get(uri, metadata.getETag())) {
                assertNotEquals(hash, body.getEtag());
                assertTrue(body.getEtag().startsWith("sha256:"));
                assertArrayEquals(changed, body.getInputStream().readAllBytes());
            }
            assertNull(conditionalHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void hashesEmptyResponseWithoutEtag() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/empty", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (HttpClient client = new HttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/empty");
            String etag;
            try (HttpClient.EntityBody body = client.get(uri, null)) {
                etag = body.getEtag();
                assertEquals("sha256:" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(new byte[0])), etag);
                assertEquals(-1, body.getInputStream().read());
            }
            try (HttpClient.EntityBody body = client.get(uri, etag)) {
                assertNull(body.getInputStream());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void retainsEtagAndSupportsConditionalRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> conditionalHeader = new AtomicReference<>();
        server.createContext("/csv", exchange -> {
            String etag = exchange.getRequestHeaders().getFirst("If-None-Match");
            conditionalHeader.set(etag);
            if (etag != null) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("ETag", "\"version-1\"");
            exchange.getResponseHeaders().set("Last-Modified", "Fri, 16 Oct 2026 10:00:00 GMT");
            exchange.sendResponseHeaders(200, 1);
            try (var output = exchange.getResponseBody()) {
                output.write('x');
            }
        });
        server.start();
        try (HttpClient client = new HttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/csv");
            try (HttpClient.EntityBody body = client.get(uri, null)) {
                assertEquals("version-1", body.getEtag());
                assertEquals(LocalDate.of(2026, 10, 16), body.getLastModified());
                assertEquals('x', body.getInputStream().read());
            }
            try (HttpClient.EntityBody body = client.get(uri, "\"version-1\"")) {
                assertEquals("\"version-1\"", conditionalHeader.get());
                assertEquals("\"version-1\"", body.getEtag());
                assertNull(body.getInputStream());
            }
            try (HttpClient.EntityBody body = client.get(uri, "sha256:" + "a".repeat(64))) {
                assertNull(conditionalHeader.get());
                assertEquals("version-1", body.getEtag());
                assertEquals('x', body.getInputStream().read());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsHttpErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (HttpClient client = new HttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/error");
            try {
                client.get(uri, null);
                fail("Expected HTTP failure");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("503"));
            }
        } finally {
            server.stop(0);
        }
    }
}
