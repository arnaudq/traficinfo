package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.RuntimeContext;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

public class TraficInfoBaseFunctionTest {
    private final List<MetricSample> samples = new ArrayList<>();
    private final TestFunction handler = configured(samples::addAll);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void sharedSettingsKeepDefaultsAndAreAvailableDuringConfiguration() {
        var function = configured(samples::addAll);
        assertEquals("", function.configuredNamespace);
        assertTrue(function.configuredPrincipal);
    }

    @Test
    public void sharedSettingsReadExplicitNamespaceAndAuthenticationChoice() {
        var function = new TestFunction(samples::addAll);
        function.config(runtime(Map.of("NAMESPACE", "testnamespace", "USE_RESOURCE_PRINCIPAL", "false")));
        assertEquals("testnamespace", function.configuredNamespace);
        assertFalse(function.configuredPrincipal);
        function.config(runtime(Map.of("NAMESPACE", "other", "USE_RESOURCE_PRINCIPAL", "true")));
        assertEquals("other", function.configuredNamespace);
        assertTrue(function.configuredPrincipal);
    }

    @Test
    public void successfulResponseIsUnchangedAndEmitsNoFailureMetric() {
        assertEquals("OK", handler.invoke(attempt -> "OK"));
        assertTrue(samples.isEmpty());
    }

    @Test
    public void failureReturnsEscapedExceptionDetailsAndOneEventWithSelectedDatasource() throws Exception {
        Instant before = Instant.now();
        var original = new IllegalStateException("failed \"upload\"\n", new java.io.IOException("connection lost"));
        String result = handler.invoke(attempt -> {
            attempt.datasource("MMM");
            throw original;
        });
        var response = JSON.readTree(result);
        assertFalse(response.path("success").asBoolean(true));
        assertEquals(original.getClass().getName(), response.at("/error/type").asText());
        assertEquals(original.getMessage(), response.at("/error/message").asText());
        assertEquals("connection lost", response.at("/error/causes/0/message").asText());
        assertEquals(1, samples.size());
        var sample = samples.get(0);
        assertEquals(MetricName.FUNCTION_FAILURES.metricName(), sample.name());
        assertEquals(1.0, sample.value(), 0);
        assertEquals("count", sample.unit());
        assertFalse(sample.timestamp().isBefore(before));
        assertEquals(Map.of("applicationId", "application", "resourceId", "function",
                "resourceDisplayName", "TestFunction", "datasourceId", "MMM"), sample.dimensions());
    }

    @Test
    public void datasourceDoesNotLeakBetweenInvocationsOnSameHandler() {
        handler.invoke(attempt -> {
            attempt.datasource("MMM");
            throw new IllegalArgumentException("first");
        });
        handler.invoke(attempt -> { throw new IllegalArgumentException("second"); });
        assertEquals(List.of("MMM", "UNKNOWN"), samples.stream()
                .map(sample -> sample.dimensions().get("datasourceId")).toList());
    }

    @Test
    public void reportingFailureRethrowsExactOriginalExceptionWithoutRetryingPublication() {
        var original = new IllegalArgumentException("original");
        int[] attempts = {0};
        var failing = configured(batch -> {
            attempts[0]++;
            throw new IllegalStateException("monitoring unavailable");
        });
        assertSame(original, assertThrows(IllegalArgumentException.class,
                () -> failing.invoke(attempt -> { throw original; })));
        assertEquals(1, attempts[0]);
    }

    @Test
    public void missingMetricsConfigurationPreservesOriginalFailure() {
        var original = new IllegalArgumentException("invalid input");
        var configured = new TestFunction(null);
        configured.config(runtime(Map.of()));
        assertSame(original, assertThrows(IllegalArgumentException.class,
                () -> configured.invoke(attempt -> { throw original; })));
        // Snapshot publication retains its existing optional configuration behavior.
        FunctionMetrics.configured(runtime(Map.of()), true).publish(List.of(new MetricSample(
                MetricName.GEO_JSON_FEATURES.metricName(), 0, "count", Instant.now(), Map.of())));
    }

    @Test
    public void jvmErrorsAreNotAcknowledgedAsHandledFailures() {
        var error = new AssertionError("fatal");
        assertSame(error, assertThrows(AssertionError.class,
                () -> handler.invoke(attempt -> { throw error; })));
        assertTrue(samples.isEmpty());
    }

