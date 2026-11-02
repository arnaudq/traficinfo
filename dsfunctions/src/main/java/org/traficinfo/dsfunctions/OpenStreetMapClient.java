package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class OpenStreetMapClient implements AutoCloseable {
    // Public Nominatim scheduled jobs allow at most four requests per minute.
    private static final int REVERSE_INTERVAL_SECONDS = 15;
    private static final Logger LOG = LoggerFactory.getLogger(OpenStreetMapClient.class);
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
                URI.create("https://nominatim.openstreetmap.org/"), TimeUnit.SECONDS.toMillis(REVERSE_INTERVAL_SECONDS));
    }

    OpenStreetMapClient(HttpClient client, URI apiBase, URI reverseApiBase, long reverseIntervalMillis) {
        if (reverseIntervalMillis < 0) {
            throw new IllegalArgumentException("Reverse request interval must not be negative");
        }
        this.client = client;
        this.apiBase = apiBase;
        this.reverseApiBase = reverseApiBase;
        this.reverseIntervalNanos = TimeUnit.MILLISECONDS.toNanos(reverseIntervalMillis);
    }

    /** Public Nominatim scheduled jobs are limited to four requests per minute. */
    public synchronized JsonNode reverseLookup(double latitude, double longitude)
            throws IOException, InterruptedException {
        String query = "lat=" + latitude + "&lon=" + longitude
                + "&format=jsonv2&zoom=17&layer=address&extratags=1&namedetails=1";
        URI requestUri = reverseApiBase.resolve("reverse?" + query);
        JsonNode cached = reverseResults.get(query);
        if (cached != null) {
            LOG.info("OSM reverse lookup {}: cached response {}", requestUri, cached);
            return cached;
        }
        if (lastReverseRequest != null) {
            long wait = reverseIntervalNanos - (System.nanoTime() - lastReverseRequest);
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
        }
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("User-Agent", "traficinfo/1.0 (https://github.com/arnaudq/traficinfo)")
                .GET().build();
        LOG.info("OSM reverse request: GET {}", requestUri);
        lastReverseRequest = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException failure) {
            LOG.warn("OSM reverse request {} failed without a response: {}", requestUri, failure.toString());
            throw failure;
        }
        LOG.info("OSM reverse response for {}: HTTP {}, body: {}",
                requestUri, response.statusCode(), response.body());
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
    @Override
    public void close() {
        client.close();
    }
}
