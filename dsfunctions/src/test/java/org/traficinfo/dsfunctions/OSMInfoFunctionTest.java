package org.traficinfo.dsfunctions;

import com.oracle.bmc.model.BmcException;
import com.fnproject.fn.api.RuntimeContext;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
                "MMM_OSMInfo.json", "MMM_OSMWayMapping.json").stream().sorted().toList(),
                store.read.stream().sorted().toList());
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
            assertEquals(List.of("bad_OSMInfo.json", "good_OSMInfo.json", "good_OSMWayMapping.json"),
                    store.read.stream().sorted().toList());
            assertTrue(store.closed.contains("good_OSMInfo.json"));
        }
    }

    @Test
    public void checksTimeBetweenResourcesButFinishesTheCurrentBoundedBatch() {
        for (Map<String, String> limits : List.of(Map.<String, String>of(),
                Map.of("MAX_LOOP_DURATION_SECONDS", "1", "MAX_WAY_MAPPINGS_PER_RESOURCE", "2"))) {
            Store store = new Store();
            String mappings = "{";
            for (int i = 0; i < 8; i++) {
                mappings += (i == 0 ? "" : ",") + "\"A" + i + "\":{\"latitude\":43,\"longitude\":3}";
            }
            mappings += "}";
            store.objects.put("A_OSMWayMapping.json", mappings);
            store.objects.put("B_OSMWayMapping.json", mappings);
            AtomicLong elapsed = new AtomicLong();
            AtomicInteger lookups = new AtomicInteger();
            OpenStreetMapClient client = new OpenStreetMapClient() {
                @Override public JsonNode reverseLookup(double latitude, double longitude) throws IOException {
                    lookups.incrementAndGet();
                    elapsed.set(TimeUnit.SECONDS.toNanos(limits.isEmpty() ? 200 : 2));
                    return new ObjectMapper().readTree("{\"error\":\"Unable to geocode\"}");
                }
            };
            OSMInfoFunction function = new OSMInfoFunction(store, client, elapsed::get);
            function.configureLimits(context(limits));
            assertEquals("OK", function.handleRequest(""));
            assertEquals(limits.isEmpty() ? 5 : 2, lookups.get());
            assertEquals(2, store.read.size());
            assertEquals(1, store.writes);
        }
    }

    @Test
    public void refreshesTagsForResolvedWaysAfterTheReverseBatch() throws Exception {
        Store store = new Store();
        store.objects.put("A_OSMWayMapping.json", "{"
                + "\"A\":{\"latitude\":43,\"longitude\":3},"
                + "\"B\":{\"latitude\":44,\"longitude\":3},"
                + "\"review\":{\"latitude\":45,\"longitude\":3,\"sourceRoadReference\":\"D 11\"}}");
        List<String> requests = new ArrayList<>();
        OpenStreetMapClient client = new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double latitude, double longitude) throws IOException {
                requests.add("reverse:" + latitude);
                return new ObjectMapper().readTree("{\"osm_type\":\"way\",\"osm_id\":"
                        + (latitude == 45 ? 456 : 123) + ",\"category\":\"highway\",\"type\":\"primary\"}");
            }
            @Override public Map<String, String> getWayTags(long wayId) throws IOException {
                assertEquals(List.of("reverse:43.0", "reverse:44.0", "reverse:45.0"), requests);
                assertEquals(1, store.writes); // Mappings are persisted before tag refresh.
                requests.add("tags:" + wayId);
                return Map.of("maxspeed", "80", "highway", "primary");
            }
        };
        assertEquals("OK", new OSMInfoFunction(store, client, () -> 0L).handleRequest(""));
        assertEquals(List.of("reverse:43.0", "reverse:44.0", "reverse:45.0", "tags:123"), requests);
        assertEquals(2, store.writes);
        JsonNode tags = new ObjectMapper().readTree(store.objects.get("A_OSMInfo.json"));
        assertEquals(1, tags.size());
        assertEquals("80", tags.get("123").get("tags").get("maxspeed").asText());
        JsonNode mappings = new ObjectMapper().readTree(store.objects.get("A_OSMWayMapping.json"));
        assertEquals(123, mappings.get("A").get("osmWayId").asLong());
        assertEquals(123, mappings.get("B").get("osmWayId").asLong());
        assertEquals("NEEDS_REVIEW", mappings.get("review").get("status").asText());
    }

    @Test
    public void rejectsInvalidLimitConfiguration() {
        for (String key : List.of("MAX_LOOP_DURATION_SECONDS", "MAX_WAY_MAPPINGS_PER_RESOURCE")) {
            for (String value : List.of("", "abc", "0", "-1", "1.5", "2147483648")) {
                try {
                    new OSMInfoFunction(new Store()).configureLimits(context(Map.of(key, value)));
                    fail("Expected invalid limit: " + key + "=" + value);
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains(key));
                }
            }
        }
    }

    private static RuntimeContext context(Map<String, String> configuration) {
        return (RuntimeContext) Proxy.newProxyInstance(RuntimeContext.class.getClassLoader(),
                new Class<?>[]{RuntimeContext.class}, (proxy, method, args) -> {
                    assertEquals("getConfigurationByKey", method.getName());
                    return Optional.ofNullable(configuration.get(args[0]));
                });
    }

    private static class Store extends ObjectStoreClient {
        final Map<String, String> objects = new LinkedHashMap<>();
        final List<String> read = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        int writes;
        Store() { super(null, "namespace", "osminfo"); }
        @Override public List<String> listResources() { return List.copyOf(objects.keySet()); }
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
        @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream body)
                throws IOException {
            objects.put(name, new String(body.readAllBytes(), StandardCharsets.UTF_8));
            writes++;
        }
    }
}
