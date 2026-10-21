package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/** OSM way-tag and rate-limited reverse requests, without redirects or retries. */
public class OpenStreetMapClient {
    private final HttpClient client;
    private final URI apiBase;
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI reverseApiBase;
    private final long reverseIntervalNanos;
    private Long lastReverseRequest;
    private final Map<String, JsonNode> reverseResults = new HashMap<>();

    public OpenStreetMapClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                URI.create("https://api.openstreetmap.org/api/0.6/"),
                URI.create("https://nominatim.openstreetmap.org/"), 15_000);
    }

    OpenStreetMapClient(HttpClient client, URI apiBase, URI reverseApiBase, long reverseIntervalMillis) {
        this.client = client;
        this.apiBase = apiBase;
        this.reverseApiBase = reverseApiBase;
        this.reverseIntervalNanos = TimeUnit.MILLISECONDS.toNanos(reverseIntervalMillis);
    }

    /** Public Nominatim scheduled jobs are limited to four requests per minute. */
    public synchronized JsonNode reverseLookup(double latitude, double longitude)
            throws IOException, InterruptedException {
        String query = "lat=" + latitude + "&lon=" + longitude
                + "&format=jsonv2&zoom=17&layer=address&extratags=1";
        JsonNode cached = reverseResults.get(query);
        if (cached != null) {
            return cached;
        }
        if (lastReverseRequest != null) {
            long wait = reverseIntervalNanos - (System.nanoTime() - lastReverseRequest);
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
        }
        HttpRequest request = HttpRequest.newBuilder(reverseApiBase.resolve("reverse?" + query))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("User-Agent", "traficinfo/1.0 (https://github.com/arnaudq/traficinfo)")
                .GET().build();
        lastReverseRequest = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result;
        if (response.statusCode() == 200 || response.statusCode() == 404) {
            result = mapper.readTree(response.body());
            if (result == null || !result.isObject()
                    || (response.statusCode() == 404 && !result.has("error"))) {
                throw new IOException("Invalid OSM reverse response");
            }
        } else {
            throw new IOException("OSM reverse lookup returned HTTP " + response.statusCode());
        }
        reverseResults.put(query, result);
        return result;
    }

    public Map<String, String> getWayTags(long wayId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(apiBase.resolve("way/" + wayId + ".json"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("User-Agent", "traficinfo/1.0 (https://github.com/arnaudq/traficinfo)")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OSM way " + wayId + " returned HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        if (root != null && root.path("elements").isArray()) {
            for (JsonNode element : root.path("elements")) {
                if ("way".equals(element.path("type").asText())
                        && element.path("id").asLong() == wayId) {
                    JsonNode tags = element.get("tags");
                    if (tags == null) {
                        return Collections.emptyMap();
                    }
                    if (!tags.isObject()) {
                        throw new IOException("Invalid tags for OSM way " + wayId);
                    }
                    return mapper.convertValue(tags, new TypeReference<Map<String, String>>() { });
                }
            }
        }
        throw new IOException("OSM response did not contain way " + wayId);
    }
}
