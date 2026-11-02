package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable retrieval configuration loaded from the release's bundled dsconfig.json. */
final class DataSourceRegistry {
    private final Map<String, Source> sources;

    private DataSourceRegistry(Map<String, Source> sources) {
        this.sources = Map.copyOf(sources);
    }

    static DataSourceRegistry bundled() {
        try (InputStream input = DataSourceRegistry.class.getResourceAsStream("/dsconfig.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled dsconfig.json");
            return read(input);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Cannot load bundled dsconfig.json", invalid);
        }
    }

    static DataSourceRegistry read(InputStream input) throws IOException {
        ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode root = json.readTree(input);
        if (root == null || !root.isObject() || root.isEmpty()) {
            throw new IllegalArgumentException("Expected a nonempty datasource map");
        }
        Map<String, Source> sources = new HashMap<>();
        Set<String> destinations = new HashSet<>();
        var entries = root.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            String id = entry.getKey();
            JsonNode source = entry.getValue();
            if (!id.matches("[A-Z][A-Z0-9_]*") || !id.equals(text(source, "id"))) {
                throw new IllegalArgumentException("Datasource key must match its id: " + id);
            }
            String bucket = source.has("inputBucket") ? text(source, "inputBucket")
                    : "ds" + id.toLowerCase(Locale.ROOT);
            validateBucket(bucket);
            String datasetId = text(source, "activeDatasetId");
            JsonNode dataset = source.path("datasets").path(datasetId);
            JsonNode inputs = dataset.path("inputs");
            if (!inputs.isArray() || inputs.isEmpty()) {
                throw new IllegalArgumentException("Missing active dataset inputs for " + id);
            }
            var settings = new ArrayList<DataSourceSettings.Input>();
            for (JsonNode inputConfig : inputs) {
                String contentType = text(inputConfig, "contentType");
                String objectName;
                if (inputConfig.has("objectName")) objectName = text(inputConfig, "objectName");
                else if (contentType.equals("text/csv")) objectName = source.has("csvObject")
                        ? text(source, "csvObject") : id + "_Comptage.csv";
                else throw new IllegalArgumentException("Non-CSV inputs require objectName: " + id);
                if (!destinations.add(bucket + "/" + objectName)) {
                    throw new IllegalArgumentException("Duplicate retrieval destination: " + bucket + "/" + objectName);
                }
                settings.add(new DataSourceSettings.Input(text(inputConfig, "url"), objectName, contentType));
            }
            sources.put(id, new Source(bucket, new DataSourceSettings(id, datasetId, settings)));
        }
        return new DataSourceRegistry(sources);
    }

    Source source(String id) {
        Source source = sources.get(id);
        if (source == null) throw new IllegalArgumentException("Unknown datasourceId: " + id);
        return source;
    }

    static void validateBucket(String bucket) {
        if (bucket == null || !bucket.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid input bucket: " + bucket);
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        return value.asText();
    }

    record Source(String bucket, DataSourceSettings settings) {}
}