    @Test
    public void allFunctionEntryPointsHandleFailuresWhenMonitoringSucceeds() throws Exception {
        var context = runtime(Map.of("NAMESPACE", "namespace"));
        var retrieval = new DataSourceFunction() {
            @Override protected MetricsPublisher createFailureMetrics(RuntimeContext ctx) { return samples::addAll; }
        };
        retrieval.config(context);
        assertError(retrieval.handleRequest("{}"), "datasourceId is required", "UNKNOWN");

        var preprocess = new PreprocessingFunction() {
            @Override protected MetricsPublisher createFailureMetrics(RuntimeContext ctx) { return samples::addAll; }
        };
        preprocess.config(context);
        assertError(preprocess.handleRequest("{}"), "datasourceId is required", "UNKNOWN");

        var geojson = new GeoJsonFunction() {
            @Override protected MetricsPublisher createFailureMetrics(RuntimeContext ctx) { return samples::addAll; }
            @Override protected ObjectStoreClient createObjectStoreClient(boolean p, String ns, String bucket) {
                throw new IllegalStateException("storage unavailable");
            }
        };
        geojson.config(context);
        assertError(geojson.handleRequest("{\"datasourceId\":\"MMM\"}"), "GeoJSON generation failed for MMM", "MMM");

        var accidents = new ONISRAccidentFunction() {
            @Override protected MetricsPublisher createFailureMetrics(RuntimeContext ctx) { return samples::addAll; }
        };
        accidents.config(context);
        assertError(accidents.handleRequest("{\"action\":\"invalid\"}"), "Expected action import or refresh", "ONISR");

        var publication = new AccidentPublicationFunction() {
            @Override protected MetricsPublisher createFailureMetrics(RuntimeContext ctx) { return samples::addAll; }
            @Override protected ObjectStoreClient createObjectStoreClient(boolean p, String ns, String bucket) {
                throw new IllegalStateException("storage unavailable");
            }
        };
        publication.config(context);
        assertError(publication.handleRequest("{}"), "storage unavailable", "ALL");

        var boundaries = new BoundaryFunction() {
            @Override protected MetricsPublisher createFailureMetrics(RuntimeContext ctx) { return samples::addAll; }
        };
        boundaries.config(context);
        assertError(boundaries.handleRequest("{\"epciCode\":\"bad\"}"), "epciCode must be a nine-digit string", "ALL");

        var osm = new OSMInfoFunction() {
            @Override protected MetricsPublisher createFailureMetrics(RuntimeContext ctx) { return samples::addAll; }
            @Override protected ObjectStoreClient createObjectStoreClient(boolean p, String ns, String bucket) { return null; }
        };
        osm.config(context);
        assertError(osm.handleRequest("{\"mode\":\"invalid\"}"),
                "OSM invocation must be an object with mode manual or scheduled", "ALL");
        assertEquals(7, samples.size());
    }

    private static TestFunction configured(MetricsPublisher publisher) {
        var function = new TestFunction(publisher);
        function.config(runtime(Map.of()));
        return function;
    }

    private static final class TestFunction extends TraficInfoBaseFunction {
        private final MetricsPublisher publisher;
        private String configuredNamespace;
        private boolean configuredPrincipal;
        private java.util.function.Function<Invocation, String> operation;

        TestFunction(MetricsPublisher publisher) {
            super("UNKNOWN");
            this.publisher = publisher;
        }

        @Override protected MetricsPublisher createFailureMetrics(RuntimeContext context) {
            return publisher == null ? super.createFailureMetrics(context) : publisher;
        }

        @Override protected void configure(RuntimeContext context) {
            configuredNamespace = namespace();
            configuredPrincipal = useResourcePrincipal();
        }

        @Override protected String processRequest(String input, Invocation invocation) {
            return operation.apply(invocation);
        }

        String invoke(java.util.function.Function<Invocation, String> operation) {
            this.operation = operation;
            return handleRequest("");
        }
    }

    private void assertError(String response, String message, String source) throws Exception {
        var body = JSON.readTree(response);
        assertFalse(body.path("success").asBoolean(true));
        assertEquals(message, body.at("/error/message").asText());
        assertEquals(source, samples.get(samples.size() - 1).dimensions().get("datasourceId"));
    }

    private static RuntimeContext runtime(Map<String, String> configuration) {
        return (RuntimeContext) Proxy.newProxyInstance(RuntimeContext.class.getClassLoader(),
                new Class<?>[]{RuntimeContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConfigurationByKey" -> Optional.ofNullable(configuration.get((String) args[0]));
                    case "getAppID" -> "application";
                    case "getFunctionID" -> "function";
                    case "getFunctionName" -> "TestFunction";
                    default -> throw new AssertionError("Unexpected context method " + method.getName());
                });
    }
}
