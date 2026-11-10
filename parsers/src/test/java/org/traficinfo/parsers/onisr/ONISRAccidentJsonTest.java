package org.traficinfo.parsers.onisr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ONISRAccidentJsonTest {
    private static final String CSV = "Num_Acc;an;mois;jour;hrmn;dep;com;adr;lat;long;col;extra\n"
            + "202500000001;2025;1;2;00:00;34;34172;Rue de l'École;43,6;3,8;6;retained\n"
            + "202500000002;2025;1;3;.;34;34172;N/A;;;-1;retained too\n";

    @Test
    public void roundTripsSourceFieldsDatesNullsAndWayAssignmentWithoutDerivedAliases() throws Exception {
        var records = new ONISRAccidentParser().parseAccidents(new StringReader(CSV));
        records.get(0).setOsmWayId(42L);
        ONISRAccidentJson codec = new ONISRAccidentJson();
        byte[] bytes = codec.write(records);
        var tree = new ObjectMapper().readTree(bytes);
        assertEquals("2025-01-02", tree.get(0).get("date").asText());
        assertEquals("00:00", tree.get(0).get("time").asText());
        assertFalse(tree.get(0).has("geometry"));
        assertFalse(tree.get(0).has("collisionType"));
        List<ONISRAccident> stored = codec.read(new ByteArrayInputStream(bytes));
        assertEquals(records.get(0).getDate(), stored.get(0).getDate());
        assertEquals(records.get(0).getTime(), stored.get(0).getTime());
        assertEquals(records.get(0).getSourceFields(), stored.get(0).getSourceFields());
        assertEquals("Rue de l'École", stored.get(0).getAddress());
        assertEquals(Long.valueOf(42), stored.get(0).getOsmWayId());
        assertEquals(Integer.valueOf(6), stored.get(0).getCollisionType());
        assertNull(stored.get(1).getTime());
        assertNull(stored.get(1).getGeometry());
        assertNull(stored.get(1).getCollisionType());
        assertArrayEquals(bytes, codec.write(stored));
        assertEquals("202500000001", new ObjectMapper().readTree(codec.writeByWay(Map.of(42L, List.of(stored.get(0)))))
                .get("42").get(0).get("accidentId").asText());
    }

    @Test
    public void rejectsNullWrongShapesAndTrailingJson() throws Exception {
        for (String invalid : List.of("null", "{}", "[null]", "[] {}", "[{\"accidentId\":\"A\"}]")) {
            try {
                new ONISRAccidentJson().read(new ByteArrayInputStream(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                fail("Expected invalid accident document to fail: " + invalid);
            } catch (IOException expected) {
                // Corrupt stored data must not become an empty successful publication.
            }
        }
    }

    @Test
    public void storesOnlySelectedParticipantFieldsAndReadsOlderAccidentsWithoutParticipants() throws Exception {
        var accident = new ONISRAccidentParser().parseAccidents(new StringReader(CSV)).get(0);
        var participants = new ONISRAccidentParticipantParser().parseParticipants(new StringReader(
                "Num_Acc;id_usager;id_vehicule;catu;grav;an_nais;sexe;trajet\n"
                        + "202500000001;123;456;3;2;1990;2;discarded\n"), java.util.Set.of(accident.getAccidentId()), 2025);
        var codec = new ONISRAccidentJson();
        byte[] bytes = codec.write(List.of(accident.withParticipants(participants.get(accident.getAccidentId()))));
        var tree = new ObjectMapper().readTree(bytes);
        var participant = tree.get(0).get("participants").get(0);
        assertEquals(6, participant.size());
        assertEquals("FEMALE", participant.get("sex").asText());
        assertEquals("KILLED", participant.get("injurySeverity").asText());
        assertFalse(participant.has("accidentId"));
        assertFalse(participant.has("sourceFields"));
        assertFalse(participant.has("trajet"));
        var stored = codec.read(new ByteArrayInputStream(bytes)).get(0).getParticipants().get(0);
        assertEquals(org.traficinfo.parsers.AccidentParticipant.Sex.FEMALE, stored.getSex());
        assertEquals("456", stored.getVehicleId());
        assertArrayEquals(bytes, codec.write(codec.read(new ByteArrayInputStream(bytes))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get(0)).remove("participants");
        assertTrue(codec.read(new ByteArrayInputStream(new ObjectMapper().writeValueAsBytes(tree))).get(0).getParticipants().isEmpty());
    }
}
