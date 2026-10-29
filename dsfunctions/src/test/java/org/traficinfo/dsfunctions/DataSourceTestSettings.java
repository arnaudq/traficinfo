package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Supplies deployment-equivalent test settings from the repository config, without network requests. */
public final class DataSourceTestSettings {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SOURCES = readSources();

    private DataSourceTestSettings() {}

    public static DataSourceSettings settings(String id) {
        JsonNode source = SOURCES.get(id);
        String datasetId = source.get("activeDatasetId").asText();
        JsonNode dataset = source.get("datasets").get(datasetId);
        ObjectNode settings = JSON.createObjectNode();
        settings.put("dataSourceId", id);
        settings.put("datasetId", datasetId);
        ArrayNode inputs = settings.putArray("inputs");
        for (JsonNode original : dataset.get("inputs")) {
            ObjectNode input = original.deepCopy();
            if (!input.has("objectName")) {
                input.put("objectName", source.path("csvObject").asText(id + "_Comptage.csv"));
            }
            inputs.add(input);
        }
        try {
            return JSON.treeToValue(settings, DataSourceSettings.class);
        } catch (IOException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    public static Map<String, String> configuration(String id) {
        try {
            return Map.of(DataSourceSettings.CONFIG_KEY, JSON.writeValueAsString(settings(id)),
                    "BUCKETNAME", SOURCES.get(id).path("inputBucket").asText("ds" + id.toLowerCase(Locale.ROOT)));
        } catch (IOException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static JsonNode readSources() {
        Path config = Path.of(System.getProperty("basedir", ".")).resolve("../dsconfig.json");
        try {
            return JSON.readTree(Files.readAllBytes(config));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read test settings from " + config, failure);
        }
    }
}
