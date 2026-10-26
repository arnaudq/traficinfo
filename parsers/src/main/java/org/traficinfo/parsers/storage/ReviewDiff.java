package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Record-level review evidence. Null before/after denotes an addition/removal. */
public record ReviewDiff(
        @JsonProperty("diffVersion") int diffVersion,
        @JsonProperty("dataSourceId") String dataSourceId,
        @JsonProperty("reviewId") String reviewId,
        @JsonProperty("changes") Map<Integer, List<Change>> changes) {
    @JsonCreator
    public ReviewDiff {
        StorageContract.requireVersion(diffVersion);
        changes = changes.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public record Change(
            @JsonProperty("codeCentrale") String codeCentrale,
            @JsonProperty("discriminator") String discriminator,
            @JsonProperty("changedFields") List<String> changedFields,
            @JsonProperty("before") NormalizedComptage before,
            @JsonProperty("after") NormalizedComptage after) {
        @JsonCreator
        public Change {
            changedFields = List.copyOf(changedFields);
            if (before == null && after == null) {
                throw new IllegalArgumentException("A change requires before or after");
            }
        }
    }
}
