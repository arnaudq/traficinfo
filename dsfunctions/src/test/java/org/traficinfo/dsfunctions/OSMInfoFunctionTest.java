package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.Assert.*;

public class OSMInfoFunctionTest {
    @Test
    public void countsUnknownWaysRegardlessOfStatusOrCandidate() throws Exception {
        String json = "{\"known\":{\"osmWayId\":123},\"missing\":{},"
                + "\"null\":{\"osmWayId\":null},\"zero\":{\"osmWayId\":0},"
                + "\"negative\":{\"osmWayId\":-1},\"text\":{\"osmWayId\":\"123\"},"
                + "\"decimal\":{\"osmWayId\":12.3},\"overflow\":{\"osmWayId\":9223372036854775808},"
                + "\"review\":{\"osmWayId\":null,\"candidateOsmWayId\":456,\"status\":\"NEEDS_REVIEW\"},"
                + "\"nullEntry\":null}";
        assertEquals(9, OSMInfoFunction.countUnknownEntries(new ObjectMapper().readTree(json)));
        assertEquals(0, OSMInfoFunction.countUnknownEntries(new ObjectMapper().readTree("{}")));
        for (String invalid : List.of("null", "[]", "42")) {
            try {
                OSMInfoFunction.countUnknownEntries(new ObjectMapper().readTree(invalid));
                fail("Invalid cache must fail");
            } catch (IOException expected) { }
        }
    }

    @Test
    public void logsEachMappingCacheAndSkipsOtherObjects() {
        Store store = new Store();
        store.objects.put("DPT34_OSMWayMapping.json", "{\"A\":{\"osmWayId\":123},\"B\":{\"osmWayId\":null}}");
        store.objects.put("MMM_OSMWayMapping.json", "{}");
        store.objects.put("DPT34_OSMInfo.json", "must not be read");
        store.objects.put("DPT34_OSMWayMapping.metadata.json", "must not be read");
        assertEquals("OK", new OSMInfoFunction(store).handleRequest(""));
        assertEquals(List.of("DPT34_OSMWayMapping.json", "MMM_OSMWayMapping.json"), store.read);
        assertEquals(store.read, store.closed);
    }

    @Test
    public void reportsFailuresAndContinuesWithOtherCaches() {
        for (String invalid : List.of("invalid", "null", "[]", "{} trailing")) {
            Store store = new Store();
            store.objects.put("bad_OSMWayMapping.json", invalid);
            store.objects.put("good_OSMWayMapping.json", "{}");
            assertEquals("KO", new OSMInfoFunction(store).handleRequest(""));
            assertEquals(2, store.read.size());
            assertEquals(store.read, store.closed);
        }
        Store unreadable = new Store();
        unreadable.objects.put("denied_OSMWayMapping.json", null);
        unreadable.objects.put("good_OSMWayMapping.json", "{}");
        assertEquals("KO", new OSMInfoFunction(unreadable).handleRequest(""));
        assertEquals(2, unreadable.read.size());
        assertEquals("OK", new OSMInfoFunction(new Store()).handleRequest(""));
        assertEquals("KO", new OSMInfoFunction(new Store() {
            @Override public List<String> listResources() {
                throw new BmcException(403, "Forbidden", "Denied", "request");
            }
        }).handleRequest(""));
    }

    private static class Store extends ObjectStoreClient {
        final Map<String, String> objects = new LinkedHashMap<>();
        final List<String> read = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        Store() { super(null, "namespace", "osminfo"); }
        @Override public List<String> listResources() { return new ArrayList<>(objects.keySet()); }
        @Override public InputStream getResource(String name) {
            read.add(name);
            if (objects.get(name) == null) {
                throw new BmcException(403, "Forbidden", "Denied", "request");
            }
            return new ByteArrayInputStream(objects.get(name).getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws IOException {
                    if (!closed.contains(name)) {
                        closed.add(name);
                    }
                    super.close();
                }
            };
        }
        @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream body) {
            fail("Reporting must not modify caches");
        }
    }
}
