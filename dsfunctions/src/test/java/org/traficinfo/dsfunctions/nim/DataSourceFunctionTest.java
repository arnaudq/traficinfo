package org.traficinfo.dsfunctions.nim;

import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;
import org.traficinfo.dsfunctions.DataSourceFunction;
import org.traficinfo.dsfunctions.DataSourceTestSettings;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OOSResourceMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** All downloads are replaced by a tiny synthetic CSV. No Nîmes data is fetched. */
public class DataSourceFunctionTest {
    private static final URI CSV_URI = URI.create(DataSourceTestSettings.settings("NIM").inputs().get(0).url());
    private static final byte[] CSV = "id_camera;date;tranche;nbpassage\n1;2022-01-01;00:00;10\n"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    public void fnUsesConfiguredDownloadAndMetadata() {
        Probe function = configured(Map.of());
        assertEquals("OK", function.handleRequest("{\"datasourceId\":\"NIM\"}"));
        assertEquals("/dsnim", function.bucket);
        assertEquals(List.of(CSV_URI), function.requests);
        assertEquals(List.of("NIM_Comptage.csv"), function.writes);
        assertArrayEquals(CSV, function.contents.get("NIM_Comptage.csv"));
        OOSResourceMetadata metadata = function.metadata.get("NIM_Comptage.csv");
        assertEquals(DataSourceTestSettings.settings("NIM").datasetId(),
                metadata.getMetadata().get("dataset-id"));
        assertEquals("NIM", metadata.getMetadata().get("datasource-id"));
        assertEquals(CSV_URI.toString(), metadata.getMetadata().get("source-url"));
        assertTrue(function.closed);
    }

    @Test
    public void bundledDatasetAndBucketAreUsed() {
        Probe function = configured(Map.of("NAMESPACE", "testns"));
        function.handleRequest("{\"datasourceId\":\"NIM\"}");
        assertEquals("testns/dsnim", function.bucket);
        assertEquals(CSV_URI, function.requests.get(0));
        assertEquals(DataSourceTestSettings.settings("NIM").datasetId(),
                function.metadata.values().iterator().next().getMetadata().get("dataset-id"));
    }

    @Test
    public void unchangedCsvIsNotUploadedAgain() {
        Probe function = configured(Map.of());
        function.handleRequest("{\"datasourceId\":\"NIM\"}");
        function.writes.clear();
        function.unchanged = true;
        function.handleRequest("{\"datasourceId\":\"NIM\"}");
        assertEquals("\"nim-validator\"", function.validators.get(1));
        assertTrue(function.writes.isEmpty());
    }

    @Test
    public void emptyOrFailedDownloadsDoNotReplaceThePreviousCsv() {
        for (boolean empty : new boolean[]{false, true}) {
            Probe function = configured(Map.of());
            function.handleRequest("{\"datasourceId\":\"NIM\"}");
            function.writes.clear();
            function.empty = empty;
            function.fail = !empty;
            try {
                function.handleRequest("{\"datasourceId\":\"NIM\"}");
                fail("Expected download failure");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getCause().getMessage().contains("NIM_Comptage.csv"));
            }
            assertTrue(function.writes.isEmpty());
            assertArrayEquals(CSV, function.contents.get("NIM_Comptage.csv"));
        }
    }

    private static Probe configured(Map<String, String> configuration) {
        Map<String, String> settings = new HashMap<>();
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
                    return new EntityBody("nim-validator", null, unchanged ? null
                            : new ByteArrayInputStream(empty ? new byte[0] : CSV) {
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
                    assertEquals("text/csv", type);
                    contents.put(object, input.readAllBytes());
                    metadata.put(object, meta);
                    writes.add(object);
                }
            };
        }
    }
}
