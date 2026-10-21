package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.model.BmcException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class OSMReverseMappingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    public void resolvesBoundedBatchAndCachesRejectedResultsWithoutOverwritingKnownWays() throws Exception {
        Store store = new Store();
        Map<String, OSMWayMapping> mappings = new LinkedHashMap<>();
        for (int lat = 43; lat <= 48; lat++) {
            OSMWayMapping mapping = new OSMWayMapping();
            mapping.setLatitude((double) lat);
            mapping.setLongitude(3.0);
            mapping.setSourceRoadReference("D0011");
            mapping.setFirstSeen("2026-01-01T00:00:00Z");
            mappings.put("counter" + lat, mapping);
        }
        OSMWayMapping known = new OSMWayMapping();
        known.setOsmWayId(77L);
        known.setLatitude(49.0);
        known.setLongitude(3.0);
        mappings.put("known", known);
        mappings.put("noLocation", new OSMWayMapping());
        store.objects.put("mappings.json", JSON.writeValueAsBytes(mappings));
        List<Double> requests = new ArrayList<>();
        OpenStreetMapClient client = new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double latitude, double longitude) throws IOException {
                requests.add(latitude);
                if (latitude == 46) throw new IOException("HTTP 503");
                if (latitude == 44) return JSON.readTree("{\"osm_type\":\"way\",\"osm_id\":44,\"category\":\"building\"}");
                if (latitude == 47) return JSON.readTree("{\"error\":\"Unable to geocode\"}");
                String reference = latitude == 45 ? "M 11" : "D 11";
                return JSON.readTree("{\"osm_type\":\"way\",\"osm_id\":\"123\",\"category\":\"highway\","
                        + "\"type\":\"secondary\",\"namedetails\":{\"ref\":\"" + reference + "\"}}");
            }
            @Override public Map<String, String> getWayTags(long id) {
                throw new AssertionError("Reverse mapping refresh must not request way tags");
            }
        };
        OSMInfoProviderImpl provider = provider(store, client);
        provider.loadCache();
        provider.refreshWayMappings(5);
        assertEquals(List.of(43.0, 44.0, 45.0, 46.0, 47.0), requests);
        JsonNode saved = JSON.readTree(store.objects.get("mappings.json"));
        assertEquals(123, saved.get("counter43").get("osmWayId").asLong());
        assertEquals("MATCHED", saved.get("counter43").get("status").asText());
        assertEquals("2026-01-01T00:00:00Z", saved.get("counter43").get("firstSeen").asText());
        assertEquals("NOT_FOUND", saved.get("counter44").get("status").asText());
        assertEquals("NEEDS_REVIEW", saved.get("counter45").get("status").asText());
        assertTrue(saved.get("counter45").get("osmWayId").isNull());
        assertEquals(123, saved.get("counter45").get("candidateOsmWayId").asLong());
        assertFalse(saved.get("counter46").has("reverseLookupResult"));
        assertFalse(saved.get("counter48").has("lastLookupAt"));
        assertEquals(77, saved.get("known").get("osmWayId").asLong());
        assertEquals(1, store.writes);

        OSMInfoProviderImpl next = provider(store, client);
        next.loadCache();
        next.refreshWayMappings(5);
        // Successful misses/review results are not queried again; new counters precede HTTP retries.
        assertEquals(List.of(43.0, 44.0, 45.0, 46.0, 47.0, 48.0, 46.0), requests);
        saved = JSON.readTree(store.objects.get("mappings.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved.get("counter44")).put("latitude", 44.1);
        store.objects.put("mappings.json", JSON.writeValueAsBytes(saved));
        next.loadCache();
        next.refreshWayMappings(1);
        assertEquals(Double.valueOf(44.1), requests.get(requests.size() - 1));
    }

    @Test
    public void rejectsNonRoadObjectsAndMissingReferenceAndPersistsBeforeInterruption() throws Exception {
        for (String result : List.of(
                "{\"osm_type\":\"node\",\"osm_id\":1,\"category\":\"highway\",\"type\":\"primary\"}",
                "{\"osm_type\":\"way\",\"osm_id\":1,\"category\":\"highway\",\"type\":\"footway\"}",
                "{\"osm_type\":\"way\",\"osm_id\":1,\"category\":\"highway\",\"type\":\"primary\"}")) {
            Store store = new Store();
            store.objects.put("mappings.json", ("{\"A\":{\"latitude\":43,\"longitude\":3,"
                    + "\"sourceRoadReference\":\"D 11\"}}").getBytes(StandardCharsets.UTF_8));
            OpenStreetMapClient client = new OpenStreetMapClient() {
                @Override public JsonNode reverseLookup(double latitude, double longitude) throws IOException {
                    return JSON.readTree(result);
                }
            };
            OSMInfoProviderImpl provider = provider(store, client);
            provider.loadCache();
            provider.refreshWayMappings(1);
            JsonNode saved = JSON.readTree(store.objects.get("mappings.json")).get("A");
            assertTrue(saved.get("osmWayId").isNull());
            assertTrue(saved.has("reverseLookupResult"));
        }
        Store store = new Store();
        store.objects.put("mappings.json", "{\"A\":{\"latitude\":43,\"longitude\":3}}".getBytes(StandardCharsets.UTF_8));
        OSMInfoProviderImpl provider = provider(store, new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double latitude, double longitude) throws InterruptedException {
                throw new InterruptedException("stop");
            }
        });
        provider.loadCache();
        try {
            provider.refreshWayMappings(1);
            fail("Interruption must propagate");
        } catch (InterruptedException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, store.writes);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void appliesReferenceMatrixAndCountsProcessedAndAllStatuses() throws Exception {
        Store store = new Store();
        Map<String, OSMWayMapping> mappings = new LinkedHashMap<>();
        String[] references = {null, "A", "B"};
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                OSMWayMapping mapping = new OSMWayMapping();
                mapping.setLatitude(43.0 + (row * 3 + column) / 100.0);
                mapping.setLongitude(3.0);
                mapping.setSourceRoadReference(references[row]);
                mappings.put(row + "_" + column, mapping);
            }
        }
        mappings.put("noCoordinates", new OSMWayMapping());
        for (String key : List.of("httpError", "noRoad")) {
            OSMWayMapping mapping = new OSMWayMapping();
            mapping.setLatitude(key.equals("httpError") ? 44.0 : 44.1);
            mapping.setLongitude(3.0);
            mappings.put(key, mapping);
        }
        store.objects.put("mappings.json", JSON.writeValueAsBytes(mappings));
        OSMInfoProviderImpl provider = provider(store, new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double latitude, double longitude) throws IOException {
                if (latitude == 44) throw new IOException("HTTP 503");
                if (latitude == 44.1) return JSON.readTree("{\"error\":\"Unable to geocode\"}");
                int index = (int) Math.round((latitude - 43) * 100);
                com.fasterxml.jackson.databind.node.ObjectNode result = JSON.createObjectNode();
                result.put("osm_type", "way");
                result.put("osm_id", 100 + index);
                result.put("category", "highway");
                result.put("type", "primary");
                result.putObject("extratags").put("ref", "WRONG_FIELD");
                if (references[index % 3] != null) {
                    result.putObject("namedetails").put("ref", references[index % 3]);
                }
                return result;
            }
        });
        provider.loadCache();
        provider.refreshWayMappings(100);
        JsonNode saved = JSON.readTree(store.objects.get("mappings.json"));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                JsonNode mapping = saved.get(row + "_" + column);
                boolean matched = row == 0 || row == column;
                assertEquals(matched ? "MATCHED" : "NEEDS_REVIEW", mapping.get("status").asText());
                if (!matched) {
                    assertEquals(column == 0 ? "missing_osm_ref" : "different_ref",
                            mapping.get("reviewReason").asText());
                }
            }
        }
        assertEquals(Map.of(OSMWayMappingStatus.MISSING, 1, OSMWayMappingStatus.MATCHED, 5,
                OSMWayMappingStatus.NEEDS_REVIEW, 4, OSMWayMappingStatus.NOT_FOUND, 1),
                provider.getProcessedWayMappingStatusCounts());
        assertEquals(Map.of(OSMWayMappingStatus.MISSING, 2, OSMWayMappingStatus.MATCHED, 5,
                OSMWayMappingStatus.NEEDS_REVIEW, 4, OSMWayMappingStatus.NOT_FOUND, 1),
                provider.getWayMappingStatusCounts());
        provider.loadCache();
        assertTrue(provider.getProcessedWayMappingStatusCounts().values().stream().allMatch(count -> count == 0));
        assertEquals(Integer.valueOf(5), provider.getWayMappingStatusCounts().get(OSMWayMappingStatus.MATCHED));
    }

    private static OSMInfoProviderImpl provider(Store store, OpenStreetMapClient client) {
        return new OSMInfoProviderImpl(store, client, "tags.json", "mappings.json", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static class Store extends ObjectStoreClient {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        int writes;
        Store() { super(null, "namespace", "osminfo"); }
        @Override public InputStream getResource(String name) {
            if (!objects.containsKey(name)) throw new BmcException(404, "NotFound", "Missing", "request");
            return new ByteArrayInputStream(objects.get(name));
        }
        @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream body)
                throws IOException {
            assertEquals("mappings.json", name);
            objects.put(name, body.readAllBytes());
            writes++;
        }
    }
}
