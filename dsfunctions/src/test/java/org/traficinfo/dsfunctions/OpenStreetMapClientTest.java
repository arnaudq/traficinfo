package org.traficinfo.dsfunctions;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class OpenStreetMapClientTest {
    @Test
    public void requestsWayByIdAndReturnsAllTags() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/api/0.6/way/370743659.json", exchange -> {
            requests.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body = ("{\"elements\":[{\"type\":\"way\",\"id\":370743659,"
                    + "\"tags\":{\"maxspeed\":\"30\",\"name\":\"Rue\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OpenStreetMapClient client = client(server);
            assertEquals(Map.of("maxspeed", "30", "name", "Rue"), client.getWayTags(370743659));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsErrorsAndWrongWayWithoutRetries() throws Exception {
        for (int status : new int[]{429, 500, 200}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger requests = new AtomicInteger();
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                byte[] body = "{\"elements\":[{\"type\":\"way\",\"id\":2}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                try {
                    client(server).getWayTags(1);
                    fail("Expected failed OSM request");
                } catch (IOException expected) {
                    assertEquals(1, requests.get());
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    public void reverseLookupRequestsRoadLevelAndCachesCoordinates() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/reverse", exchange -> {
            requests.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            assertTrue(query, query.contains("lat=43.2") && query.contains("lon=3.5"));
            assertTrue(query, query.contains("format=jsonv2&zoom=17&layer=address&extratags=1&namedetails=1"));
            assertTrue(exchange.getRequestHeaders().getFirst("User-Agent").contains("traficinfo"));
            byte[] body = "{\"osm_type\":\"way\",\"osm_id\":123,\"category\":\"highway\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            OpenStreetMapClient client = new OpenStreetMapClient(HttpClient.newHttpClient(), base, base, 50);
            long started = System.nanoTime();
            assertEquals(123, client.reverseLookup(43.25, 3.5).get("osm_id").asLong());
            assertEquals(123, client.reverseLookup(43.25, 3.5).get("osm_id").asLong());
            assertEquals(1, requests.get());
            assertEquals(123, client.reverseLookup(43.26, 3.5).get("osm_id").asLong());
            assertEquals(2, requests.get());
            assertTrue("Distinct requests must be spaced", System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(50));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void reverseLookupRejectsHttpErrorsAndMalformedResponsesAndCachesNotFound() throws Exception {
        for (int status : new int[]{429, 503, 200, 404}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger requests = new AtomicInteger();
            server.createContext("/reverse", exchange -> {
                requests.incrementAndGet();
                byte[] body = (status == 404 ? "{\"error\":\"Unable to geocode\"}" : "invalid")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
                OpenStreetMapClient client = new OpenStreetMapClient(HttpClient.newHttpClient(), base, base, 0);
                if (status == 404) {
                    assertTrue(client.reverseLookup(43, 3).has("error"));
                    assertTrue(client.reverseLookup(43, 3).has("error"));
                } else {
                    try {
                        client.reverseLookup(43, 3);
                        fail("Expected failed reverse request");
                    } catch (IOException expected) { }
                }
                assertEquals(1, requests.get());
            } finally {
                server.stop(0);
            }
        }
    }

    private static OpenStreetMapClient client(HttpServer server) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new OpenStreetMapClient(HttpClient.newHttpClient(), base.resolve("api/0.6/"), base, 0);
    }
}
