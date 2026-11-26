package org.traficinfo.dsfunctions;

import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class HistoryPublicationFunctionTest {
    @Test
    public void publishesSelectedDatasourceUsingConfiguredBucketsAndClosesResources() {
        var function = configured();
        assertEquals("OK", function.handleRequest("{\"datasourceId\":\"MMM\"}"));
        assertEquals("OK", function.handleRequest("{\"datasourceId\":\"REN\"}"));
        assertEquals(List.of("ns/ds3mbucket", "ns/public-history", "ns/dsren", "ns/public-history"), function.clients);
        assertEquals(List.of("history/MMM.json", "history/REN.json"), function.writes);
        assertEquals(4, function.closed);
        assertNull(MDC.get("datasourceId"));
        assertNull(MDC.get("stage"));
    }

    @Test
    public void invalidRequestsCreateNoClients() {
        for (var input : new String[]{"", "null", "{}", "[]", "{\"datasourceId\":\"UNKNOWN\"}",
                "{\"datasourceId\":\"MMM\",\"bucket\":\"override\"}", "{\"datasourceId\":\"MMM\"} {}"}) {
            var function = configured();
            try { function.handleRequest(input); fail("Expected invalid request: " + input); }
            catch (IllegalArgumentException expected) { assertTrue(function.clients.isEmpty()); }
        }
    }

    @Test
    public void readFailureDoesNotPublishAndClosesClients() {
        var function = configured();
        function.failRead = true;
        try { function.handleRequest("{\"datasourceId\":\"NIM\"}"); fail("Expected read failure"); }
        catch (java.io.UncheckedIOException expected) { assertEquals(2, function.closed); }
        assertTrue(function.writes.isEmpty());
        assertNull(MDC.get("datasourceId"));
    }

    private static Probe configured() {
        var function = new Probe();
        function.config(new FunctionRuntimeContext(
                new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"),
                Map.of("NAMESPACE", "ns", "BUCKETNAME_OUT", "public-history")));
        return function;
    }

    public static class Probe extends HistoryPublicationFunction {
        final List<String> clients = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        int closed;
        boolean failRead;

        // Failure reporting rethrows the original when no metrics backend is configured.
        @Override protected MetricsPublisher createMetricsPublisher() {
            return samples -> { throw new IllegalStateException("No test metrics backend"); };
        }

        @Override protected ObjectStore createObjectStoreClient(String bucket) {
            clients.add(namespace() + "/" + bucket);
            return new StubObjectStore() {
                @Override public InputStream getResource(String name) throws IOException {
                    assertEquals("history", MDC.get("stage"));
                    if (failRead) throw new IOException("Unavailable manifest");
                    var source = GeoJsonDataSource.valueOf(MDC.get("datasourceId"));
                    return new ByteArrayInputStream(ManifestTestPublication.fromRecords(source, List.of()).get(name));
                }
                @Override public void putResource(String name, String type, ObjectMetadata metadata,
                                                   InputStream input, String cacheControl) throws IOException {
                    assertTrue(input.readAllBytes().length > 0);
                    writes.add(name);
                }
                @Override public void close() { closed++; }
            };
        }
    }
}
