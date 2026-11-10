package org.traficinfo.parsers.onisr;

import org.junit.Test;
import org.traficinfo.parsers.AccidentVehicle.Motorization;
import org.traficinfo.parsers.AccidentVehicle.Type;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.Assert.*;

public class ONISRAccidentVehicleParserTest {
    private static final String HEADER = "Num_Acc;id_vehicule;catv;motor\n";
    private static final String ROW = "202500000001;001\u00a0234;7;1\n";
    private static final Set<String> SELECTED = Set.of("202500000001");
    private final ONISRAccidentVehicleParser parser = new ONISRAccidentVehicleParser();

    @Test
    public void handlesPublishedMultilineHeaderAndNormalizesIdsForParticipantJoins() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/ONISRVehiclesSample.csv"), StandardCharsets.UTF_8)) {
            var result = parser.parseVehicles(reader, Set.of("202500000001", "202500000002"), 2025);
            assertEquals(2, result.size());
            var vehicles = result.get("202500000001");
            assertEquals(2, vehicles.size());
            assertEquals("155975242", vehicles.get(0).getVehicleId());
            assertEquals(Integer.valueOf(7), vehicles.get(0).getCategoryCode());
            assertEquals(Type.CAR, vehicles.get(0).getType());
            assertEquals(Motorization.COMBUSTION, vehicles.get(0).getMotorization());
            assertEquals(Type.ELECTRIC_BICYCLE, vehicles.get(1).getType());
            assertEquals(Motorization.ELECTRIC, vehicles.get(1).getMotorization());
            assertEquals(Type.BICYCLE, result.get("202500000002").get(0).getType());
            assertEquals(Motorization.HUMAN, result.get("202500000002").get(0).getMotorization());
        }
    }

    @Test
    public void filtersBeforeFieldValidationAndPreservesUnrecognizedCategoryCode() throws Exception {
        String csv = HEADER + "202400000001;broken\n" + ROW.replace(";7;1", ";123;99");
        var result = parser.parseVehicles(new StringReader(csv), SELECTED, 2025);
        var vehicle = result.get("202500000001").get(0);
        assertEquals("001234", vehicle.getVehicleId());
        assertEquals(Integer.valueOf(123), vehicle.getCategoryCode());
        assertEquals(Type.UNKNOWN, vehicle.getType());
        assertEquals(Motorization.UNKNOWN, vehicle.getMotorization());
        assertTrue(parser.parseVehicles(new StringReader(csv), Set.of(), 2025).isEmpty());
    }

    @Test
    public void distinguishesPersonalMobilityFromScootersAndUnknownValues() throws Exception {
        for (var entry : java.util.Map.of(50, Type.MOTORIZED_PERSONAL_MOBILITY, 60, Type.NON_MOTORIZED_PERSONAL_MOBILITY,
                30, Type.SCOOTER, 33, Type.MOTORCYCLE, 39, Type.TRAIN).entrySet()) {
            var vehicle = parser.parseVehicles(new StringReader(HEADER + ROW.replace(";7;1", ";" + entry.getKey() + ";1")), SELECTED, 2025)
                    .get("202500000001").get(0);
            assertEquals(entry.getValue(), vehicle.getType());
        }
        for (String unknown : new String[]{";", "-1;-1", "0;0", ".;N/A"}) {
            var vehicle = parser.parseVehicles(new StringReader(HEADER + "202500000001;123;" + unknown + "\n"), SELECTED, 2025)
                    .get("202500000001").get(0);
            assertNull(vehicle.getCategoryCode());
            assertEquals(Type.UNKNOWN, vehicle.getType());
            assertEquals(Motorization.UNKNOWN, vehicle.getMotorization());
        }
    }

    @Test
    public void rejectsSelectedDuplicateIdsMalformedFieldsAndInvalidHeaders() throws Exception {
        for (String csv : new String[]{"", HEADER.replace("catv", "wrong") + ROW, HEADER.replace("catv", "motor") + ROW,
                HEADER + ROW + ROW.replace("001\u00a0234", "001\u202f234"), HEADER + ROW.replace(";7;1", ";bad;1"),
                HEADER + ROW.replace(";7;1", ";7;bad"), HEADER + ROW.replace("001\u00a0234", ""),
                HEADER + ROW.stripTrailing() + ";extra\n"}) {
            try {
                parser.parseVehicles(new StringReader(csv), SELECTED, 2025);
                fail("Expected selected vehicle to fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("vehicle"));
            }
        }
    }

    @Test
    public void acceptsBomAndKeepsCallerReaderOpen() throws Exception {
        StringReader reader = new StringReader("\uFEFF" + HEADER + ROW);
        assertEquals(1, parser.parseVehicles(reader, SELECTED, 2025).size());
        assertEquals(-1, reader.read());
    }
}
