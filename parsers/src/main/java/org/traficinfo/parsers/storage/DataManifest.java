package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** The current publication for one datasource. Object references are relative to its bucket. */
public record DataManifest(
        @JsonProperty("manifestVersion") int manifestVersion,
        @JsonProperty("dataSourceId") String dataSourceId,
        @JsonProperty("revision") String revision,
        @JsonProperty("publishedAt") String publishedAt,
        @JsonProperty("comptageSchemaVersion") int comptageSchemaVersion,
        @JsonProperty("activeResourceId") String activeResourceId,
        @JsonProperty("resources") Map<String, Resource> resources,
        @JsonProperty("years") Map<Integer, YearData> years) {
    @JsonCreator
    public DataManifest {
        StorageContract.requireVersion(manifestVersion);
        StorageContract.requireVersion(comptageSchemaVersion);
        resources = Map.copyOf(resources);
        years = Map.copyOf(years);
    }

    public record Resource(
            @JsonProperty("datasetUrl") String datasetUrl,
            @JsonProperty("years") List<Integer> years,
            @JsonProperty("inputs") Map<String, Input> inputs) {
        @JsonCreator
        public Resource {
            years = List.copyOf(years);
            inputs = Map.copyOf(inputs);
        }
    }

    public record Input(
            @JsonProperty("url") String url,
            @JsonProperty("format") String format,
            @JsonProperty("headers") List<String> headers) {
        @JsonCreator
        public Input {
            headers = headers == null ? null : List.copyOf(headers);
        }
    }

    public record YearData(
            @JsonProperty("object") String object,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("recordCount") long recordCount,
            @JsonProperty("resourceId") String resourceId,
            @JsonProperty("parserVersion") String parserVersion,
            @JsonProperty("importId") String importId) {
        @JsonCreator
        public YearData {
        }
    }
}
