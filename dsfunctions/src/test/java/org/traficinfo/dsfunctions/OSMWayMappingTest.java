package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

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
        JsonNode encoded = mapper.readTree(mapper.writeValueAsBytes(mapping));
        assertEquals("https://www.openstreetmap.org/way/456", encoded.get("candidateOsmWayUrl").asText());
        assertEquals("https://www.openstreetmap.org/?mlat=43.5&mlon=3.2#map=17/43.5/3.2", encoded.get("coordinatesOsmUrl").asText());
        assertFalse(encoded.has("osmWayIdUrl"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).remove(List.of("candidateOsmWayUrl", "coordinatesOsmUrl"));
        assertEquals(mapper.readTree(json), encoded);
        // Ignore uploaded links and regenerate them from the actual IDs and coordinates.
        OSMWayMapping roundTrip = mapper.readValue(mapper.writeValueAsBytes(mapping), OSMWayMapping.class);
        assertTrue(roundTrip.getAdditionalProperties().containsKey("audit"));
        assertFalse(roundTrip.getAdditionalProperties().containsKey("candidateOsmWayUrl"));

        mapping.setOsmWayId(456L);
        mapping.setStatus(OSMWayMappingStatus.MATCHED);
        JsonNode saved = mapper.valueToTree(mapping);
        assertEquals(456L, saved.get("osmWayId").asLong());
        assertEquals("MATCHED", saved.get("status").asText());
        assertEquals("https://www.openstreetmap.org/way/456", saved.get("osmWayIdUrl").asText());
        assertEquals(mapper.readTree(json).get("audit"), saved.get("audit"));
        assertEquals("2026-10-19T12:00:00Z", saved.get("firstSeen").asText());
    }
}
