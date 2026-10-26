package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Immutable decision bound to one proposal checksum and the reviewed baseline. */
public record ReviewDecision(
        @JsonProperty("decisionVersion") int decisionVersion,
        @JsonProperty("reviewId") String reviewId,
        @JsonProperty("outcome") Outcome outcome,
        @JsonProperty("decidedAt") String decidedAt,
        @JsonProperty("decidedBy") String decidedBy,
        @JsonProperty("comment") String comment,
        @JsonProperty("baseManifestRevision") String baseManifestRevision,
        @JsonProperty("proposalSha256") String proposalSha256) {
    @JsonCreator
    public ReviewDecision {
        StorageContract.requireVersion(decisionVersion);
        java.util.Objects.requireNonNull(outcome, "outcome");
    }

    public enum Outcome { APPROVED, REJECTED }
}
