package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mutable discovery index; proposals, decisions and manifests remain authoritative. */
public record ReviewIndex(
        @JsonProperty("indexVersion") int indexVersion,
        @JsonProperty("dataSourceId") String dataSourceId,
        @JsonProperty("reviews") List<Entry> reviews) {
    @JsonCreator
    public ReviewIndex {
        StorageContract.requireVersion(indexVersion);
        reviews = List.copyOf(reviews);
    }

    public enum Status { PENDING, REJECTED, SUPERSEDED, APPROVED, PUBLISHED }

    public record Entry(
            @JsonProperty("reviewId") String reviewId,
            @JsonProperty("status") Status status,
            @JsonProperty("proposalObject") String proposalObject,
            @JsonProperty("decisionObject") String decisionObject,
            @JsonProperty("publishedRevision") String publishedRevision) {
        @JsonCreator
        public Entry {
        }
    }
}
