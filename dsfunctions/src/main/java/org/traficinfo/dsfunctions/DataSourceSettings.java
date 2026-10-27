package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.RuntimeContext;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Retrieval settings passed by deployment, rather than reading dsconfig.json at runtime. */
public record DataSourceSettings(
        @JsonProperty("dataSourceId") String dataSourceId,
        @JsonProperty("resourceId") String resourceId,
        @JsonProperty("inputs") List<Input> inputs) {
    public static final String CONFIG_KEY = "DATA_SOURCE_SETTINGS";

    @JsonCreator
    public DataSourceSettings {
        requireText(dataSourceId, "dataSourceId");
        requireText(resourceId, "resourceId");
        inputs = List.copyOf(inputs);
        if (inputs.isEmpty()) throw new IllegalArgumentException("At least one source input is required");
        Set<String> names = new HashSet<>();
        for (Input input : inputs) {
            if (!names.add(input.objectName())) {
                throw new IllegalArgumentException("Duplicate input Object: " + input.objectName());
            }
        }
    }

    public static DataSourceSettings from(RuntimeContext context, String dataSourceId) {
        return context.getConfigurationByKey(CONFIG_KEY).map(json -> {
            try {
                DataSourceSettings settings = new ObjectMapper().readValue(json, DataSourceSettings.class);
                if (!settings.dataSourceId().equals(dataSourceId)) {
                    throw new IllegalArgumentException("Datasource ID does not match handler: " + settings.dataSourceId());
                }
                return settings;
            } catch (IOException invalid) {
                throw new IllegalArgumentException("Invalid " + CONFIG_KEY, invalid);
            }
        }).orElseThrow(() -> new IllegalArgumentException("Missing " + CONFIG_KEY));
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
            if (!objectName.endsWith(".csv") && !objectName.endsWith(".json")) {
                throw new IllegalArgumentException("Input Object must end in .csv or .json");
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
