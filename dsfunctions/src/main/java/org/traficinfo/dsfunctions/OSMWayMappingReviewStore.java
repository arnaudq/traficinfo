package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.model.BmcException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

/** The global manual-review document, grouped by data source prefix and then counter ID. */
class OSMWayMappingReviewStore {
    static final String OBJECT_NAME = "OSMWayMapping_NEEDS_REVIEW.json";
    static final String NEEDS_REVIEW_MERGED_OBJECT_NAME = "OSMWayMapping_NEEDS_REVIEW_MERGED.json";
    static final String NOT_FOUND_MERGED_OBJECT_NAME = "OSMWayMapping_NOT_FOUND_MERGED.json";
    static final String NOT_FOUND_OBJECT_NAME = "OSMWayMapping_NOT_FOUND.json";
    private final String objectName;
    private final ObjectStoreClient client;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Map<String, Map<String, OSMWayMapping>> previousReview;

    record SaveResult(boolean written, int added, int changed, int removed) { }

    OSMWayMappingReviewStore(ObjectStoreClient client) {
        this(client, OSMWayMappingStatus.NEEDS_REVIEW);
    }

    OSMWayMappingReviewStore(ObjectStoreClient client, OSMWayMappingStatus status) {
        this(client, "OSMWayMapping_" + status.name() + ".json");
    }

    OSMWayMappingReviewStore(ObjectStoreClient client, String objectName) {
        this.client = client;
        this.objectName = objectName;
    }

    boolean wasLoaded() {
        return previousReview != null;
    }

    String getObjectName() {
        return objectName;
    }

    Map<String, Map<String, OSMWayMapping>> load() throws IOException {
        try (InputStream input = client.getResource(objectName)) {
            Map<String, Map<String, OSMWayMapping>> review = mapper.readValue(input,
                    new TypeReference<TreeMap<String, Map<String, OSMWayMapping>>>() { });
            if (review == null || review.values().stream().anyMatch(group -> group == null
                    || group.values().stream().anyMatch(record -> record == null || record.getStatus() == null))) {
                throw new IOException("OSM review must contain data source objects with non-null mapping records and statuses");
            }
            // Snapshot before approvals or cache refreshes can change any mapping records.
            previousReview = snapshot(review);
            return review;
        } catch (BmcException failure) {
            if (failure.getStatusCode() == 404) {
                previousReview = null;
                return new TreeMap<>();
            }
            throw failure;
        }
    }

    /** Load a submission, leaving unresolved entries out of the approval decisions. */
    Map<String, Map<String, OSMWayMapping>> loadApprovals() throws IOException {
        Map<String, Map<String, OSMWayMapping>> approvals = new TreeMap<>();
        load().forEach((source, records) -> {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("Approval source is required");
            Map<String, OSMWayMapping> decisions = new TreeMap<>();
            records.forEach((counterId, decision) -> {
                if (decision.getStatus() == OSMWayMappingStatus.NEEDS_REVIEW
                        || decision.getStatus() == OSMWayMappingStatus.NOT_FOUND) return;
                if (counterId == null || counterId.isBlank() || decision.getStatus() != OSMWayMappingStatus.MATCHED
                        || decision.getCandidateOsmWayId() == null || decision.getCandidateOsmWayId() <= 0) {
                    throw new IllegalArgumentException("Approval requires counter ID, MATCHED status, and positive candidateOsmWayId: "
                            + source + "/" + counterId);
                }
                decisions.put(counterId, decision);
            });
            if (!decisions.isEmpty()) approvals.put(source, decisions);
        });
        return approvals;
    }

    SaveResult save(Map<String, Map<String, OSMWayMapping>> review) throws IOException {
        Map<String, Map<String, OSMWayMapping>> current = snapshot(review);
        Map<String, Map<String, OSMWayMapping>> previous = previousReview == null ? Map.of() : previousReview;
        int added = 0;
        int changed = 0;
        int removed = 0;
        for (var source : current.entrySet()) {
            for (var record : source.getValue().entrySet()) {
                OSMWayMapping oldRecord = previous.getOrDefault(source.getKey(), Map.of()).get(record.getKey());
                if (oldRecord == null) added++;
                else if (!oldRecord.equals(record.getValue())) changed++;
            }
        }
        for (var source : previous.entrySet()) {
            for (String counterId : source.getValue().keySet()) {
                if (!current.getOrDefault(source.getKey(), Map.of()).containsKey(counterId)) removed++;
            }
        }
        if (previousReview != null && previousReview.equals(current)) {
            return new SaveResult(false, added, changed, removed);
        }
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(review);
        try (InputStream input = new ByteArrayInputStream(json)) {
            client.putResource(objectName, "application/json", new OOSResourceMetadata(), input);
        }
        previousReview = current;
        return new SaveResult(true, added, changed, removed);
    }

    private static Map<String, Map<String, OSMWayMapping>> snapshot(
            Map<String, Map<String, OSMWayMapping>> review) {
        Map<String, Map<String, OSMWayMapping>> copy = new TreeMap<>();
        review.forEach((source, records) -> {
            Map<String, OSMWayMapping> group = new TreeMap<>();
            records.forEach((id, mapping) -> group.put(id, new OSMWayMapping(mapping)));
            copy.put(source, group);
        });
        return copy;
    }
}
