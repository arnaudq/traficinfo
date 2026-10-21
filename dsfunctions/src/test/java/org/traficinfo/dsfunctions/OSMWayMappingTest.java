package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

public class OSMWayMappingTest {
    @Test
    public void readsSeedFieldsAndPreservesReviewMetadataWhenAssigningWayId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"osmWayId\":null,\"candidateOsmWayId\":456,\"latitude\":43.5,\"longitude\":3.2,"
                + "\"sourceRoadReference\":\"D0011\",\"osmRoadReference\":\"M 11\","
                + "\"status\":\"NEEDS_REVIEW\",\"reviewReason\":\"different_ref\",\"distanceMeters\":12.5,"
                + "\"lastUpdated\":\"2026-10-20T12:00:00Z\",\"firstSeen\":\"2026-10-19T12:00:00Z\","
                + "\"lastSeen\":\"2026-10-21T12:00:00Z\","
                + "\"audit\":{\"accepted\":true,\"note\":\"metropolitan road\"}}";
        OSMWayMapping mapping = mapper.readValue(json, OSMWayMapping.class);
        assertNull(mapping.getOsmWayId());
        assertEquals(Long.valueOf(456), mapping.getCandidateOsmWayId());
        assertEquals(Double.valueOf(43.5), mapping.getLatitude());
        assertEquals("D0011", mapping.getSourceRoadReference());
        assertEquals("M 11", mapping.getOsmRoadReference());
        assertEquals("different_ref", mapping.getReviewReason());
        assertEquals(Double.valueOf(12.5), mapping.getDistanceMeters());
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsBytes(mapping)));

        mapping.setOsmWayId(456L);
        mapping.setStatus(OSMWayMappingStatus.MATCHED);
        JsonNode saved = mapper.valueToTree(mapping);
        assertEquals(456L, saved.get("osmWayId").asLong());
        assertEquals("MATCHED", saved.get("status").asText());
        assertEquals(mapper.readTree(json).get("audit"), saved.get("audit"));
        assertEquals("2026-10-19T12:00:00Z", saved.get("firstSeen").asText());
    }
}
