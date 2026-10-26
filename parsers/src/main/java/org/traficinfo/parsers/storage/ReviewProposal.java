package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Immutable candidate proposal. A null baseline revision denotes the first import. */
public record ReviewProposal(
        @JsonProperty("reviewVersion") int reviewVersion,
        @JsonProperty("reviewId") String reviewId,
        @JsonProperty("dataSourceId") String dataSourceId,
        @JsonProperty("importId") String importId,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("baseManifestRevision") String baseManifestRevision,
        @JsonProperty("reasons") List<Reason> reasons,
        @JsonProperty("changes") Map<Integer, YearChange> changes,
        @JsonProperty("schemaChanges") Map<String, SchemaChange> schemaChanges) {
    @JsonCreator
    public ReviewProposal {
        StorageContract.requireVersion(reviewVersion);
        reasons = List.copyOf(reasons);
        changes = Map.copyOf(changes);
        schemaChanges = Map.copyOf(schemaChanges);
    }

    public enum Reason { INITIAL_IMPORT, RESOURCE_SWITCH, HISTORICAL_DATA_CHANGED, SCHEMA_CHANGED }
    public record YearChange(
            @JsonProperty("baselineObject") String baselineObject,
            @JsonProperty("candidateObject") String candidateObject,
            @JsonProperty("candidateSha256") String candidateSha256,
            @JsonProperty("recordCount") long recordCount,
            @JsonProperty("diffObject") String diffObject,
            @JsonProperty("summary") Summary summary) {
        @JsonCreator
        public YearChange {
        }
    }

    public record Summary(
            @JsonProperty("added") long added,
            @JsonProperty("modified") long modified,
            @JsonProperty("removed") long removed) {
        @JsonCreator
        public Summary {
        }
    }

    public record SchemaChange(
            @JsonProperty("acceptedHeaders") List<String> acceptedHeaders,
            @JsonProperty("observedHeaders") List<String> observedHeaders) {
        @JsonCreator
        public SchemaChange {
            acceptedHeaders = acceptedHeaders == null ? null : List.copyOf(acceptedHeaders);
            observedHeaders = List.copyOf(observedHeaders);
        }
    }
}
