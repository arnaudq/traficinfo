package org.traficinfo.dsfunctions;

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
        Store store = new Store();
        store.objects.put("mapping.json", json);
        store.objects.put("info.json", "{\"123\":{\"tags\":{\"maxspeed\":\"80\"}}}");
        OSMInfoProviderImpl provider = new OSMInfoProviderImpl(store, new OpenStreetMapClient(),
                "info.json", "mapping.json");
        provider.loadCache();
        assertEquals(9, provider.getUnknownWayMappingCount());
        assertEquals(10, provider.getWayMappingCount());
        assertEquals(1, provider.getWayInfoCount());
        provider.getWayId("new", null, null);
        assertEquals(10, provider.getUnknownWayMappingCount());
        assertEquals(11, provider.getWayMappingCount());
        store.objects.clear();
        provider.loadCache();
        assertEquals(0, provider.getUnknownWayMappingCount());
        assertEquals(0, provider.getWayMappingCount());
        assertEquals(0, provider.getWayInfoCount());
    }

    @Test
    public void loadsMatchingInfoForEachMappingAndSkipsOtherObjects() {
        Store store = new Store();
        store.objects.put("DPT34_OSMWayMapping.json", "{\"A\":{\"osmWayId\":123},\"B\":{\"osmWayId\":null}}");
        store.objects.put("MMM_OSMWayMapping.json", "{}");
        store.objects.put("DPT34_OSMInfo.json", "{\"123\":{\"lastUpdated\":\"2026-10-20T12:00:00Z\","
                + "\"tags\":{\"maxspeed\":\"80\"}}}");
        store.objects.put("MMM_OSMInfo.json", "{}");
        store.objects.put("unrelated_OSMInfo.json", "must not be read");
        store.objects.put("DPT34_OSMWayMapping.metadata.json", "must not be read");
        assertEquals("OK", new OSMInfoFunction(store).handleRequest(""));
        assertEquals(List.of("DPT34_OSMInfo.json", "DPT34_OSMWayMapping.json",
                "MMM_OSMInfo.json", "MMM_OSMWayMapping.json"), store.read);
        assertEquals(store.read, store.closed);
    }

    @Test
    public void reportsFailuresAndContinuesWithOtherCaches() {
        for (String invalid : List.of("invalid", "null", "[]", "{} trailing")) {
            Store store = new Store();
            store.objects.put("bad_OSMWayMapping.json", invalid);
            store.objects.put("bad_OSMInfo.json", "{}");
            store.objects.put("good_OSMWayMapping.json", "{}");
            store.objects.put("good_OSMInfo.json", "{}");
            assertEquals("KO", new OSMInfoFunction(store).handleRequest(""));
            assertEquals(4, store.read.size());
            assertEquals(store.read, store.closed);
        }
        Store unreadable = new Store();
        unreadable.objects.put("denied_OSMWayMapping.json", null);
        unreadable.objects.put("denied_OSMInfo.json", "{}");
        unreadable.objects.put("good_OSMWayMapping.json", "{}");
        assertEquals("KO", new OSMInfoFunction(unreadable).handleRequest(""));
        assertEquals(4, unreadable.read.size());
        assertEquals("OK", new OSMInfoFunction(new Store()).handleRequest(""));
        assertEquals("KO", new OSMInfoFunction(new Store() {
            @Override public List<String> listResources() {
                throw new BmcException(403, "Forbidden", "Denied", "request");
            }
        }).handleRequest(""));
    }

    @Test
    public void missingInfoStartsEmptyButInvalidOrForbiddenInfoFailsAndContinues() {
        Store missing = new Store();
        missing.objects.put("DPT34_OSMWayMapping.json", "{}");
        assertEquals("OK", new OSMInfoFunction(missing).handleRequest(""));
        assertEquals(List.of("DPT34_OSMInfo.json", "DPT34_OSMWayMapping.json"), missing.read);
        for (String info : new String[]{"invalid", "null", "[]", "{} trailing", null}) {
            Store store = new Store();
            store.objects.put("bad_OSMWayMapping.json", "{}");
            store.objects.put("bad_OSMInfo.json", info);
            store.objects.put("good_OSMWayMapping.json", "{}");
            store.objects.put("good_OSMInfo.json", "{}");
            assertEquals("KO", new OSMInfoFunction(store).handleRequest(""));
            assertEquals(List.of("bad_OSMInfo.json", "good_OSMInfo.json", "good_OSMWayMapping.json"), store.read);
            assertTrue(store.closed.contains("good_OSMInfo.json"));
        }
    }

    private static class Store extends ObjectStoreClient {
        final Map<String, String> objects = new LinkedHashMap<>();
        final List<String> read = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        Store() { super(null, "namespace", "osminfo"); }
        @Override public List<String> listResources() { return new ArrayList<>(objects.keySet()); }
        @Override public InputStream getResource(String name) {
            read.add(name);
            if (!objects.containsKey(name)) {
                throw new BmcException(404, "NotFound", "Missing", "request");
            }
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
