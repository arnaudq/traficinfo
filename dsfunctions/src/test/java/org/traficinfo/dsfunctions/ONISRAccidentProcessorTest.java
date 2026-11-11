/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oracle.bmc.model.BmcException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

public class ONISRAccidentProcessorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER = "Num_Acc;an;mois;jour;hrmn;dep;com;adr;lat;long;col\n";
    private static final OpenStreetMapClient NO_NETWORK = new OpenStreetMapClient() {
        @Override public JsonNode reverseLookup(double latitude, double longitude) {
            throw new AssertionError("Processing must not make reverse lookup requests");
        }
        @Override public Map<String, String> getWayTags(long way) {
            throw new AssertionError("Processing must not request OSM tags");
        }
    };

    private static String row(int year, int sequence, String region, String latitude, String longitude) {
        return String.format(java.util.Locale.ROOT, "%d%08d;%d;1;2;00:00;%s;%s001;Rue de l'École;%s;%s;6\n",
                year, sequence, year, region, region, latitude, longitude);
    }

    private static ONISRAccidentProcessor processor(Store data, Store osm, String... regions) {
        return new ONISRAccidentProcessor(data, prefix -> provider(osm, prefix, NO_NETWORK), Set.of(regions));
    }

    private static OSMInfoProviderImpl provider(Store osm, String prefix, OpenStreetMapClient client) {
        return new OSMInfoProviderImpl(osm, client, prefix + "_OSMInfo.json", prefix + "_OSMWayMapping.json");
    }

    private static void known(Store osm, String region, String id, long way) throws Exception {
        String name = ONISRAccidentProcessor.cachePrefix(region) + "_OSMWayMapping.json";
        ObjectNode entries = osm.objects.containsKey(name) ? (ObjectNode) osm.json(name) : JSON.createObjectNode();
        entries.putObject(id).put("osmWayId", way).put("status", "MATCHED").put("latitude", 43.0).put("longitude", 3.0);
        osm.objects.put(name, JSON.writeValueAsBytes(entries));
    }

    @Test
    public void importsSelectedRegionsPreservesUnmatchedAndUsesSeparateAccidentCaches() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        data.text("caract-2025.csv", HEADER + row(2025, 1, "30", "43", "3")
                + row(2025, 2, "30", "43", "3") + row(2025, 3, "30", "44", "4")
                + row(2025, 4, "34", "", "") + row(2025, 5, "76", "49", "1")
                + row(2025, 6, "75", "48", "2"));
        known(osm, "30", "202500000001", 77);
        known(osm, "30", "202500000002", 77);
        osm.text("DPT30_OSMWayMapping.json", "{\"trafficCounter\":{\"osmWayId\":99}}");
        osm.text("ONISR_DPT30_OSMInfo.json", "{}");
        byte[] originalTags = osm.objects.get("ONISR_DPT30_OSMInfo.json");
        var result = processor(data, osm, "30", "34", "76").importCsv("caract-2025.csv", 2025);
        assertEquals(new ONISRAccidentProcessor.Statistics(3, 2, 1, 1), result.get("30"));
        assertEquals(new ONISRAccidentProcessor.Statistics(1, 0, 1, 0), result.get("34"));
        assertEquals(3, data.json(ONISRAccidentProcessor.yearObject("30", 2025)).size());
        assertEquals(2, data.json(ONISRAccidentProcessor.outputObject("30")).get("77").size());
        assertTrue(data.json(ONISRAccidentProcessor.outputObject("34")).isObject());
        assertEquals(0, data.json(ONISRAccidentProcessor.outputObject("34")).size());
        assertFalse(data.objects.containsKey(ONISRAccidentProcessor.yearObject("75", 2025)));
        JsonNode mappings = osm.json("ONISR_DPT30_OSMRequests.json").get("reverseLookups");
        assertFalse(mappings.get("202500000003").has("status"));
        assertEquals(44, mappings.get("202500000003").get("latitude").asDouble(), 0);
        assertTrue(mappings.get("202500000003").get("sourceRoadReference").isNull());
        assertTrue(osm.json("ONISR_DPT34_OSMRequests.json").get("reverseLookups").get("202500000004").get("latitude").isNull());
        assertEquals(99, osm.json("DPT30_OSMWayMapping.json").get("trafficCounter").get("osmWayId").asInt());
        assertArrayEquals(originalTags, osm.objects.get("ONISR_DPT30_OSMInfo.json"));
        assertFalse(osm.writes.contains("DPT30_OSMWayMapping.json"));
        JsonNode stored = data.json(ONISRAccidentProcessor.yearObject("30", 2025)).get(0);
        assertEquals("43", stored.get("sourceFields").get("lat").asText());
        assertTrue(stored.get("osmWayId").isNull()); // Source snapshots stay independent of mappings.
    }

    @Test
    public void accumulatesYearsAndReimportsReplaceRatherThanAppend() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        var processor = processor(data, osm, "30", "34");
        known(osm, "30", "202400000001", 77);
        known(osm, "30", "202500000001", 77);
        known(osm, "30", "202500000002", 88);
        data.text("2024.csv", HEADER + row(2024, 1, "30", "43", "3"));
        processor.importCsv("2024.csv", 2024);
        data.text("2025.csv", HEADER + row(2025, 2, "30", "43", "3") + row(2025, 1, "30", "43", "3"));
        processor.importCsv("2025.csv", 2025);
        JsonNode output = data.json(ONISRAccidentProcessor.outputObject("30"));
        assertEquals(2, output.get("77").size());
        assertEquals("202400000001", output.get("77").get(0).get("accidentId").asText());
        data.writes.clear();
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3") + row(2025, 2, "30", "43", "3"));
        processor.importCsv("2025.csv", 2025);
        assertTrue("Row order and repeated import must not replace equivalent data/output", data.writes.isEmpty());
        data.text("2025.csv", HEADER + row(2025, 1, "30", "44", "4"));
        processor.importCsv("2025.csv", 2025);
        assertFalse(data.json(ONISRAccidentProcessor.outputObject("30")).has("88"));
        assertEquals("44", data.json(ONISRAccidentProcessor.yearObject("30", 2025)).get(0).get("sourceFields").get("lat").asText());
        assertEquals(1, data.json(ONISRAccidentProcessor.yearObject("30", 2024)).size());
        // An empty replacement still clears that year, while preserving other years.
        data.text("2025.csv", HEADER);
        var result = processor.importCsv("2025.csv", 2025);
        assertEquals(1, result.get("30").accidents());
        assertEquals(0, data.json(ONISRAccidentProcessor.yearObject("30", 2025)).size());
        assertEquals(1, data.json(ONISRAccidentProcessor.outputObject("30")).get("77").size());
    }

    @Test
    public void refreshPicksUpSharedReverseLookupResultsWithoutReimportingCsv() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3"));
        var processor = processor(data, osm, "30");
        processor.importCsv("2025.csv", 2025);
        var resolver = provider(osm, "ONISR_DPT30", new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double lat, double lon) throws IOException {
                return JSON.readTree("{\"osm_type\":\"way\",\"osm_id\":123,\"category\":\"highway\",\"type\":\"residential\"}");
            }
        });
        resolver.loadCache();
        resolver.mergeRequests(resolver.loadRequests("ONISR_DPT30"));
        resolver.refreshWayMappings(1);
        data.objects.remove("2025.csv");
        var result = processor.refresh();
        assertEquals(1, result.get("30").matched());
        assertEquals(1, data.json(ONISRAccidentProcessor.outputObject("30")).get("123").size());
        // Reassigning a way in the mapping cache moves the accident on the next refresh.
        known(osm, "30", "202500000001", 456);
        processor.refresh();
        assertFalse(data.json(ONISRAccidentProcessor.outputObject("30")).has("123"));
        assertEquals(1, data.json(ONISRAccidentProcessor.outputObject("30")).get("456").size());
    }

    @Test
    public void candidatesAndUnlocatedRecordsNeverBecomeMatchedOutput() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3") + row(2025, 2, "30", "", ""));
        osm.text("ONISR_DPT30_OSMWayMapping.json", "{\"202500000001\":{\"candidateOsmWayId\":77,"
                + "\"status\":\"NEEDS_REVIEW\",\"latitude\":43,\"longitude\":3,\"reviewReason\":\"different_ref\"},"
                + "\"202500000002\":{\"osmWayId\":88,\"status\":\"MATCHED\"}}");
        var result = processor(data, osm, "30").importCsv("2025.csv", 2025);
        assertEquals(2, result.get("30").unmatched());
        assertEquals(0, data.json(ONISRAccidentProcessor.outputObject("30")).size());
        assertEquals("different_ref", osm.json("ONISR_DPT30_OSMWayMapping.json").get("202500000001").get("reviewReason").asText());
    }

    @Test
    public void invalidCsvAndYearMismatchDoNotReplaceStoredRecordsOrOutput() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        var processor = processor(data, osm, "30");
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3"));
        processor.importCsv("2025.csv", 2025);
        byte[] original = data.objects.get(ONISRAccidentProcessor.yearObject("30", 2025));
        for (String invalid : List.of("bad CSV", HEADER + row(2024, 1, "30", "43", "3"),
                HEADER + row(2025, 1, "30", "43", "3") + row(2025, 1, "30", "43", "3"))) {
            data.text("2025.csv", invalid);
            data.writes.clear();
            osm.writes.clear();
            try {
                processor.importCsv("2025.csv", 2025);
                fail("Expected invalid source to fail");
            } catch (IOException expected) {
                assertTrue(data.writes.isEmpty());
                assertTrue(osm.writes.isEmpty());
                assertArrayEquals(original, data.objects.get(ONISRAccidentProcessor.yearObject("30", 2025)));
            }
        }
    }

    @Test
    public void corruptStoredYearOrMappingPreservesPreviousOutput() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        var processor = processor(data, osm, "30");
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3"));
        known(osm, "30", "202500000001", 77);
        processor.importCsv("2025.csv", 2025);
        String year = ONISRAccidentProcessor.yearObject("30", 2025);
        String output = ONISRAccidentProcessor.outputObject("30");
        byte[] originalYear = data.objects.get(year);
        byte[] originalOutput = data.objects.get(output);
        String valid = new String(originalYear, StandardCharsets.UTF_8);
        for (String corrupt : List.of("null", "{}", valid.replace("2025-01-02", "2024-01-02"),
                valid.replace("\"regionalId\":\"30\"", "\"regionalId\":\"34\""))) {
            data.text(year, corrupt);
            try {
                processor.refresh();
                fail("Expected corrupt stored year to fail");
            } catch (IOException expected) {
                assertArrayEquals(originalOutput, data.objects.get(output));
            }
        }
        data.objects.put(year, originalYear);
        osm.text("ONISR_DPT30_OSMWayMapping.json", "null");
        try {
            processor.refresh();
            fail("Expected corrupt mapping to fail");
        } catch (IOException expected) {
            assertArrayEquals(originalOutput, data.objects.get(output));
        }
    }

    @Test
    public void propagatesReadAndWriteFailuresAndCanRefreshAfterFailedPublication() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        var processor = processor(data, osm, "30");
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3"));
        String output = ONISRAccidentProcessor.outputObject("30");
        processor.importCsv("2025.csv", 2025);
        byte[] original = data.objects.get(output);
        known(osm, "30", "202500000001", 99);
        data.failRead = output;
        try {
            processor.refresh();
            fail("Access denied must not be treated as an absent object");
        } catch (BmcException expected) {
            assertEquals(403, expected.getStatusCode());
            assertArrayEquals(original, data.objects.get(output));
        }
        data.failRead = null;
        data.failWrite = output;
        try {
            processor.refresh();
            fail("Output failure must propagate");
        } catch (IOException expected) {
            assertArrayEquals(original, data.objects.get(output));
        }
        data.failWrite = null;
        processor.refresh();
        assertTrue(data.json(output).has("99"));
    }

    @Test
    public void acceptsDecodedGzipSourceAndIgnoresUnrelatedStoredObjects() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write((HEADER + row(2025, 1, "30", "43", "3")).getBytes(StandardCharsets.UTF_8));
        }
        data.objects.put("2025.csv", compressed.toByteArray());
        data.gzip.add("2025.csv");
        data.text("data/ONISR_DPT30/2025.json.bak", "not JSON");
        data.text("data/DPT30/2025.json", "not JSON");
        var result = processor(data, osm, "30").importCsv("2025.csv", 2025);
        assertEquals(1, result.get("30").accidents());
    }

    @Test
    public void joinsOnlySelectedUsagersPersistsSexAndReplacesParticipantsOnReimport() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        var processor = processor(data, osm, "30");
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3") + row(2025, 2, "75", "48", "2"));
        String header = "Num_Acc;id_usager;id_vehicule;catu;grav;an_nais;sexe;trajet\n";
        String first = "202500000001;123;456;1;3;1990;1;discarded\n";
        String second = "202500000001;124;456;2;4;1991;2;discarded\n";
        data.text("users.csv", header + second + first + "202500000002;invalid\n");
        known(osm, "30", "202500000001", 77);
        processor.importCsv("2025.csv", "users.csv", 2025);
        String year = ONISRAccidentProcessor.yearObject("30", 2025);
        String output = ONISRAccidentProcessor.outputObject("30");
        JsonNode stored = data.json(year).get(0).get("participants");
        assertEquals(2, stored.size());
        assertEquals("123", stored.get(0).get("participantId").asText());
        assertEquals("FEMALE", stored.get(1).get("sex").asText());
        assertFalse(stored.get(0).has("sourceFields"));
        assertFalse(stored.get(0).has("trajet"));
        assertEquals(stored, data.json(output).get("77").get(0).get("participants"));
        data.writes.clear();
        data.text("users.csv", header + first + second);
        processor.importCsv("2025.csv", "users.csv", 2025);
        assertTrue("Reordering participants must not republish", data.writes.isEmpty());
        data.text("users.csv", header + second);
        processor.importCsv("2025.csv", "users.csv", 2025);
        assertEquals(1, data.json(year).get(0).get("participants").size());
        assertEquals("124", data.json(output).get("77").get(0).get("participants").get(0).get("participantId").asText());
        data.objects.remove("2025.csv");
        data.objects.remove("users.csv");
        processor.refresh();
        assertEquals("FEMALE", data.json(output).get("77").get(0).get("participants").get(0).get("sex").asText());
    }

    @Test
    public void missingOrInvalidSelectedUsagersDoNotReplaceExistingYear() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        var processor = processor(data, osm, "30");
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3"));
        processor.importCsv("2025.csv", 2025);
        String year = ONISRAccidentProcessor.yearObject("30", 2025);
        byte[] original = data.objects.get(year);
        data.writes.clear();
        osm.writes.clear();
        try {
            processor.importCsv("2025.csv", "users.csv", 2025);
            fail("Missing companion must fail");
        } catch (BmcException expected) {
            assertEquals(404, expected.getStatusCode());
        }
        data.text("users.csv", "Num_Acc;id_usager;id_vehicule;catu;grav;an_nais;sexe\n"
                + "202500000001;123;456;1;3;2026;1\n");
        try {
            processor.importCsv("2025.csv", "users.csv", 2025);
            fail("Invalid selected participant must fail");
        } catch (IOException expected) {
            assertArrayEquals(original, data.objects.get(year));
        }
        assertTrue(data.writes.isEmpty());
        assertTrue(osm.writes.isEmpty());
    }

    @Test
    public void joinsSelectedVehiclesAndParticipantReferencesAndRefreshesWithoutRawFiles() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        var processor = processor(data, osm, "30");
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3") + row(2025, 2, "75", "48", "2"));
        data.text("users.csv", "Num_Acc;id_usager;id_vehicule;catu;grav;an_nais;sexe\n"
                + "202500000001;123;456\u00a0789;3;3;1990;2\n");
        String vehicleHeader = "Num_Acc;id_vehicule;catv;motor;manv\n";
        String car = "202500000001;456 789;7;1;discarded\n";
        String bicycle = "202500000001;456790;1;5;discarded\n";
        data.text("vehicles.csv", vehicleHeader + bicycle + car + "202500000002;invalid\n");
        known(osm, "30", "202500000001", 77);
        processor.importCsv("2025.csv", "users.csv", "vehicles.csv", 2025);
        String output = ONISRAccidentProcessor.outputObject("30");
        String year = ONISRAccidentProcessor.yearObject("30", 2025);
        JsonNode stored = data.json(year).get(0);
        assertEquals(2, stored.get("vehicles").size());
        assertEquals("CAR", stored.get("vehicles").get(0).get("type").asText());
        assertEquals(stored.get("vehicles").get(0).get("vehicleId"), stored.get("participants").get(0).get("vehicleId"));
        assertFalse(stored.get("vehicles").get(0).has("manv"));
        assertFalse(stored.get("vehicles").get(0).has("sourceFields"));
        assertEquals(stored.get("vehicles"), data.json(output).get("77").get(0).get("vehicles"));
        data.writes.clear();
        data.text("vehicles.csv", vehicleHeader + car + bicycle);
        processor.importCsv("2025.csv", "users.csv", "vehicles.csv", 2025);
        assertTrue(data.writes.isEmpty());
        data.text("vehicles.csv", vehicleHeader + car.replace(";7;1;", ";80;3;"));
        processor.importCsv("2025.csv", "users.csv", "vehicles.csv", 2025);
        assertEquals(1, data.json(year).get(0).get("vehicles").size());
        data.objects.remove("2025.csv");
        data.objects.remove("users.csv");
        data.objects.remove("vehicles.csv");
        processor.refresh();
        assertEquals("ELECTRIC_BICYCLE", data.json(output).get("77").get(0).get("vehicles").get(0).get("type").asText());
    }

    @Test
    public void missingMalformedOrCrossAccidentVehicleReferencesDoNotReplacePreviousRecords() throws Exception {
        Store data = new Store();
        Store osm = new Store();
        var processor = processor(data, osm, "30");
        data.text("2025.csv", HEADER + row(2025, 1, "30", "43", "3"));
        data.text("users.csv", "Num_Acc;id_usager;id_vehicule;catu;grav;an_nais;sexe\n"
                + "202500000001;123;456;1;3;1990;1\n");
        processor.importCsv("2025.csv", "users.csv", 2025);
        String year = ONISRAccidentProcessor.yearObject("30", 2025);
        byte[] original = data.objects.get(year);
        data.writes.clear();
        osm.writes.clear();
        try {
            processor.importCsv("2025.csv", "users.csv", "vehicles.csv", 2025);
            fail("Missing vehicles CSV must fail");
        } catch (BmcException expected) {
            assertEquals(404, expected.getStatusCode());
        }
        for (String invalid : List.of("bad CSV", "Num_Acc;id_vehicule;catv;motor\n202500000001;999;7;1\n",
                "Num_Acc;id_vehicule;catv;motor\n202500000002;456;7;1\n")) {
            data.text("vehicles.csv", invalid);
            try {
                processor.importCsv("2025.csv", "users.csv", "vehicles.csv", 2025);
                fail("Invalid selected vehicles or dangling references must fail");
            } catch (IOException expected) {
                assertArrayEquals(original, data.objects.get(year));
                assertTrue(data.writes.isEmpty());
                assertTrue(osm.writes.isEmpty());
            }
        }
    }

    private static class Store extends ObjectStoreClient {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        final Set<String> gzip = new java.util.HashSet<>();
        String failRead;
        String failWrite;

        Store() { super(null, "test", "fake"); }
        void text(String name, String value) { objects.put(name, value.getBytes(StandardCharsets.UTF_8)); }
        JsonNode json(String name) throws IOException { return JSON.readTree(objects.get(name)); }
        @Override public List<String> listResources() { return new ArrayList<>(objects.keySet()); }
        @Override public InputStream getResource(String name) throws IOException {
            if (name.equals(failRead)) throw new BmcException(403, "Denied", "No access", "test");
            if (!objects.containsKey(name)) throw new BmcException(404, "Missing", "No such object", "test");
            InputStream input = new ByteArrayInputStream(objects.get(name));
            return gzip.contains(name) ? new GZIPInputStream(input) : input;
        }
        @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream input) throws IOException {
            if (name.equals(failWrite)) throw new IOException("Injected write failure");
            assertEquals("application/json", type);
            objects.put(name, input.readAllBytes());
            writes.add(name);
        }
    }
}
