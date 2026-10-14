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

    private static OpenStreetMapClient client(HttpServer server) {
        return new OpenStreetMapClient(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/0.6/"));
    }
}
