package org.traficinfo.dsfunctions.dpt76;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;
import org.traficinfo.dsfunctions.DataSourceSettings;
import org.traficinfo.dsfunctions.DataSourceTestSettings;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OOSResourceMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** All downloads are replaced by a tiny synthetic GeoJSON. No Seine-Maritime data is fetched. */
public class DataSourceFunctionTest {
    private static final URI GEOJSON_URI = URI.create(DataSourceTestSettings.settings("DPT76").inputs().get(0).url());
    private static final byte[] GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    public void fnUsesConfiguredDownloadAndMetadata() {
        Probe function = configured(Map.of());
        assertEquals("/dsdpt76", function.bucket);
        assertEquals("OK", function.handleRequest(""));
        assertEquals(List.of(GEOJSON_URI), function.requests);
        assertEquals(List.of("DPT76_Comptage_2022.geojson"), function.writes);
        assertArrayEquals(GEOJSON, function.contents.get("DPT76_Comptage_2022.geojson"));
        OOSResourceMetadata metadata = function.metadata.get("DPT76_Comptage_2022.geojson");
        assertEquals(DataSourceTestSettings.settings("DPT76").datasetId(),
                metadata.getMetadata().get("dataset-id"));
        assertEquals("DPT76", metadata.getMetadata().get("datasource-id"));
        assertEquals(GEOJSON_URI.toString(), metadata.getMetadata().get("source-url"));
        assertTrue(function.closed);
    }

    @Test
    public void configuredDatasetAndBucketAreUsed() throws Exception {
        DataSourceSettings settings = new DataSourceSettings("DPT76", "dpt76-later", List.of(
                new DataSourceSettings.Input("https://example.invalid/later.geojson", "DPT76_Comptage_2022.geojson", "application/geo+json")));
        Probe function = configured(Map.of("NAMESPACE", "testns", "BUCKETNAME", "custom-dpt76",
                DataSourceSettings.CONFIG_KEY, new ObjectMapper().writeValueAsString(settings)));
        function.handleRequest("");
        assertEquals("testns/custom-dpt76", function.bucket);
        assertEquals(URI.create("https://example.invalid/later.geojson"), function.requests.get(0));
        assertEquals("dpt76-later", function.metadata.get("DPT76_Comptage_2022.geojson").getMetadata().get("dataset-id"));
    }

    @Test
    public void unchangedGeoJsonIsNotUploadedAgain() {
        Probe function = configured(Map.of());
        function.handleRequest("");
        function.writes.clear();
        function.unchanged = true;
        function.handleRequest("");
        assertEquals("\"dpt76-validator\"", function.validators.get(1));
        assertTrue(function.writes.isEmpty());
    }

    @Test
    public void emptyOrFailedDownloadsDoNotReplaceThePreviousGeoJson() {
        for (boolean empty : new boolean[]{false, true}) {
            Probe function = configured(Map.of());
            function.handleRequest("");
            function.writes.clear();
            function.empty = empty;
            function.fail = !empty;
            try {
                function.handleRequest("");
                fail("Expected download failure");
            } catch (UncheckedIOException expected) {
                assertTrue(expected.getMessage().contains("DPT76_Comptage_2022.geojson"));
            }
            assertTrue(function.writes.isEmpty());
            assertArrayEquals(GEOJSON, function.contents.get("DPT76_Comptage_2022.geojson"));
        }
    }

    @Test
    public void archivesPreviousGeoJsonBeforeReplacingItAndKeepsCurrentOnArchiveFailure() {
        Probe function = configured(Map.of());
        String current = "DPT76_Comptage_2022.geojson";
        function.handleRequest("");
        function.metadata.get(current).setLastModified(java.time.LocalDate.of(2026, 10, 23));
        function.writes.clear();
        function.handleRequest("");
        assertEquals(List.of("DPT76_Comptage_2022_20261023.geojson", current), function.writes);
        assertArrayEquals(GEOJSON, function.contents.get("DPT76_Comptage_2022_20261023.geojson"));
        byte[] previous = function.contents.get(current);
        function.writes.clear();
        function.failArchive = true;
        try {
            function.handleRequest("");
            fail("Expected archive failure");
        } catch (UncheckedIOException expected) {
            assertTrue(function.writes.isEmpty());
            assertSame(previous, function.contents.get(current));
            assertTrue(function.closed);
        }
    }

    private static Probe configured(Map<String, String> configuration) {
        Map<String, String> settings = new HashMap<>(DataSourceTestSettings.configuration("DPT76"));
        settings.putAll(configuration);
        FunctionRuntimeContext runtime = new FunctionRuntimeContext(
                new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"), settings);
        new FunctionConfigurer().configure(runtime);
        return (Probe) runtime.getInvokeInstance().orElseThrow();
    }

    public static class Probe extends DataSourceFunction {
        String bucket;
        boolean unchanged;
        boolean empty;
        boolean fail;
        boolean failArchive;
        boolean closed;
        final List<URI> requests = new ArrayList<>();
        final List<String> validators = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        final Map<String, byte[]> contents = new HashMap<>();
        final Map<String, OOSResourceMetadata> metadata = new HashMap<>();

        @Override protected HttpClient createHttpClient() {
            return new HttpClient() {
                @Override public EntityBody get(URI uri, String etag) throws IOException {
                    requests.add(uri);
                    validators.add(etag);
                    if (fail) throw new IOException("Download failed");
                    return new EntityBody("dpt76-validator", null, unchanged ? null
                            : new ByteArrayInputStream(empty ? new byte[0] : GEOJSON) {
                                @Override public void close() throws IOException {
                                    closed = true;
                                    super.close();
                                }
                            });
                }
            };
        }

        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String ns, String name) {
            bucket = ns + "/" + name;
            return new ObjectStoreClient(null, ns, name) {
                @Override public OOSResourceMetadata getResourceMetadata(String object) { return metadata.get(object); }
                @Override public InputStream getResource(String object) { return new ByteArrayInputStream(contents.get(object)); }
                @Override public void putResource(String object, String type, OOSResourceMetadata meta,
                                                  InputStream input) throws IOException {
                    assertEquals("application/geo+json", type);
                    if (failArchive && !object.equals("DPT76_Comptage_2022.geojson")) throw new IOException("Archive failure");
                    contents.put(object, input.readAllBytes());
                    metadata.put(object, meta);
                    writes.add(object);
                }
            };
        }
    }
}
