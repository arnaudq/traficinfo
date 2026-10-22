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
    private final ObjectStoreClient client;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    OSMWayMappingReviewStore(ObjectStoreClient client) {
        this.client = client;
    }

    Map<String, Map<String, OSMWayMapping>> load() throws IOException {
        try (InputStream input = client.getResource(OBJECT_NAME)) {
            Map<String, Map<String, OSMWayMapping>> review = mapper.readValue(input,
                    new TypeReference<TreeMap<String, Map<String, OSMWayMapping>>>() { });
            if (review == null || review.values().stream().anyMatch(group -> group == null
                    || group.values().stream().anyMatch(record -> record == null || record.getStatus() == null))) {
                throw new IOException("OSM review must contain data source objects with non-null mapping records and statuses");
            }
            return review;
        } catch (BmcException failure) {
            if (failure.getStatusCode() == 404) {
                return new TreeMap<>();
            }
            throw failure;
        }
    }

    void save(Map<String, Map<String, OSMWayMapping>> review) throws IOException {
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(review);
        try (InputStream input = new ByteArrayInputStream(json)) {
            client.putResource(OBJECT_NAME, "application/json", new OOSResourceMetadata(), input);
        }
    }
}
