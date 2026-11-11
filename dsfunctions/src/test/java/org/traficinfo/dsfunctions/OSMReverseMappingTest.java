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
        assertEquals(6, provider.getPendingReverseLookupCount());
        assertTrue(requests.isEmpty()); // Counting is cache-only.
        provider.refreshWayMappings(5);
        assertEquals(2, provider.getPendingReverseLookupCount()); // One new record and one HTTP failure.
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
        assertEquals(2, next.getPendingReverseLookupCount());
        next.refreshWayMappings(5);
        assertEquals(1, next.getPendingReverseLookupCount());
        // Successful misses/review results are not queried again; new counters precede HTTP retries.
        assertEquals(List.of(43.0, 44.0, 45.0, 46.0, 47.0, 48.0, 46.0), requests);
        saved = JSON.readTree(store.objects.get("mappings.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved.get("counter44")).put("latitude", 44.1);
        store.objects.put("mappings.json", JSON.writeValueAsBytes(saved));
        next.loadCache();
        assertEquals(2, next.getPendingReverseLookupCount()); // Changed coordinates invalidate a cached rejection.
        next.refreshWayMappings(1);
        assertEquals(Double.valueOf(44.1), requests.get(requests.size() - 1));
    }

    @Test
    public void rejectsNonRoadObjectsAndMissingReferenceAndPersistsBeforeInterruption() throws Exception {
        for (String result : List.of(
                "{\"osm_type\":\"node\",\"osm_id\":1,\"category\":\"highway\",\"type\":\"primary\"}",
                "{\"osm_type\":\"way\",\"osm_id\":1,\"category\":\"highway\",\"type\":\"steps\"}",
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
    public void acceptsRoadHighwayTagOnBoundaryWayAndStillValidatesReferenceAndObject() throws Exception {
        for (String scenario : List.of("matched", "different_ref", "steps", "node", "invalid_id", "no_highway")) {
            var result = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(
                    "{\"osm_type\":\"way\",\"osm_id\":35141862,\"category\":\"boundary\","
                    + "\"type\":\"administrative\",\"name\":\"Route d'Ouville\","
                    + "\"extratags\":{\"highway\":\"primary\",\"maxspeed\":\"80\"},"
                    + "\"namedetails\":{\"ref\":\"D 925\"}}");
            switch (scenario) {
                case "different_ref" -> ((com.fasterxml.jackson.databind.node.ObjectNode) result.get("namedetails"))
                        .put("ref", "D 926");
                case "steps" -> ((com.fasterxml.jackson.databind.node.ObjectNode) result.get("extratags"))
                        .put("highway", "steps");
                case "node" -> result.put("osm_type", "node");
                case "invalid_id" -> result.put("osm_id", 0);
                case "no_highway" -> result.remove("extratags");
                default -> { }
            }
            Store store = new Store();
            OSMWayMapping mapping = new OSMWayMapping();
            mapping.setLatitude(49.8923906373954);
            mapping.setLongitude(1.03978726868645);
            mapping.setSourceRoadReference("D0925");
            store.objects.put("mappings.json", JSON.writeValueAsBytes(Map.of("DPT76_237", mapping)));
            OSMInfoProviderImpl provider = provider(store, new OpenStreetMapClient() {
                @Override public JsonNode reverseLookup(double latitude, double longitude) {
                    return result;
                }
            });
            provider.loadCache();
            provider.refreshWayMappings(1);
            JsonNode saved = JSON.readTree(store.objects.get("mappings.json")).get("DPT76_237");
            String expected = scenario.equals("matched") ? "MATCHED"
                    : scenario.equals("different_ref") ? "NEEDS_REVIEW" : "NOT_FOUND";
            assertEquals(scenario, expected, saved.get("status").asText());
            assertEquals(result, saved.get("reverseLookupResult"));
            if (scenario.equals("matched")) {
                assertEquals(35141862L, saved.get("osmWayId").asLong());
                assertEquals("D 925", saved.get("osmRoadReference").asText());
            } else {
                assertTrue(saved.get("osmWayId").isNull());
                assertEquals(scenario.equals("different_ref") ? "different_ref" : "no_road_way",
                        saved.get("reviewReason").asText());
            }
        }
    }

    @Test
    public void acceptsNonMotorwayHighwayTypesThroughEitherClassificationWithoutAccessChecks() throws Exception {
        for (String highway : List.of("footway", "cycleway", "path", "pedestrian", "track")) {
            for (boolean extraTags : List.of(false, true)) {
                for (String referenceCase : List.of("absent", "matching", "different")) {
                    Store store = new Store();
                    OSMWayMapping mapping = new OSMWayMapping();
                    mapping.setLatitude(43.5);
                    mapping.setLongitude(3.2);
                    if (!referenceCase.equals("absent")) {
                        mapping.setSourceRoadReference(referenceCase.equals("matching") ? "D0123" : "D0124");
                    }
                    store.objects.put("mappings.json", JSON.writeValueAsBytes(Map.of("counter", mapping)));
                    var result = JSON.createObjectNode();
                    result.put("osm_type", "way");
                    result.put("osm_id", 123);
                    result.put("category", extraTags ? "boundary" : "highway");
                    result.put("type", extraTags ? "administrative" : highway);
                    if (extraTags) result.putObject("extratags").put("highway", highway);
                    if (!referenceCase.equals("absent")) result.putObject("namedetails").put("ref", "D 123");
                    OSMInfoProviderImpl provider = provider(store, new OpenStreetMapClient() {
                        @Override public JsonNode reverseLookup(double latitude, double longitude) {
                            return result;
                        }
                    });
                    provider.loadCache();
                    provider.refreshWayMappings(1);
                    JsonNode saved = JSON.readTree(store.objects.get("mappings.json")).get("counter");
                    String context = highway + ", extraTags=" + extraTags + ", reference=" + referenceCase;
                    assertEquals(context, referenceCase.equals("different") ? "NEEDS_REVIEW" : "MATCHED",
                            saved.get("status").asText());
                    if (referenceCase.equals("different")) {
                        assertEquals("different_ref", saved.get("reviewReason").asText());
                        assertTrue(saved.get("osmWayId").isNull());
                    } else {
                        assertEquals(context, 123, saved.get("osmWayId").asLong());
                    }
                }
            }
        }
    }

    @Test
    public void missingStatusReplacesCachedOutcomeAndStillRequiresCoordinatesAndNoAssignedWay() throws Exception {
        for (String outcome : List.of("MATCHED", "NEEDS_REVIEW", "NOT_FOUND")) {
            Store store = new Store();
            var mappings = JSON.createObjectNode();
            var pending = mappings.putObject("reset");
            pending.put("status", "MISSING").put("latitude", 43.5).put("longitude", 3.2)
                    .put("lastLookupLatitude", 43.5).put("lastLookupLongitude", 3.2).put("sourceRoadReference", "D0123");
            pending.putObject("reverseLookupResult").put("error", "Old lookup result");
            for (String cachedStatus : List.of("NEEDS_REVIEW", "NOT_FOUND")) {
                var cached = pending.deepCopy();
                cached.put("status", cachedStatus);
                mappings.set(cachedStatus, cached);
            }
            mappings.set("noCoordinates", pending.deepCopy().putNull("latitude"));
            mappings.set("invalidCoordinates", pending.deepCopy().put("latitude", 91));
            mappings.set("assigned", pending.deepCopy().put("osmWayId", 321));
            store.objects.put("mappings.json", JSON.writeValueAsBytes(mappings));
            var replacement = JSON.createObjectNode();
            if (outcome.equals("NOT_FOUND")) {
                replacement.put("error", "New lookup found no road");
            } else {
                replacement.put("osm_type", "way").put("osm_id", 123).put("category", "highway").put("type", "primary");
                replacement.putObject("namedetails").put("ref", outcome.equals("MATCHED") ? "D 123" : "D 124");
            }
            List<Double> requests = new ArrayList<>();
            OSMInfoProviderImpl provider = provider(store, new OpenStreetMapClient() {
                @Override public JsonNode reverseLookup(double latitude, double longitude) {
                    requests.add(latitude);
                    return replacement;
                }
            });
            provider.loadCache();
            assertEquals(1, provider.getPendingReverseLookupCount());
            assertTrue(requests.isEmpty());
            provider.refreshWayMappings(10);
            assertEquals(List.of(43.5), requests);
            JsonNode saved = JSON.readTree(store.objects.get("mappings.json"));
            assertEquals(outcome, saved.get("reset").get("status").asText());
            assertEquals(replacement, saved.get("reset").get("reverseLookupResult"));
            assertEquals(mappings.get("NOT_FOUND").get("reverseLookupResult"),
                    saved.get("NOT_FOUND").get("reverseLookupResult"));
            assertEquals(321, saved.get("assigned").get("osmWayId").asLong());
            provider.loadCache();
            assertEquals(0, provider.getPendingReverseLookupCount());
            provider.refreshWayMappings(10);
            assertEquals(List.of(43.5), requests);
        }
    }

    @Test
    public void failedExplicitRetryRetainsPreviousResponseAndRemainsEligible() throws Exception {
        Store store = new Store();
        var mapping = JSON.createObjectNode();
        mapping.put("status", "MISSING").put("latitude", 43.5).put("longitude", 3.2)
                .put("lastLookupLatitude", 43.5).put("lastLookupLongitude", 3.2);
        var previous = mapping.putObject("reverseLookupResult").put("error", "Old lookup result").deepCopy();
        store.objects.put("mappings.json", JSON.writeValueAsBytes(Map.of("reset", mapping)));
        List<Double> requests = new ArrayList<>();
        var replacement = JSON.createObjectNode().put("error", "New lookup result");
        OpenStreetMapClient client = new OpenStreetMapClient() {
            @Override public JsonNode reverseLookup(double latitude, double longitude) throws IOException {
                requests.add(latitude);
                if (requests.size() == 1) throw new IOException("HTTP 503");
                return replacement;
            }
        };
        OSMInfoProviderImpl provider = provider(store, client);
        provider.loadCache();
        provider.refreshWayMappings(1);
        JsonNode failed = JSON.readTree(store.objects.get("mappings.json")).get("reset");
        assertEquals("MISSING", failed.get("status").asText());
        assertEquals(previous, failed.get("reverseLookupResult"));
        provider.loadCache();
        assertEquals(1, provider.getPendingReverseLookupCount());
        provider.refreshWayMappings(1);
        JsonNode saved = JSON.readTree(store.objects.get("mappings.json")).get("reset");
        assertEquals("NOT_FOUND", saved.get("status").asText());
        assertEquals(replacement, saved.get("reverseLookupResult"));
        assertEquals(List.of(43.5, 43.5), requests);
        assertEquals(0, provider.getPendingReverseLookupCount());
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

    @Test
    public void normalizesLeadingZeroesInRoadAndExtensionNumbers() throws Exception {
        String[] sourceReferences = {"D0111E01", "D0111E01", "D0111E01", "D0051E05",
                "D0002E02", "D0908E02", "D0111E01", "D0111E01"};
        String[] osmReferences = {"D 111E1", "d-00111e0001", "D 999;D 111E1", "D 51E5",
                "D 2E2", "D 908E2", "D 111E2", "M 111E1"};
        for (int i = 0; i < osmReferences.length; i++) {
            Store store = new Store();
            OSMWayMapping mapping = new OSMWayMapping();
            mapping.setLatitude(43.633493);
            mapping.setLongitude(3.69301);
            mapping.setSourceRoadReference(sourceReferences[i]);
            store.objects.put("mappings.json", JSON.writeValueAsBytes(Map.of("DPT34_Tou.034.3116.00.3", mapping)));
            String osmReference = osmReferences[i];
            OSMInfoProviderImpl provider = provider(store, new OpenStreetMapClient() {
                @Override public JsonNode reverseLookup(double latitude, double longitude) {
                    com.fasterxml.jackson.databind.node.ObjectNode result = JSON.createObjectNode();
                    result.put("osm_type", "way");
                    result.put("osm_id", 30754670);
                    result.put("category", "highway");
                    result.put("type", "secondary");
                    result.putObject("namedetails").put("ref", osmReference);
                    return result;
                }
            });
            provider.loadCache();
            provider.refreshWayMappings(1);
            JsonNode saved = JSON.readTree(store.objects.get("mappings.json")).get("DPT34_Tou.034.3116.00.3");
            assertEquals(sourceReferences[i] + " versus " + osmReference, i < 6 ? "MATCHED" : "NEEDS_REVIEW", saved.get("status").asText());
            if (i < 6) {
                assertEquals(30754670L, saved.get("osmWayId").asLong());
            } else {
                assertEquals("different_ref", saved.get("reviewReason").asText());
            }
        }
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
