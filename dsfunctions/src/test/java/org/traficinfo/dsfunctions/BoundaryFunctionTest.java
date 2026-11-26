package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BoundaryFunctionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void publishesMissingBoundariesThenSkipsFreshDownloads() throws Exception {
        Probe probe = configured();
        assertEquals("OK", probe.handleRequest("{}"));
        assertEquals(5, probe.downloads.size());
        assertEquals(11, probe.objects.size());
        assertEquals(List.of("ns/traficinfo"), probe.clients);
        assertEquals(1, probe.downloads.stream().filter(BoundaryFunction.DEPARTEMENTS_URL::equals).count());
        assertTrue(probe.downloads.contains(URI.create("https://geo.api.gouv.fr/epcis/243400017?format=geojson&geometry=contour")));
        assertTrue(probe.downloads.contains(URI.create("https://geo.api.gouv.fr/epcis/243500139?format=geojson&geometry=contour")));
        JsonNode config = probe.loadMapConfig();
        for (JsonNode definition : config.get("boundaries")) {
            JsonNode feature = JSON.readTree(probe.objects.get(definition.get("geoJsonPath").asText()));
            assertEquals(definition.get("boundaryId"), feature.path("properties").get("boundaryId"));
            assertTrue(List.of("Polygon", "MultiPolygon").contains(feature.path("geometry").path("type").asText()));
        }
        assertFalse(config.get("boundaries").has("departement:75"));
        JsonNode paris = JSON.readTree(probe.objects.get("boundaries/departements/75.geojson"));
        assertEquals("departement:75", paris.path("properties").path("boundaryId").asText());
        JsonNode attribution = JSON.readTree(probe.objects.get("boundaries/attribution.json"));
        assertEquals(9, attribution.path("files").size());
        assertEquals("ODbL-1.0", attribution.path("licence").asText());
        assertEquals(probe.clock.current.toString(), probe.metadata.get("boundaries/epcis/243500139.geojson").get("downloaded-at"));
        assertEquals(probe.clock.current.toString(), probe.metadata.get(BoundaryFunction.DEPARTEMENTS_OBJECT).get("downloaded-at"));
        JsonNode source = JSON.readTree(probe.objects.get(BoundaryFunction.DEPARTEMENTS_OBJECT));
        assertEquals("FeatureCollection", source.path("type").asText());
        assertFalse(source.path("features").get(0).path("properties").has("boundaryId"));
        assertEquals("OK", probe.handleRequest("{}"));
        assertEquals(5, probe.downloads.size());
        assertEquals(11, probe.writes);
        assertEquals(2, probe.closed);
    }

    @Test
    public void selectedEpciNeverProcessesDepartementsOrOtherEpcis() throws Exception {
        Probe probe = configured();
        assertEquals("OK", probe.handleRequest("{\"epciCode\":\"243500139\"}"));
        assertEquals(List.of(URI.create("https://geo.api.gouv.fr/epcis/243500139?format=geojson&geometry=contour")), probe.downloads);
        assertEquals(List.of("boundaries/epcis/243500139.geojson", "boundaries/attribution.json"), List.copyOf(probe.objects.keySet()));
        assertFalse(probe.heads.contains(BoundaryFunction.DEPARTEMENTS_OBJECT));
        assertEquals(1, JSON.readTree(probe.objects.get("boundaries/attribution.json")).path("files").size());
        probe.handleRequest("{\"epciCode\":\"243500139\"}");
        assertEquals(1, probe.downloads.size());
        assertEquals(2, probe.writes);
    }

    @Test
    public void acceptsAnExplicitEpciOutsideTheRegistry() {
        Probe probe = configured();
        probe.handleRequest("{\"epciCode\":\"200000001\"}");
        assertTrue(probe.objects.containsKey("boundaries/epcis/200000001.geojson"));
        assertEquals(1, probe.downloads.size());
    }

    @Test
    public void calendarMonthExpiryPreservesUnselectedAttribution() throws Exception {
        Probe probe = configured();
        probe.handleRequest("{}");
        Instant original = probe.clock.current;
        byte[] other = probe.objects.get("boundaries/epcis/243400017.geojson");
        probe.clock.current = Instant.parse("2027-02-19T12:00:00Z");
        probe.handleRequest("{\"epciCode\":\"243500139\"}");
        assertEquals(5, probe.downloads.size()); // Exactly three months is still fresh.
        probe.clock.current = probe.clock.current.plusNanos(1);
        probe.handleRequest("{\"epciCode\":\"243500139\"}");
        assertEquals(6, probe.downloads.size());
        assertEquals(probe.clock.current.toString(), probe.metadata.get("boundaries/epcis/243500139.geojson").get("downloaded-at"));
        assertArrayEquals(other, probe.objects.get("boundaries/epcis/243400017.geojson"));
        JsonNode attribution = JSON.readTree(probe.objects.get("boundaries/attribution.json"));
        assertEquals(9, attribution.path("files").size());
        for (JsonNode entry : attribution.get("files")) {
            assertEquals(entry.path("boundaryId").asText().equals("epci:243500139") ? probe.clock.current.toString() : original.toString(),
                    entry.path("downloadedAt").asText());
        }
    }

    @Test
    public void missingOrUnusableFetchMetadataTriggersADownload() {
        Probe probe = configured();
        probe.handleRequest("{\"epciCode\":\"243500139\"}");
        String path = "boundaries/epcis/243500139.geojson";
        probe.metadata.get(path).remove("downloaded-at");
        probe.handleRequest("{\"epciCode\":\"243500139\"}");
        probe.metadata.get(path).put("downloaded-at", "invalid");
        probe.handleRequest("{\"epciCode\":\"243500139\"}");
        probe.objects.remove(path);
        probe.handleRequest("{\"epciCode\":\"243500139\"}");
        assertEquals(4, probe.downloads.size());
    }

    @Test
    public void repairsMissingDepartementFromFreshOriginalWithoutRedownloading() {
        Probe probe = configured();
        probe.handleRequest("{}");
        Instant original = probe.clock.current;
        probe.clock.current = Instant.parse("2026-12-19T12:00:00Z");
        probe.objects.remove("boundaries/departements/34.geojson");
        probe.handleRequest("{}");
        assertEquals(5, probe.downloads.size());
        assertEquals(original.toString(), probe.metadata.get("boundaries/departements/34.geojson").get("downloaded-at"));
        assertTrue(probe.reads.contains(BoundaryFunction.DEPARTEMENTS_OBJECT));
        assertEquals(13, probe.writes); // Repaired outline and merged attribution.
    }

    @Test
    public void staleOriginalDatasetRefreshesAllDepartements() {
        Probe probe = configured();
        probe.handleRequest("{}");
        probe.clock.current = Instant.parse("2027-06-19T12:00:00Z");
        probe.handleRequest("{}");
        assertEquals(10, probe.downloads.size());
        assertEquals(22, probe.writes);
        assertEquals(probe.clock.current.toString(), probe.metadata.get(BoundaryFunction.DEPARTEMENTS_OBJECT).get("downloaded-at"));
        assertEquals(probe.clock.current.toString(), probe.metadata.get("boundaries/departements/75.geojson").get("downloaded-at"));
    }

    @Test
    public void invalidGeometryOrWrongTerritoryNeverReplacesItsObject() {
        for (String response : List.of("<html>error</html>", "{}",
                feature("wrong"), feature("34").replace("Polygon", "Point"),
                feature("34").replace("[[[0,0],[1,0],[1,1],[0,0]]]", "[]"))) {
            Probe probe = configured();
            probe.response = response;
            assertThrows(IllegalStateException.class, () -> probe.handleRequest("{}"));
            assertTrue(probe.objects.isEmpty());
            assertEquals(1, probe.closed);
        }
    }

    @Test
    public void downloadAndUploadFailuresStopWithoutRetryOrAttributionPublication() {
        Probe download = configured();
        download.failDownload = true;
        assertThrows(IllegalStateException.class, () -> download.handleRequest("{}"));
        assertEquals(1, download.downloads.size());
        assertTrue(download.objects.isEmpty());
        assertEquals(1, download.closed);
        Probe upload = configured();
        upload.failUpload = true;
        assertThrows(IllegalStateException.class, () -> upload.handleRequest("{}"));
        assertEquals(1, upload.downloads.size());
        assertTrue(upload.objects.isEmpty());
        assertEquals(1, upload.closed);
    }

    @Test
    public void invalidRequestsDoNotCreateClients() {
        Probe probe = configured();
        for (String input : new String[]{null, "", "null", "[]", "{} {}", "{\"boundaryId\":\"epci:243400017\"}",
                "{\"epciCode\":243500139}", "{\"epciCode\":\"REN\"}", "{\"epciCode\":null}",
                "{\"maxAgeMonths\":3}"}) {
            assertThrows(IllegalArgumentException.class, () -> probe.handleRequest(input));
        }
        assertTrue(probe.clients.isEmpty());
        assertTrue(probe.downloads.isEmpty());
    }

    @Test
    public void unsupportedSchemeAndDuplicateDestinationsAreRejected() throws Exception {
        Probe probe = new Probe();
        var config = probe.loadMapConfig();
        var definitions = (com.fasterxml.jackson.databind.node.ObjectNode) config.get("boundaries");
        definitions.set("unknown:34", definitions.get("departement:34"));
        assertThrows(IllegalArgumentException.class, () -> BoundaryFunction.readBoundaries(definitions));
        definitions.remove("unknown:34");
        ((com.fasterxml.jackson.databind.node.ObjectNode) definitions.get("epci:243400017"))
                .put("geoJsonPath", "boundaries/departements/34.geojson");
        assertThrows(IllegalArgumentException.class, () -> BoundaryFunction.readBoundaries(definitions));
    }

    @Test
    public void acceptsMultiPolygons() throws Exception {
        var boundary = new BoundaryFunction.Boundary("departement:34", "34", "unused", URI.create("https://example.org"));
        String input = feature("34").replace("\"Polygon\"", "\"MultiPolygon\"")
                .replace("[[[0,0],[1,0],[1,1],[0,0]]]", "[[[[0,0],[1,0],[1,1],[0,0]]]]");
        assertEquals("MultiPolygon", JSON.readTree(BoundaryFunction.prepareFeature(boundary,
                input.getBytes(StandardCharsets.UTF_8))).path("geometry").path("type").asText());
    }

    @Test
    public void extractsRealDepartementContoursAndPreservesTheirGeometry() throws Exception {
        byte[] source;
        try (InputStream input = getClass().getResourceAsStream("/boundaries/departements-100m.geojson")) {
            source = input.readAllBytes();
        }
        JsonNode collection = JSON.readTree(source);
        for (JsonNode original : collection.get("features")) {
            String code = original.path("properties").path("code").asText();
            var boundary = new BoundaryFunction.Boundary("departement:" + code, code, "unused", BoundaryFunction.DEPARTEMENTS_URL);
            JsonNode output = JSON.readTree(BoundaryFunction.prepareFeature(boundary, source));
            assertEquals("Feature", output.path("type").asText());
            assertEquals(original.get("geometry"), output.get("geometry"));
            assertEquals(boundary.id(), output.path("properties").path("boundaryId").asText());
        }
    }

    @Test
    public void rejectsMissingDuplicateAndMetadataOnlyDepartementResponses() {
        var boundary = new BoundaryFunction.Boundary("departement:34", "34", "unused", BoundaryFunction.DEPARTEMENTS_URL);
        for (String source : List.of("{\"nom\":\"Hérault\",\"code\":\"34\",\"codeRegion\":\"76\"}",
                "{\"type\":\"FeatureCollection\",\"features\":[]}",
                "{\"type\":\"FeatureCollection\",\"features\":[" + feature("30") + "]}",
                "{\"type\":\"FeatureCollection\",\"features\":[" + feature("34") + "," + feature("34") + "]}")) {
            assertThrows(IOException.class, () -> BoundaryFunction.prepareFeature(boundary, source.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String feature(String code) {
        return "{\"type\":\"Feature\",\"properties\":{\"code\":\"" + code
                + "\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,0]]]}}";
    }

    private static Probe configured() {
        Probe probe = new Probe();
        probe.config(new FunctionRuntimeContext(new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"),
                Map.of("NAMESPACE", "ns")));
        return probe;
    }

    private static class TestClock extends Clock {
        Instant current = Instant.parse("2026-11-19T12:00:00Z");

        @Override public Instant instant() { return current; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
    }

    public static class Probe extends BoundaryFunction {
        final List<URI> downloads = new ArrayList<>();
        final List<String> clients = new ArrayList<>();
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final Map<String, Map<String, String>> metadata = new LinkedHashMap<>();
        final List<String> heads = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
        final TestClock clock;

        public Probe() {
            this(new TestClock());
        }

        private Probe(TestClock clock) {
            super(clock);
            this.clock = clock;
        }
        int writes;
        int closed;
        String response;
        boolean failDownload;
        boolean failUpload;

        @Override protected byte[] download(URI url) throws IOException {
            downloads.add(url);
            if (failDownload) throw new IOException("Download failed");
            if (response == null && url.equals(BoundaryFunction.DEPARTEMENTS_URL)) {
                try (InputStream input = getClass().getResourceAsStream("/boundaries/departements-100m.geojson")) {
                    var departments = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(input);
                    ((com.fasterxml.jackson.databind.node.ArrayNode) departments.get("features"))
                            .add(JSON.readTree(feature("35")));
                    return JSON.writeValueAsBytes(departments);
                }
            }
            String code = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
            return (response == null ? feature(code) : response).getBytes(StandardCharsets.UTF_8);
        }

        @Override protected ObjectStore createObjectStoreClient(String bucket) {
            assertTrue(useResourcePrincipal());
            clients.add(namespace() + "/" + bucket);
            return new StubObjectStore() {
                @Override public void putResource(String name, String contentType, ObjectMetadata metadata,
                                                  InputStream input) throws IOException {
                    if (failUpload) throw new IOException("Upload failed");
                    assertEquals(name.endsWith(".geojson") ? "application/geo+json" : "application/json", contentType);
                    objects.put(name, input.readAllBytes());
                    Probe.this.metadata.put(name, new LinkedHashMap<>(metadata.getMetadata()));
                    writes++;
                }
                @Override public ObjectMetadata getResourceMetadata(String name) {
                    heads.add(name);
                    return objects.containsKey(name) ? new ObjectMetadata(new LinkedHashMap<>(metadata.get(name))) : null;
                }
                @Override public InputStream getResource(String name) throws IOException {
                    reads.add(name);
                    if (!objects.containsKey(name)) throw new IOException("Missing object: " + name);
                    return new ByteArrayInputStream(objects.get(name));
                }
                @Override public void close() { closed++; }
            };
        }
    }
}
