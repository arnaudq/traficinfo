package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Immutable provenance of one completed retrieval, including companion inputs. */
public record ImportRecord(
        @JsonProperty("importVersion") int importVersion,
        @JsonProperty("dataSourceId") String dataSourceId,
        @JsonProperty("importId") String importId,
        @JsonProperty("resourceId") String resourceId,
        @JsonProperty("retrievedAt") String retrievedAt,
        @JsonProperty("parserVersion") String parserVersion,
        @JsonProperty("years") List<Integer> years,
        @JsonProperty("inputs") Map<String, Input> inputs) {
    @JsonCreator
    public ImportRecord {
        StorageContract.requireVersion(importVersion);
        years = List.copyOf(years);
        inputs = Map.copyOf(inputs);
    }

    public record Input(
            @JsonProperty("sourceUrl") String sourceUrl,
            @JsonProperty("object") String object,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("sourceValidator") String sourceValidator,
            @JsonProperty("sourceLastModified") String sourceLastModified,
            @JsonProperty("observedHeaders") List<String> observedHeaders) {
        @JsonCreator
        public Input {
            observedHeaders = observedHeaders == null ? null : List.copyOf(observedHeaders);
        }
    }
}
