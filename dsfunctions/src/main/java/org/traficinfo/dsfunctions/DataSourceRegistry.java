/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

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

/** Immutable datasource configuration loaded from the release's bundled dsconfig.json. */
final class DataSourceRegistry {
    private final Map<String, Source> sources;
    private final JsonNode definitions;

    private DataSourceRegistry(Map<String, Source> sources, JsonNode definitions) {
        this.sources = Map.copyOf(sources);
        this.definitions = definitions.deepCopy();
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
        return new DataSourceRegistry(sources, root);
    }

    Source source(String id) {
        Source source = sources.get(id);
        if (source == null) throw new IllegalArgumentException("Unknown datasourceId: " + id);
        return source;
    }

    String datasourceForInput(String bucket, String objectName) {
        return sources.entrySet().stream()
                .filter(entry -> entry.getValue().bucket().equals(bucket))
                .filter(entry -> {
                    var inputs = entry.getValue().settings().inputs();
                    return inputs.get(inputs.size() - 1).objectName().equals(objectName);
                })
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown preprocessing input: " + bucket + "/" + objectName));
    }

    GeoJsonDataSource geojson(String datasourceId) {
        source(datasourceId);
        if (!definitions.path(datasourceId).path("geoJsonEnabled").asBoolean(true)) {
            throw new IllegalArgumentException("GeoJSON is not enabled for " + datasourceId);
        }
        return GeoJsonDataSource.valueOf(datasourceId);
    }

    PreprocessingSettings preprocessing(String datasourceId, String datasetId) {
        Source active = source(datasourceId);
        JsonNode definition = definitions.path(datasourceId);
        String selected = datasetId == null ? active.settings().datasetId() : datasetId;
        JsonNode dataset = definition.path("datasets").path(selected);
        JsonNode years = dataset.path("years");
        if (!years.isArray()) throw new IllegalArgumentException("Missing years for " + datasourceId + "/" + selected);
        var scope = new ArrayList<Integer>();
        for (JsonNode year : years) {
            if (!year.isIntegralNumber() || !year.canConvertToInt()) throw new IllegalArgumentException("Invalid year");
            scope.add(year.asInt());
        }
        DataSourceSettings settings = active.settings();
        if (datasetId != null) {
            if (selected.equals(active.settings().datasetId())) throw new IllegalArgumentException("Archive must be inactive");
            JsonNode inputs = dataset.path("inputs");
            if (!inputs.isArray()) throw new IllegalArgumentException("Missing archive inputs");
            var archived = new ArrayList<DataSourceSettings.Input>();
            for (JsonNode input : inputs) {
                archived.add(new DataSourceSettings.Input(text(input, "url"), text(input, "objectName"), text(input, "contentType")));
            }
            settings = new DataSourceSettings(datasourceId, selected, archived);
        }
        return new PreprocessingSettings(settings, text(dataset, "datasetInfoUrl"), scope);
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
