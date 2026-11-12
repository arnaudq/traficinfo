package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validated inputs selected from a datasource configuration for retrieval or preprocessing. */
public record DataSourceSettings(
        @JsonProperty("dataSourceId") String dataSourceId,
        @JsonProperty("datasetId") String datasetId,
        @JsonProperty("inputs") List<Input> inputs) {

    @JsonCreator
    public DataSourceSettings {
        requireText(dataSourceId, "dataSourceId");
        requireText(datasetId, "datasetId");
        inputs = List.copyOf(inputs);
        if (inputs.isEmpty()) throw new IllegalArgumentException("At least one source input is required");
        Set<String> names = new HashSet<>();
        for (Input input : inputs) {
            if (!names.add(input.objectName())) {
                throw new IllegalArgumentException("Duplicate input Object: " + input.objectName());
            }
        }
    }

    public record Input(
            @JsonProperty("url") String url,
            @JsonProperty("objectName") String objectName,
            @JsonProperty("contentType") String contentType) {
        @JsonCreator
        public Input {
            requireText(url, "url");
            URI uri = URI.create(url);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException("Input URL must be an absolute HTTP(S) URL");
            }
            requireText(objectName, "objectName");
            if (!objectName.endsWith(".csv") && !objectName.endsWith(".json") && !objectName.endsWith(".geojson")) {
                throw new IllegalArgumentException("Input Object must end in .csv, .json or .geojson");
            }
            requireText(contentType, "contentType");
        }

        public String archiveName(String date) {
            int extension = objectName.lastIndexOf('.');
            return objectName.substring(0, extension) + "_" + date + objectName.substring(extension);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
