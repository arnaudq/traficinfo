package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.model.BmcException;
import org.junit.Test;
import org.geojson.Point;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class OSMMappingCacheTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    public void missingObjectCreatesDeduplicatedPlaceholdersWithoutRequests() throws Exception {
        Store store = new Store();
        OSMInfoProviderImpl provider = provider(store, NOW);
        provider.loadCache();
        assertNull(provider.getWayId("DPT34_A", new Point(3.0, 43.0), "D0011"));
        assertNull(provider.getWayId("DPT34_A", new Point(3.001, 43.001), "M11"));
        assertNull(provider.getWayId("DPT34_B", null, null));
        assertEquals(0, store.writes);
        provider.refreshCache(Set.of());
        JsonNode cache = JSON.readTree(store.objects.get("mappings.json"));
        assertEquals(2, cache.size());
        assertTrue(cache.get("DPT34_A").get("osmWayId").isNull());
        assertEquals("MISSING", cache.get("DPT34_A").get("status").asText());
        assertEquals("M11", cache.get("DPT34_A").get("sourceRoadReference").asText());
        assertTrue(cache.get("DPT34_B").get("sourceRoadReference").isNull());
        assertEquals(43.001, cache.get("DPT34_A").get("latitude").asDouble(), 0);
        assertTrue(cache.get("DPT34_B").get("latitude").isNull());
        assertEquals(NOW.toString(), cache.get("DPT34_A").get("firstSeen").asText());
        assertEquals(1, store.writes);
    }

    @Test
    public void preservesSeedMetadataAndDoesNotPromoteReviewCandidates() throws Exception {
        Store store = new Store();
        // Representative seed entries are embedded so tests do not depend on local data/ files.
        store.objects.put("mappings.json", ("{"
                + "\"DPT34_A\":{\"osmWayId\":123,\"latitude\":43,\"longitude\":3,"
                + "\"status\":\"MATCHED\",\"sourceRoadReference\":\"D11\",\"osmRoadReference\":\"D 11\","
                + "\"lastUpdated\":\"2026-10-19T16:09:45Z\",\"validationNote\":\"retained\"},"
                + "\"DPT34_B\":{\"osmWayId\":null,\"candidateOsmWayId\":456,\"latitude\":44,\"longitude\":4,"
                + "\"status\":\"NEEDS_REVIEW\",\"sourceRoadReference\":\"D12\",\"osmRoadReference\":\"M 12\","
                + "\"reviewReason\":\"different_ref\"}}").getBytes(StandardCharsets.UTF_8));
        JsonNode before = JSON.readTree(store.objects.get("mappings.json"));
        OSMInfoProviderImpl provider = provider(store, NOW);
        provider.loadCache();
        before.fields().forEachRemaining(entry -> {
            JsonNode mapping = entry.getValue();
            Long expected = mapping.get("osmWayId").isNull() ? null : mapping.get("osmWayId").asLong();
            assertEquals(expected, provider.getWayId(entry.getKey(),
                    new Point(mapping.get("longitude").asDouble(), mapping.get("latitude").asDouble()),
                    mapping.path("sourceRoadReference").asText(null)));
        });
        provider.refreshCache(Set.of());
        JsonNode after = JSON.readTree(store.objects.get("mappings.json"));
        assertEquals(before.size(), after.size());
        before.fields().forEachRemaining(entry -> entry.getValue().fields().forEachRemaining(field -> {
            JsonNode actual = after.get(entry.getKey()).get(field.getKey());
            if ("latitude".equals(field.getKey()) || "longitude".equals(field.getKey())) {
                assertEquals(field.getValue().asDouble(), actual.asDouble(), 0);
            } else {
                assertEquals(field.getValue(), actual);
            }
        }));
    }

    @Test
    public void manualIdIsUsedOnNextLoadAndFirstSeenIsPreserved() throws Exception {
        Store store = new Store();
        OSMInfoProviderImpl first = provider(store, NOW);
        first.loadCache();
        first.getWayId("A", new Point(3.0, 43.0), "D11");
        first.refreshCache(Set.of());
        OSMInfoProviderImpl second = provider(store, NOW.plusSeconds(86400));
        second.loadCache();
        second.getWayId("A", new Point(3.0, 43.0), null);
        second.refreshCache(Set.of());
        JsonNode saved = JSON.readTree(store.objects.get("mappings.json"));
        assertEquals("D11", saved.get("A").get("sourceRoadReference").asText());
        assertEquals(NOW.toString(), saved.get("A").get("firstSeen").asText());
        assertEquals(NOW.plusSeconds(86400).toString(), saved.get("A").get("lastSeen").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved.get("A")).put("osmWayId", 123);
        store.objects.put("mappings.json", JSON.writeValueAsBytes(saved));
        OSMInfoProviderImpl next = provider(store, NOW);
        next.loadCache();
        assertEquals(Long.valueOf(123), next.getWayId("A", new Point(3.0, 43.0), null));
    }

    @Test
    public void stillSavesLoadedCacheWhenNoEntriesAreMissing() throws Exception {
        Store store = new Store();
        store.objects.put("mappings.json", "{\"A\":{\"osmWayId\":123,\"note\":\"manual\"}}".getBytes(StandardCharsets.UTF_8));
        OSMInfoProviderImpl provider = provider(store, NOW);
        provider.loadCache();
        assertEquals(Long.valueOf(123), provider.getWayId("A", null, null));
        provider.refreshCache(Set.of());
        assertEquals("manual", JSON.readTree(store.objects.get("mappings.json")).get("A").get("note").asText());
        assertEquals(1, store.writes);
    }

    @Test
    public void rejectsInvalidOrForbiddenCacheWithoutWriting() throws Exception {
        Store store = new Store();
        for (String json : new String[]{"invalid", "null", "[]", "{\"A\":5}"}) {
            store.objects.put("mappings.json", json.getBytes(StandardCharsets.UTF_8));
            try {
                provider(store, NOW).loadCache();
                fail("Invalid cache must fail: " + json);
            } catch (IOException expected) {
                assertEquals(0, store.writes);
            }
        }
        store.forbidden = true;
        try {
            provider(store, NOW).loadCache();
            fail("Forbidden cache must fail");
        } catch (BmcException expected) {
            assertEquals(403, expected.getStatusCode());
            assertEquals(0, store.writes);
        }
    }

    private static OSMInfoProviderImpl provider(Store store, Instant now) {
        return new OSMInfoProviderImpl(store, new OpenStreetMapClient() {
            @Override public Map<String, String> getWayTags(long id) {
                throw new AssertionError("Mapping operations must not request way tags");
            }
        }, "tags.json", "mappings.json", Clock.fixed(now, ZoneOffset.UTC));
    }

    private static class Store extends ObjectStoreClient {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        boolean forbidden;
        int writes;
        Store() { super(null, "namespace", "osminfo"); }
        @Override public InputStream getResource(String name) {
            if (forbidden) throw new BmcException(403, "Forbidden", "Denied", "request");
            if (!objects.containsKey(name)) throw new BmcException(404, "NotFound", "Missing", "request");
            return new ByteArrayInputStream(objects.get(name));
        }
        @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream body)
                throws IOException {
            assertEquals("mappings.json", name);
            assertEquals("application/json", type);
            objects.put(name, body.readAllBytes());
            writes++;
        }
    }
}
