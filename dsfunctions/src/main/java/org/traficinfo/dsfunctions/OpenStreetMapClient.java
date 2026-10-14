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

/** One REST request per way, without redirects or retries. */
public class OpenStreetMapClient {
    private final HttpClient client;
    private final URI apiBase;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenStreetMapClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                URI.create("https://api.openstreetmap.org/api/0.6/"));
    }

    OpenStreetMapClient(HttpClient client, URI apiBase) {
        this.client = client;
        this.apiBase = apiBase;
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
