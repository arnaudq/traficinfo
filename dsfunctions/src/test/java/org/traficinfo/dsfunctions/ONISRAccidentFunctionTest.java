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

    @Test
    public void fnImportsFixed2025ObjectAndClosesBothStores() throws Exception {
        Probe probe = configured(Map.of("NAMESPACE", "namespace"));
        assertTrue(probe.connections.isEmpty());
        probe.accidentStore.text("caract-2025.csv", CSV);
        probe.accidentStore.text("usagers-2025.csv", USERS);
        probe.accidentStore.text("caract-2024.csv", "must not be read");
        assertEquals("OK", probe.handleRequest("{}"));
        assertEquals(List.of("true/namespace/onisrinfo", "true/namespace/osminfo"), probe.connections);
        assertTrue(probe.accidentStore.closed);
        assertTrue(probe.osmStore.closed);
        JsonNode records = JSON.readTree(probe.accidentStore.objects.get("data/ONISR_DPT34/2025.json"));
        assertEquals(1, records.size());
        assertEquals("2025-01-02", records.get(0).get("date").asText());
        assertEquals("FEMALE", records.get(0).get("participants").get(0).get("sex").asText());
        assertFalse(probe.accidentStore.objects.containsKey("data/ONISR_DPT34/2024.json"));
        assertTrue(probe.osmStore.objects.containsKey("ONISR_DPT34_OSMWayMapping.json"));
    }

    @Test
    public void emptyInputImportsAndRefreshDoesNotRequireRawCsv() throws Exception {
        Probe probe = configured(Map.of("NAMESPACE", "namespace", "USE_RESOURCE_PRINCIPAL", "false",
                "BUCKETNAME_OSM", "custom-osm"));
        probe.accidentStore.text("caract-2025.csv", CSV);
        probe.accidentStore.text("usagers-2025.csv", USERS);
        assertEquals("OK", probe.handleRequest(""));
        assertEquals(List.of("false/namespace/onisrinfo", "false/namespace/custom-osm"), probe.connections);
        probe.accidentStore.objects.remove("caract-2025.csv");
        probe.accidentStore.objects.remove("usagers-2025.csv");
        probe.osmStore.text("ONISR_DPT34_OSMWayMapping.json", "{\"202500000001\":{\"osmWayId\":123,\"status\":\"MATCHED\"}}");
        assertEquals("OK", probe.handleRequest("{\"action\":\"refresh\"}"));
        JsonNode output = JSON.readTree(probe.accidentStore.objects.get("ONISR_DPT34_Accidents.json"));
        assertEquals(1, output.get("123").size());
        assertEquals("FEMALE", output.get("123").get(0).get("participants").get(0).get("sex").asText());
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
        probe.accidentStore.text("caract-2025.csv", CSV.replace("2025", "2024"));
        try {
            probe.handleRequest("{}");
            fail("Wrong source year must fail the function");
        } catch (UncheckedIOException expected) {
            assertFalse(probe.accidentStore.objects.containsKey("data/ONISR_DPT34/2025.json"));
            assertTrue(probe.osmStore.objects.isEmpty());
        }
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
        Store() { super(null, "test", "fake"); }
        void text(String name, String value) { objects.put(name, value.getBytes(StandardCharsets.UTF_8)); }
        @Override public List<String> listResources() { return new ArrayList<>(objects.keySet()); }
        @Override public InputStream getResource(String name) {
            if (!objects.containsKey(name)) throw new BmcException(404, "Missing", "No such object", "test");
            return new ByteArrayInputStream(objects.get(name));
        }
        @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream input) throws IOException {
            objects.put(name, input.readAllBytes());
        }
        @Override public void close() { closed = true; }
    }
}
