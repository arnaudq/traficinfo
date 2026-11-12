package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.model.BmcException;
import org.geojson.Point;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.traficinfo.dsfunctions.FunctionFailureAssertions.assertFunctionFailure;

public class OSMRequestWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    @Test
    public void collectorOnlyWritesRequestsEvenWhenCachesChangeAfterItReadsThem() throws Exception {
        Store store = new Store();
        store.put("MRN_OSMWayMapping.json", "{\"known\":{\"osmWayId\":123,\"status\":\"MATCHED\"}}");
        store.put("MRN_OSMInfo.json", "{\"123\":{\"lastUpdated\":\"2026-11-11T10:00:00Z\",\"tags\":{\"maxspeed\":\"50\"}},"
                + "\"456\":{\"lastUpdated\":\"2025-01-01T00:00:00Z\",\"tags\":{\"maxspeed\":\"30\"}}}");
        var provider = provider(store, noHttp(), "MRN");
        provider.loadCache();
        var collector = new OSMRequestCollector(provider);
        assertEquals(Long.valueOf(123), collector.getWayId("known", null, null, null));
        assertEquals(50, collector.getWayInfo(123).getMaximumSpeed());
        assertEquals(30, collector.getWayInfo(456).getMaximumSpeed());
        assertNull(collector.getWayInfo(789));
        assertNull(collector.getWayId("pending", new Point(3, 43), "D0011", null));
        assertNull(collector.getWayId("pending", new Point(3.1, 43.1), "D0012", null));
        // Simulate the OSM function saving newer caches after GeoJSON's initial reads.
        store.put("MRN_OSMWayMapping.json", "{\"pending\":{\"osmWayId\":999,\"status\":\"MATCHED\"}}");
        byte[] newerMappings = store.objects.get("MRN_OSMWayMapping.json").clone();
        byte[] tags = store.objects.get("MRN_OSMInfo.json").clone();
        collector.publish("MRN");
        assertEquals(List.of("MRN_OSMRequests.json"), store.writes);
        assertArrayEquals(newerMappings, store.objects.get("MRN_OSMWayMapping.json"));
        assertArrayEquals(tags, store.objects.get("MRN_OSMInfo.json"));
        JsonNode request = store.json("MRN_OSMRequests.json");
        assertEquals(1, request.get("reverseLookups").size());
        assertEquals(43.1, request.get("reverseLookups").get("pending").get("latitude").asDouble(), 0);
        assertEquals(List.of(456L, 789L), JSON.convertValue(request.get("wayTagRefreshes"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {}));
        provider.loadCache();
        new OSMRequestCollector(provider).publish("MRN");
        assertEquals(0, store.json("MRN_OSMRequests.json").get("reverseLookups").size());
        assertEquals(0, store.json("MRN_OSMRequests.json").get("wayTagRefreshes").size());
    }

    @Test
    public void requestOnlySourcesResolveAndFetchTagsAndRepeatedSnapshotsAreSkipped() throws Exception {
        Store store = new Store();
        store.put("MRN_OSMRequests.json", "{\"reverseLookups\":{\"counter\":{\"latitude\":43,\"longitude\":3,"
                + "\"sourceRoadReference\":\"D0011\"}},\"wayTagRefreshes\":[]}");
        store.put("MMM_OSMRequests.json", "{\"reverseLookups\":{},\"wayTagRefreshes\":[456]}");
        byte[] reverseRequest = store.objects.get("MRN_OSMRequests.json").clone();
        byte[] directRequest = store.objects.get("MMM_OSMRequests.json").clone();
        List<String> http = new ArrayList<>();
        OpenStreetMapClient client = new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double lat, double lon) throws IOException {
                http.add("reverse");
                return JSON.readTree("{\"osm_type\":\"way\",\"osm_id\":123,\"category\":\"highway\","
                        + "\"type\":\"primary\",\"namedetails\":{\"ref\":\"D 11\"}}");
            }
            @Override public Map<String, String> getWayTags(long id) {
                http.add("tags:" + id);
                return Map.of("maxspeed", "50");
            }
        };
        var function = new OSMInfoFunction(store, client, () -> 0L);
        assertEquals("OK", function.handleRequest(""));
        assertEquals("MATCHED", store.json("MRN_OSMWayMapping.json").get("counter").get("status").asText());
        assertEquals("50", store.json("MRN_OSMInfo.json").get("123").get("tags").get("maxspeed").asText());
        assertEquals("50", store.json("MMM_OSMInfo.json").get("456").get("tags").get("maxspeed").asText());
        assertFalse(store.objects.containsKey("MMM_OSMWayMapping.json"));
        assertEquals(3, http.size());
        assertTrue(http.containsAll(List.of("reverse", "tags:123", "tags:456")));
        assertArrayEquals(reverseRequest, store.objects.get("MRN_OSMRequests.json"));
        assertArrayEquals(directRequest, store.objects.get("MMM_OSMRequests.json"));
        assertTrue(store.writes.stream().noneMatch(name -> name.endsWith(OSMRequests.SUFFIX)));
        assertEquals("OK", function.handleRequest(""));
        assertEquals(3, http.size());
        assertTrue(store.deleted.isEmpty()); // OSM never acknowledges by deleting request snapshots.
    }

    @Test
    public void repeatedUnresolvedRequestsReuseNegativeAndReviewOutcomes() throws Exception {
        Store store = new Store();
        store.put("MRN_OSMRequests.json", "{\"reverseLookups\":{"
                + "\"review\":{\"latitude\":43,\"longitude\":3,\"sourceRoadReference\":\"D11\"},"
                + "\"notFound\":{\"latitude\":44,\"longitude\":3},"
                + "\"noLocation\":{\"latitude\":null,\"longitude\":null}},\"wayTagRefreshes\":[]}");
        List<Double> requested = new ArrayList<>();
        OpenStreetMapClient client = new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double lat, double lon) throws IOException {
                requested.add(lat);
                return lat == 44 ? JSON.readTree("{\"error\":\"No road\"}")
                        : JSON.readTree("{\"osm_type\":\"way\",\"osm_id\":123,\"category\":\"highway\","
                                + "\"type\":\"primary\",\"namedetails\":{\"ref\":\"M 11\"}}");
            }
            @Override public Map<String, String> getWayTags(long id) { throw new AssertionError("No approved way"); }
        };
        var function = new OSMInfoFunction(store, client, () -> 0L);
        assertEquals("OK", function.handleRequest(""));
        assertEquals(2, requested.size());
        assertEquals("NEEDS_REVIEW", store.json("MRN_OSMWayMapping.json").get("review").get("status").asText());
        assertEquals("NOT_FOUND", store.json("MRN_OSMWayMapping.json").get("notFound").get("status").asText());
        assertEquals("MISSING", store.json("MRN_OSMWayMapping.json").get("noLocation").get("status").asText());
        assertEquals("OK", function.handleRequest(""));
        assertEquals(2, requested.size());
        assertEquals(1, store.json(OSMWayMappingReviewStore.OBJECT_NAME).get("MRN").size());
        assertEquals(1, store.json(OSMWayMappingReviewStore.NOT_FOUND_OBJECT_NAME).get("MRN").size());
    }

    @Test
    public void failedExportRetainsSubmissionAndRetryIsIdempotentAfterMappingWasSaved() throws Exception {
        Store store = new Store();
        store.put("MRN_OSMWayMapping.json", "{\"counter\":{\"status\":\"NOT_FOUND\","
                + "\"reverseLookupResult\":{\"error\":\"No road\"}}}");
        store.put(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME,
                "{\"MRN\":{\"counter\":{\"status\":\"MATCHED\",\"candidateOsmWayId\":789}}}");
        List<Long> fetched = new ArrayList<>();
        OpenStreetMapClient client = new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double lat, double lon) { throw new AssertionError(); }
            @Override public Map<String, String> getWayTags(long id) {
                fetched.add(id);
                return Map.of("maxspeed", "50");
            }
        };
        var function = new OSMInfoFunction(store, client, () -> 0L);
        store.failWrite = OSMWayMappingReviewStore.NOT_FOUND_OBJECT_NAME;
        assertFunctionFailure(() -> function.handleRequest(""));
        assertTrue(store.objects.containsKey(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME));
        assertEquals(789, store.json("MRN_OSMWayMapping.json").get("counter").get("osmWayId").asLong());
        assertTrue(store.deleted.isEmpty());
        store.failWrite = null;
        assertEquals("OK", function.handleRequest(""));
        assertFalse(store.objects.containsKey(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME));
        assertEquals(List.of(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME), store.deleted);
        assertEquals(List.of(789L), fetched);
        assertEquals(0, store.json(OSMWayMappingReviewStore.NOT_FOUND_OBJECT_NAME).get("MRN").size());
        assertEquals(0, store.json(OSMWayMappingReviewStore.OBJECT_NAME).get("MRN").size());
        assertEquals("No road", store.json("MRN_OSMWayMapping.json").get("counter")
                .get("reverseLookupResult").get("error").asText());
    }

    @Test
    public void processesBothSubmissionFilesAndRetriesOnlyTheFileWhoseDeletionFailed() throws Exception {
        Store store = new Store();
        store.put("MRN_OSMWayMapping.json", "{\"needsReview\":{\"status\":\"NEEDS_REVIEW\"},"
                + "\"notFound\":{\"status\":\"NOT_FOUND\"}}");
        store.put(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME,
                "{\"MRN\":{\"needsReview\":{\"status\":\"MATCHED\",\"candidateOsmWayId\":123}}}");
        store.put(OSMWayMappingReviewStore.NOT_FOUND_MERGED_OBJECT_NAME,
                "{\"MRN\":{\"notFound\":{\"status\":\"MATCHED\",\"candidateOsmWayId\":456}}}");
        List<Long> fetched = new ArrayList<>();
        OpenStreetMapClient client = new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double lat, double lon) { throw new AssertionError(); }
            @Override public Map<String, String> getWayTags(long id) {
                fetched.add(id);
                return Map.of("maxspeed", "50");
            }
        };
        var function = new OSMInfoFunction(store, client, () -> 0L);
        store.failDelete = OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME;
        assertFunctionFailure(() -> function.handleRequest(""));
        assertEquals(123, store.json("MRN_OSMWayMapping.json").get("needsReview").get("osmWayId").asLong());
        assertEquals(456, store.json("MRN_OSMWayMapping.json").get("notFound").get("osmWayId").asLong());
        assertEquals(List.of(123L, 456L), fetched);
        assertTrue(store.objects.containsKey(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME));
        assertFalse(store.objects.containsKey(OSMWayMappingReviewStore.NOT_FOUND_MERGED_OBJECT_NAME));
        assertEquals(List.of(OSMWayMappingReviewStore.NOT_FOUND_MERGED_OBJECT_NAME), store.deleted);
        assertEquals(0, store.json(OSMWayMappingReviewStore.OBJECT_NAME).get("MRN").size());
        assertEquals(0, store.json(OSMWayMappingReviewStore.NOT_FOUND_OBJECT_NAME).get("MRN").size());
        store.failDelete = null;
        assertEquals("OK", function.handleRequest(""));
        assertFalse(store.objects.containsKey(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME));
        assertEquals(List.of(123L, 456L), fetched);
        assertEquals(List.of(OSMWayMappingReviewStore.NOT_FOUND_MERGED_OBJECT_NAME,
                OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME), store.deleted);
    }

    @Test
    public void generatedReviewFilesAreNotApprovalInputs() throws Exception {
        Store store = new Store();
        store.put("MRN_OSMWayMapping.json", "{\"counter\":{\"status\":\"NOT_FOUND\"}}");
        store.put(OSMWayMappingReviewStore.NOT_FOUND_OBJECT_NAME,
                "{\"MRN\":{\"counter\":{\"status\":\"MATCHED\",\"candidateOsmWayId\":789}}}");
        assertEquals("OK", new OSMInfoFunction(store, noHttp(), () -> 0L).handleRequest(""));
        assertEquals("NOT_FOUND", store.json("MRN_OSMWayMapping.json").get("counter").get("status").asText());
        assertEquals("NOT_FOUND", store.json(OSMWayMappingReviewStore.NOT_FOUND_OBJECT_NAME)
                .get("MRN").get("counter").get("status").asText());
    }

    @Test
    public void submissionUploadedAfterStartupIsNotDeleted() throws Exception {
        Store store = new Store() {
            @Override public List<String> listResources() {
                try {
                    put(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME, "{\"MRN\":{\"counter\":{\"status\":\"MATCHED\",\"candidateOsmWayId\":789}}}");
                } catch (IOException impossible) { throw new AssertionError(impossible); }
                return super.listResources();
            }
        };
        assertEquals("OK", new OSMInfoFunction(store, noHttp(), () -> 0L).handleRequest(""));
        assertTrue(store.objects.containsKey(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME));
        assertTrue(store.deleted.isEmpty());
    }

    @Test
    public void invalidRequestsAndUnknownApprovalsFailWithoutLosingSubmission() throws Exception {
        for (String request : List.of("null", "[]", "{}", "{} trailing",
                "{\"reverseLookups\":null,\"wayTagRefreshes\":[]}",
                "{\"reverseLookups\":{\"counter\":null},\"wayTagRefreshes\":[]}",
                "{\"reverseLookups\":{},\"wayTagRefreshes\":[-1]}",
                "{\"reverseLookups\":{},\"wayTagRefreshes\":[1.5]}")) {
            Store store = new Store();
            store.objects.put("MRN_OSMRequests.json", request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFunctionFailure(() -> new OSMInfoFunction(store, noHttp(), () -> 0L).handleRequest(""));
            assertTrue(store.writes.isEmpty());
        }
        Store store = new Store();
        store.put(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME,
                "{\"unknown\":{\"counter\":{\"status\":\"MATCHED\",\"candidateOsmWayId\":789}}}");
        assertFunctionFailure(() -> new OSMInfoFunction(store, noHttp(), () -> 0L).handleRequest(""));
        assertTrue(store.objects.containsKey(OSMWayMappingReviewStore.NEEDS_REVIEW_MERGED_OBJECT_NAME));
        assertTrue(store.deleted.isEmpty());
    }

    private static OSMInfoProviderImpl provider(Store store, OpenStreetMapClient client, String source) {
        return new OSMInfoProviderImpl(store, client, source + "_OSMInfo.json", source + "_OSMWayMapping.json",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OpenStreetMapClient noHttp() {
        return new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double lat, double lon) { throw new AssertionError("No reverse HTTP expected"); }
            @Override public Map<String, String> getWayTags(long id) { throw new AssertionError("No tag HTTP expected"); }
        };
    }

    private static class Store extends ObjectStoreClient {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        String failWrite;
        String failDelete;
        Store() { super(null, "namespace", "osminfo"); }
        void put(String name, String json) throws IOException { objects.put(name, JSON.writeValueAsBytes(JSON.readTree(json))); }
        JsonNode json(String name) throws IOException { return JSON.readTree(objects.get(name)); }
        @Override public List<String> listResources() { return new ArrayList<>(objects.keySet()); }
        @Override public InputStream getResource(String name) {
            if (!objects.containsKey(name)) throw new BmcException(404, "NotFound", "missing", "request");
            return new ByteArrayInputStream(objects.get(name));
        }
        @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream body) throws IOException {
            if (name.equals(failWrite)) throw new IOException("Write failed: " + name);
            writes.add(name);
            objects.put(name, body.readAllBytes());
        }
        @Override public void deleteResource(String name) {
            if (name.equals(failDelete)) throw new IllegalStateException("Delete failed: " + name);
            deleted.add(name);
            objects.remove(name);
        }
    }
}
