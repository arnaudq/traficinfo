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
        @JsonProperty("activeDatasetId") String activeDatasetId,
        @JsonProperty("datasets") Map<String, Dataset> datasets,
        @JsonProperty("years") Map<Integer, YearData> years) {
    public static final int VERSION = 1;

    @JsonCreator
    public DataManifest {
        if (manifestVersion != VERSION) {
            throw new IllegalArgumentException("Unsupported manifest version: " + manifestVersion);
        }
        datasets = Map.copyOf(datasets);
        years = Map.copyOf(years);
    }

    public record Dataset(
            @JsonProperty("datasetUrl") String datasetUrl,
            @JsonProperty("years") List<Integer> years,
            @JsonProperty("inputs") Map<String, Input> inputs) {
        @JsonCreator
        public Dataset {
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
            @JsonProperty("datasetId") String datasetId,
            @JsonProperty("parserVersion") String parserVersion) {
        @JsonCreator
        public YearData {
        }
    }
}
