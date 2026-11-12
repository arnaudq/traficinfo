package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class OSMWayMappingTest {
    @Test
    public void comparesAllStoredFieldsByValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"osmWayId":123,"latitude":43.5,"longitude":3.2,"sourceRoadReference":"D11",
                 "sourceStreetName":"Jean Jaures (AVENUE)","osmStreetName":"Avenue Jean Jaurès",
                 "status":"NEEDS_REVIEW","firstSeen":"first","lastSeen":"last","lastUpdated":"updated",
                 "candidateOsmWayId":456,"osmRoadReference":"M11","reviewReason":"different_ref",
                 "distanceMeters":12.5,"lastLookupLatitude":43.5,"lastLookupLongitude":3.2,
                 "lastLookupAt":"lookup","reverseLookupResult":{"name":"road"},"audit":{"note":"review"}}
                """);
        OSMWayMapping original = mapper.treeToValue(json, OSMWayMapping.class);
        OSMWayMapping equal = mapper.treeToValue(json, OSMWayMapping.class);
        assertEquals(original, equal);
        assertEquals(equal, original);
        assertEquals(original.hashCode(), equal.hashCode());
        assertNotEquals(original, null);
        assertNotEquals(original, "mapping");
        assertEquals(new OSMWayMapping(), new OSMWayMapping());
        for (var fields = json.fieldNames(); fields.hasNext();) {
            String field = fields.next();
            var changed = json.deepCopy();
            if (field.equals("status")) changed.put(field, "MATCHED");
            else changed.putNull(field);
            assertNotEquals(field, original, mapper.treeToValue(changed, OSMWayMapping.class));
        }
    }

    @Test
    public void indicatesAvailableIdentifiersAndIgnoresUploadedIndicator() throws Exception {
        var mapping = new OSMWayMapping();
        assertEquals(OSMSourceMatchCriteria.NONE, mapping.getSourceMatchCriteria());
        mapping.setSourceStreetName("Jean Jaures (AVENUE)");
        assertEquals(OSMSourceMatchCriteria.STREET_NAME, mapping.getSourceMatchCriteria());
        mapping.setSourceRoadReference("D11");
        assertEquals(OSMSourceMatchCriteria.BOTH, mapping.getSourceMatchCriteria());
        mapping.setSourceStreetName(" ");
        assertEquals(OSMSourceMatchCriteria.ROAD_REFERENCE, mapping.getSourceMatchCriteria());
        var json = new ObjectMapper();
        var encoded = json.valueToTree(mapping);
        assertEquals("ROAD_REFERENCE", encoded.get("sourceMatchCriteria").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).put("sourceMatchCriteria", "STREET_NAME");
        var decoded = json.treeToValue(encoded, OSMWayMapping.class);
        assertEquals(OSMSourceMatchCriteria.ROAD_REFERENCE, decoded.getSourceMatchCriteria());
        assertFalse(decoded.getAdditionalProperties().containsKey("sourceMatchCriteria"));
    }

    @Test
    public void snapshotDoesNotShareMutableLookupResultsOrAnnotations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OSMWayMapping original = mapper.readValue("""
                {"status":"NEEDS_REVIEW","reverseLookupResult":{"name":"road"},"audit":{"note":"review"}}
                """, OSMWayMapping.class);
        OSMWayMapping copy = new OSMWayMapping(original);
        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
        ((com.fasterxml.jackson.databind.node.ObjectNode) original.getReverseLookupResult()).put("name", "changed");
        ((com.fasterxml.jackson.databind.node.ObjectNode) original.getAdditionalProperties().get("audit"))
                .put("note", "changed");
        assertEquals("road", copy.getReverseLookupResult().get("name").asText());
        assertEquals("review", copy.getAdditionalProperties().get("audit").get("note").asText());
        assertNotEquals(original, copy);
    }

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
        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).remove(List.of("candidateOsmWayUrl", "coordinatesOsmUrl", "sourceStreetName", "sourceMatchCriteria"));
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
