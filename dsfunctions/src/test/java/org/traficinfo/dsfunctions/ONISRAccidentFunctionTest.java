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
import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import com.oracle.bmc.model.BmcException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ONISRAccidentFunctionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSV = "Num_Acc;an;mois;jour;hrmn;dep;com;adr;lat;long\n"
            + "202500000001;2025;1;2;12:30;34;34172;Rue de l'École;43,6;3,8\n";
    private static final String USERS = "Num_Acc;id_usager;id_vehicule;catu;grav;an_nais;sexe\n"
            + "202500000001;1;2;1;3;2000;2\n";
    private static final String VEHICLES = "Num_Acc;id_vehicule;catv;motor\n202500000001;2;7;1\n";

    @Test
    public void fnImportsConfiguredYearsAndRefreshesOnceAndClosesBothStores() throws Exception {
        Probe probe = configured(Map.of("NAMESPACE", "namespace"));
        assertTrue(probe.connections.isEmpty());
        source(probe, 2024);
        source(probe, 2025);
        probe.accidentStore.text("caract-2023.csv", "must not be read");
        assertEquals("OK", probe.handleRequest("{}"));
        assertEquals(List.of("true/namespace/onisrinfo", "true/namespace/osminfo"), probe.connections);
        assertTrue(probe.accidentStore.closed);
        assertTrue(probe.osmStore.closed);
        JsonNode records = JSON.readTree(probe.accidentStore.objects.get("data/ONISR_DPT34/2025.json"));
        assertEquals(1, records.size());
        assertEquals("2025-01-02", records.get(0).get("date").asText());
        assertEquals("FEMALE", records.get(0).get("participants").get(0).get("sex").asText());
        assertEquals("CAR", records.get(0).get("vehicles").get(0).get("type").asText());
        JsonNode previous = JSON.readTree(probe.accidentStore.objects.get("data/ONISR_DPT34/2024.json"));
        assertEquals("2024-01-02", previous.get(0).get("date").asText());
        assertEquals("CAR", previous.get(0).get("vehicles").get(0).get("type").asText());
        assertEquals("FEMALE", previous.get(0).get("participants").get(0).get("sex").asText());
        assertFalse(probe.accidentStore.objects.containsKey("data/ONISR_DPT34/2023.json"));
        assertEquals(3, probe.accidentStore.writes.stream().filter(name -> name.endsWith("_Accidents.json")).count());
        assertEquals(List.of(2024, 2025), ONISRYearInputs.configured().stream().map(ONISRYearInputs::year).toList());
        assertTrue(probe.osmStore.objects.containsKey("ONISR_DPT34_OSMRequests.json"));
    }

    @Test
    public void emptyInputImportsAndRefreshDoesNotRequireRawCsv() throws Exception {
        Probe probe = configured(Map.of("NAMESPACE", "namespace", "USE_RESOURCE_PRINCIPAL", "false",
                "BUCKETNAME_OSM", "custom-osm"));
        source(probe, 2024);
        source(probe, 2025);
        assertEquals("OK", probe.handleRequest(""));
        assertEquals(List.of("false/namespace/onisrinfo", "false/namespace/custom-osm"), probe.connections);
        for (ONISRYearInputs year : ONISRYearInputs.configured()) {
            probe.accidentStore.objects.remove(year.accidentObject());
            probe.accidentStore.objects.remove(year.participantsObject());
            probe.accidentStore.objects.remove(year.vehiclesObject());
        }
        probe.osmStore.text("ONISR_DPT34_OSMWayMapping.json", "{\"202400000001\":{\"osmWayId\":123,\"status\":\"MATCHED\"},"
                + "\"202500000001\":{\"osmWayId\":123,\"status\":\"MATCHED\"}}");
        assertEquals("OK", probe.handleRequest("{\"action\":\"refresh\"}"));
        JsonNode output = JSON.readTree(probe.accidentStore.objects.get("ONISR_DPT34_Accidents.json"));
        assertEquals(2, output.get("123").size());
        assertEquals("2024-01-02", output.get("123").get(0).get("date").asText());
        assertEquals("2025-01-02", output.get("123").get(1).get("date").asText());
        assertEquals("FEMALE", output.get("123").get(0).get("participants").get(0).get("sex").asText());
        assertEquals("CAR", output.get("123").get(0).get("vehicles").get(0).get("type").asText());
    }

    @Test
    public void rejectsUnsupportedRequestsBeforeOpeningStores() {
        Probe probe = configured(Map.of("NAMESPACE", "namespace"));
        for (String invalid : List.of("null", "[]", "{} {}", "{\"action\":\"other\"}",
                "{\"year\":2024}", "{\"objectName\":\"caract-2024.csv\"}")) {
            try {
                probe.handleRequest(invalid);
                fail("Expected unsupported request to fail: " + invalid);
            } catch (IllegalArgumentException expected) {
                assertTrue(probe.connections.isEmpty());
            }
        }
    }

    @Test
    public void missingInputAndWrongYearFailInvocationAndCloseStores() {
        Probe probe = configured(Map.of("NAMESPACE", "namespace"));
        try {
            probe.handleRequest("{}");
            fail("Missing raw CSV must fail the function");
        } catch (BmcException expected) {
            assertEquals(404, expected.getStatusCode());
            assertTrue(probe.accidentStore.closed);
            assertTrue(probe.osmStore.closed);
        }
        probe.accidentStore.text("caract-2024.csv", CSV);
        try {
            probe.handleRequest("{}");
            fail("Wrong source year must fail the function");
        } catch (UncheckedIOException expected) {
            assertFalse(probe.accidentStore.objects.containsKey("data/ONISR_DPT34/2025.json"));
            assertTrue(probe.osmStore.objects.isEmpty());
        }
    }

    @Test
    public void reimportReplaces2024WithoutDuplicatingOrReplacingUnchanged2025() throws Exception {
        Probe probe = configured(Map.of("NAMESPACE", "namespace"));
        source(probe, 2024);
        source(probe, 2025);
        probe.handleRequest("{}");
        byte[] original2025 = probe.accidentStore.objects.get("data/ONISR_DPT34/2025.json");
        probe.accidentStore.writes.clear();
        probe.accidentStore.text("caract-2024.csv", CSV.substring(0, CSV.indexOf('\n') + 1));
        probe.handleRequest("{}");
        assertEquals(0, JSON.readTree(probe.accidentStore.objects.get("data/ONISR_DPT34/2024.json")).size());
        assertArrayEquals(original2025, probe.accidentStore.objects.get("data/ONISR_DPT34/2025.json"));
        assertFalse(probe.accidentStore.writes.contains("data/ONISR_DPT34/2025.json"));
    }

    @Test
    public void missingLaterYearFailsBeforeCombinedPublicationAndRetryCompletesImport() throws Exception {
        Probe probe = configured(Map.of("NAMESPACE", "namespace"));
        source(probe, 2024);
        String previous = "{\"previous\":[]}";
        probe.accidentStore.text("ONISR_DPT34_Accidents.json", previous);
        try {
            probe.handleRequest("{}");
            fail("Missing 2025 must fail the invocation");
        } catch (BmcException expected) {
            assertEquals(404, expected.getStatusCode());
            assertTrue(probe.accidentStore.objects.containsKey("data/ONISR_DPT34/2024.json"));
            assertEquals(previous, new String(probe.accidentStore.objects.get("ONISR_DPT34_Accidents.json"), StandardCharsets.UTF_8));
            assertTrue(probe.osmStore.objects.isEmpty());
        }
        source(probe, 2025);
        assertEquals("OK", probe.handleRequest("{}"));
        assertEquals(1, JSON.readTree(probe.accidentStore.objects.get("data/ONISR_DPT34/2024.json")).size());
        assertEquals(1, JSON.readTree(probe.accidentStore.objects.get("data/ONISR_DPT34/2025.json")).size());
    }

    private static void source(Probe probe, int year) {
        String value = Integer.toString(year);
        probe.accidentStore.text("caract-" + year + ".csv", CSV.replace("2025", value));
        probe.accidentStore.text("usagers-" + year + ".csv", USERS.replace("2025", value));
        probe.accidentStore.text("vehicules-" + year + ".csv", VEHICLES.replace("2025", value));
    }

    private static Probe configured(Map<String, String> config) {
        var context = new FunctionRuntimeContext(new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"), config);
        new FunctionConfigurer().configure(context);
        return (Probe) context.getInvokeInstance().orElseThrow();
    }

    public static class Probe extends ONISRAccidentFunction {
        final Store accidentStore = new Store();
        final Store osmStore = new Store();
        final List<String> connections = new ArrayList<>();

        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
            connections.add(principal + "/" + namespace + "/" + bucket);
            return bucket.equals("onisrinfo") ? accidentStore : osmStore;
        }
    }

    private static class Store extends ObjectStoreClient {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        boolean closed;
        final List<String> writes = new ArrayList<>();
        Store() { super(null, "test", "fake"); }
        void text(String name, String value) { objects.put(name, value.getBytes(StandardCharsets.UTF_8)); }
        @Override public List<String> listResources() { return new ArrayList<>(objects.keySet()); }
        @Override public InputStream getResource(String name) {
            if (!objects.containsKey(name)) throw new BmcException(404, "Missing", "No such object", "test");
            return new ByteArrayInputStream(objects.get(name));
        }
        @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream input) throws IOException {
            objects.put(name, input.readAllBytes());
            writes.add(name);
        }
        @Override public void close() { closed = true; }
    }
}
