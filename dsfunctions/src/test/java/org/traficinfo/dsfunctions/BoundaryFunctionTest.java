package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BoundaryFunctionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void publishesAllDepartementsAndConfiguredEpcisOnEveryInvocation() throws Exception {
        Probe probe = configured();
        assertEquals("OK", probe.handleRequest("{}"));
        assertEquals(4, probe.downloads.size());
        assertEquals(8, probe.objects.size());
        assertEquals(List.of("ns/traficinfo"), probe.clients);
        assertEquals(1, probe.downloads.stream().filter(BoundaryFunction.DEPARTEMENTS_URL::equals).count());
        assertTrue(probe.downloads.contains(URI.create("https://geo.api.gouv.fr/epcis/243400017?format=geojson&geometry=contour")));
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
        assertEquals(7, attribution.path("files").size());
        assertEquals("ODbL-1.0", attribution.path("licence").asText());
        assertEquals("OK", probe.handleRequest("{}"));
        assertEquals(8, probe.downloads.size());
        assertEquals(16, probe.writes);
        assertEquals(2, probe.closed);
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
        for (String input : new String[]{null, "", "null", "[]", "{} {}", "{\"boundaryId\":\"epci:243400017\"}"}) {
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

    public static class Probe extends BoundaryFunction {
        final List<URI> downloads = new ArrayList<>();
        final List<String> clients = new ArrayList<>();
        final Map<String, byte[]> objects = new LinkedHashMap<>();
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
                    return input.readAllBytes();
                }
            }
            String code = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
            return (response == null ? feature(code) : response).getBytes(StandardCharsets.UTF_8);
        }

        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String ns, String bucket) {
            assertTrue(principal);
            clients.add(ns + "/" + bucket);
            return new ObjectStoreClient(null, ns, bucket) {
                @Override public void putResource(String name, String contentType, OOSResourceMetadata metadata,
                                                  InputStream input) throws IOException {
                    if (failUpload) throw new IOException("Upload failed");
                    assertEquals(name.endsWith(".geojson") ? "application/geo+json" : "application/json", contentType);
                    objects.put(name, input.readAllBytes());
                    writes++;
                }
                @Override public void close() { closed++; }
            };
        }
    }
}
