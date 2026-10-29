package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.MethodWrapper;
import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;
import org.traficinfo.parsers.GeoJsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CsvFunctionInheritanceTest {
    @Test
    public void enumDefinesGeoJsonSourcesAndTheirResourceNames() {
        for (GeoJsonDataSource source : GeoJsonDataSource.values()) {
            String id = source.getDataSourceId();
            assertEquals(source.name(), id);
            assertEquals(id.equals("MMM") ? GeoJsonParser.DataSource.M3MV2 : GeoJsonParser.DataSource.valueOf(id), source.getParserDataSource());
            assertEquals(id.equals("MMM") ? "ds3mbucket" : "ds" + id.toLowerCase(java.util.Locale.ROOT), source.getDefaultInputBucket());
            assertEquals(id.equals("MMM") ? "MMM_MMM_Comptage.csv" : id + "_Comptage.csv", source.getCsvObjectName());
            assertEquals(id + "_TraficInfo.geojson", source.getGeoJsonObjectName());
            assertEquals(id + "_OSMInfo.json", source.getOsmCacheObjectName());
            assertEquals(id + "_OSMWayMapping.json", source.getOsmWayMappingObjectName());
        }
    }

    @Test
    public void fnLoadsInheritedMethodsForAllEightConcreteHandlers() throws Exception {
        for (String name : List.of("dpt30", "dpt34", "mrn", "m3m")) {
            for (String handler : List.of("DataSourceFunction", "GeoJsonOutFunction")) {
                Class<?> type = Class.forName("org.traficinfo.dsfunctions." + name + "." + handler);
                MethodWrapper wrapper = new FunctionLoader().loadClass(type.getName(), "handleRequest");
                Class<?> base = handler.equals("DataSourceFunction")
                        ? AbstractDataSourceFunction.class : AbstractGeoJsonOutFunction.class;
                assertEquals(base, wrapper.getTargetMethod().getDeclaringClass());
                assertTrue(Modifier.isFinal(wrapper.getTargetMethod().getModifiers()));
                var config = type.getMethod("config", com.fnproject.fn.api.RuntimeContext.class);
                assertEquals(base, config.getDeclaringClass());
                assertNotNull(config.getAnnotation(FnConfiguration.class));
                assertTrue(Modifier.isFinal(config.getModifiers()));
                // Fn requires exactly one public constructor; test constructors stay package-private.
                assertEquals(1, type.getConstructors().length);
                assertNotNull(type.getConstructor().newInstance());
            }
        }
    }

    @Test
    public void allSourceHandlersShareTheSameConfigurationLifecycle() throws Exception {
        for (String name : List.of("dpt30", "dpt34", "mrn", "m3m", "nim")) {
            Class<?> type = Class.forName("org.traficinfo.dsfunctions." + name + ".DataSourceFunction");
            assertEquals(AbstractDataSourceFunction.class, type.getSuperclass());
            assertEquals(1, type.getConstructors().length);
            MethodWrapper method = new FunctionLoader().loadClass(type.getName(), "handleRequest");
            assertEquals(AbstractDataSourceFunction.class, method.getTargetMethod().getDeclaringClass());
            var configure = type.getMethod("config", com.fnproject.fn.api.RuntimeContext.class);
            assertNotNull(configure.getAnnotation(FnConfiguration.class));
            assertEquals(AbstractDataSourceFunction.class, configure.getDeclaringClass());
            AbstractDataSourceFunction function = (AbstractDataSourceFunction) type.getConstructor().newInstance();
            try {
                function.config(new FunctionRuntimeContext(method, Map.of()));
                fail("Expected mandatory configuration for " + name);
            } catch (IllegalArgumentException expected) {
                assertEquals("Missing DATA_SOURCE_SETTINGS", expected.getMessage());
            }
        }
    }

    @Test
    public void fnConfiguresAndInvokesInheritedMrnDownloadWithDeploymentSettingsAndOverrides() throws Exception {
        for (boolean overrides : new boolean[]{false, true}) {
            Map<String, String> config = overrides ? Map.of("NAMESPACE", "testns",
                    "BUCKETNAME", "custom-input", "USE_RESOURCE_PRINCIPAL", "false") : Map.of();
            FunctionRuntimeContext runtime = configuredRuntime(SourceProbe.class, config);
            SourceProbe function = (SourceProbe) runtime.getInvokeInstance().orElseThrow();
            assertEquals(List.of((overrides ? "false:testns:custom-input" : "true::dsmrn")),
                    function.clients);
            assertEquals("OK", runtime.getMethod().getTargetMethod().invoke(function, ""));
            assertEquals(List.of("MRN_Comptage.csv"), function.writes);
            assertTrue(function.closed);
            assertEquals("\"sha256:" + "a".repeat(64) + "\"",
                    function.metadata.get("MRN_Comptage.csv").getETag());

            function.unchanged = true;
            assertEquals("OK", function.handleRequest(""));
            assertEquals(1, function.writes.size());
            function.unchanged = false;
            assertEquals("OK", function.handleRequest(""));
            assertEquals(3, function.writes.size());
            assertTrue(function.writes.get(1).startsWith("MRN_Comptage_"));
            assertEquals("MRN_Comptage.csv", function.writes.get(2));

            function.empty = true;
            try {
                function.handleRequest("");
                fail("Empty CSV must fail before archive or replacement");
            } catch (UncheckedIOException expected) {
                assertTrue(expected.getMessage().contains("MRN_Comptage.csv"));
            }
            assertEquals(3, function.writes.size());
        }
    }

    @Test
    public void fnConfiguresAndInvokesInheritedMrnGeoJsonWithDefaultsAndOverrides() throws Exception {
        for (boolean overrides : new boolean[]{false, true}) {
            Map<String, String> config = overrides ? Map.of("NAMESPACE", "testns",
                    "BUCKETNAME_IN", "custom-in", "BUCKETNAME_OUT", "custom-out",
                    "BUCKETNAME_OSM", "custom-osm", "OSM_CACHE_OBJECT", "tags.json",
                    "OSM_WAY_MAPPING_OBJECT", "mapping.json", "USE_RESOURCE_PRINCIPAL", "false") : Map.of();
            FunctionRuntimeContext runtime = configuredRuntime(GeoJsonProbe.class, config);
            GeoJsonProbe function = (GeoJsonProbe) runtime.getInvokeInstance().orElseThrow();
            assertEquals(overrides ? List.of("false:testns:custom-in", "false:testns:custom-out",
                    "false:testns:custom-osm") : List.of("true::dsmrn", "true::traficinfo", "true::osminfo"),
                    function.clients);
            assertEquals("OK", runtime.getMethod().getTargetMethod().invoke(function, ""));
            assertEquals(List.of("MRN_Manifest.json",
                    ManifestTestPublication.yearObjectName(GeoJsonDataSource.MRN, Year.now().getValue()),
                    overrides ? "tags.json" : "MRN_OSMInfo.json",
                    overrides ? "mapping.json" : "MRN_OSMWayMapping.json"), function.reads);
            assertEquals(List.of("MRN_TraficInfo.geojson",
                    overrides ? "mapping.json" : "MRN_OSMWayMapping.json"), function.writes);
            JsonNode output = new ObjectMapper().readTree(function.output);
            JsonNode feature = output.get("features").get(0);
            assertEquals("MRN_A_3", feature.get("properties").get("code_centrale").asText());
            assertEquals(120, feature.get("properties").get("moyenne_jour_annuelle").asInt());
            assertEquals("15 (08h-09h)", feature.get("properties").get("moyenne_pointe_matin").asText());
            assertEquals(1.1, feature.get("geometry").get("coordinates").get(0).asDouble(), 0);
            JsonNode mapping = new ObjectMapper().readTree(function.mapping).get("MRN_A_3");
            assertTrue(mapping.get("sourceRoadReference").isNull());
            assertEquals(49.2, mapping.get("latitude").asDouble(), 0);
            byte[] previousOutput = function.output;
            int previousWrites = function.writes.size();
            function.invalidManifest = true;
            assertEquals("KO", function.handleRequest(""));
            assertSame(previousOutput, function.output);
            assertEquals(previousWrites, function.writes.size());
            function.invalidManifest = false;
            function.failUpload = true;
            assertEquals("KO", function.handleRequest(""));
        }
    }

    @Test
    public void fnConfiguresAndInvokesInheritedMmmGeoJsonWithDefaultsAndOverrides() throws Exception {
        for (boolean overrides : new boolean[]{false, true}) {
            Map<String, String> config = overrides ? Map.of("NAMESPACE", "testns",
                    "BUCKETNAME_IN", "custom-in", "BUCKETNAME_OUT", "custom-out",
                    "BUCKETNAME_OSM", "custom-osm", "OSM_CACHE_OBJECT", "tags.json",
                    "OSM_WAY_MAPPING_OBJECT", "mapping.json", "USE_RESOURCE_PRINCIPAL", "false") : Map.of();
            FunctionRuntimeContext runtime = configuredRuntime(MmmGeoJsonProbe.class, config);
            MmmGeoJsonProbe function = (MmmGeoJsonProbe) runtime.getInvokeInstance().orElseThrow();
            assertEquals(overrides ? List.of("false:testns:custom-in", "false:testns:custom-out",
                    "false:testns:custom-osm") : List.of("true::ds3mbucket", "true::traficinfo", "true::osminfo"),
                    function.clients);
            assertEquals("OK", runtime.getMethod().getTargetMethod().invoke(function, ""));
            assertEquals(List.of("MMM_Manifest.json", overrides ? "tags.json" : "MMM_OSMInfo.json",
                    overrides ? "mapping.json" : "MMM_OSMWayMapping.json"), function.reads);
            assertEquals(List.of("MMM_TraficInfo.geojson", overrides ? "mapping.json" : "MMM_OSMWayMapping.json"),
                    function.writes);
        }
    }

    private static FunctionRuntimeContext configuredRuntime(Class<?> handler, Map<String, String> config) {
        MethodWrapper wrapper = new FunctionLoader().loadClass(handler.getName(), "handleRequest");
        Map<String, String> settings = new HashMap<>();
        if (AbstractDataSourceFunction.class.isAssignableFrom(handler)) {
            settings.putAll(DataSourceTestSettings.configuration("MRN"));
        }
        settings.putAll(config);
        FunctionRuntimeContext runtime = new FunctionRuntimeContext(wrapper, settings);
        new FunctionConfigurer().configure(runtime);
        return runtime;
    }

    /** Only client factories are overridden; Fn discovers the actual inherited lifecycle methods. */
    public static class SourceProbe extends org.traficinfo.dsfunctions.mrn.DataSourceFunction {
        final List<String> clients = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        final Map<String, OOSResourceMetadata> metadata = new HashMap<>();
        boolean unchanged;
        boolean empty;
        boolean closed;

        @Override
        protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
            clients.add(principal + ":" + namespace + ":" + bucket);
            return new ObjectStoreClient(null, namespace, bucket) {
                @Override public OOSResourceMetadata getResourceMetadata(String name) {
                    return metadata.get(name);
                }
                @Override public InputStream getResource(String name) {
                    return new ByteArrayInputStream("old CSV".getBytes(StandardCharsets.UTF_8));
                }
                @Override public void putResource(String name, String type, OOSResourceMetadata meta,
                                                  InputStream body) throws IOException {
                    assertEquals("text/csv", type);
                    assertTrue(body.readAllBytes().length > 0);
                    writes.add(name);
                    metadata.put(name, meta);
                }
            };
        }

        @Override
        protected HttpClient createHttpClient() {
            return new HttpClient() {
                @Override public EntityBody get(URI uri, String etag) {
                    assertEquals(URI.create(DataSourceTestSettings.settings("MRN").inputs().get(0).url()), uri);
                    assertEquals(metadata.isEmpty() ? null : metadata.get("MRN_Comptage.csv").getETag(), etag);
                    return new EntityBody("sha256:" + "a".repeat(64), null,
                            unchanged ? null : new ByteArrayInputStream(empty ? new byte[0]
                                    : "new CSV".getBytes(StandardCharsets.UTF_8)) {
                                @Override public void close() throws IOException {
                                    closed = true;
                                    super.close();
                                }
                            });
                }
            };
        }
    }

    public static class GeoJsonProbe extends org.traficinfo.dsfunctions.mrn.GeoJsonOutFunction {
        final List<String> clients = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        byte[] output;
        byte[] mapping;
        boolean failUpload;
        boolean invalidManifest;
        Map<String, byte[]> publication;

        @Override
        protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
            clients.add(principal + ":" + namespace + ":" + bucket);
            return new ObjectStoreClient(null, namespace, bucket) {
                @Override public InputStream getResource(String name) throws IOException {
                    reads.add(name);
                    assertFalse("Raw CSV must not be read by the GeoJSON handler", name.endsWith(".csv"));
                    if (publication == null) {
                        publication = ManifestTestPublication.fromCsv(GeoJsonDataSource.MRN,
                                "Type de Campagne;Id_SIG;Année;Rue;Commune;"
                                + "Nombre de Sens;TV_TMJA;TV_HPM;TV_HPM (heure);X_84;Y_84\n"
                                + "Comptage ponctuel en section;A_3;" + Year.now().getValue()
                                + ";D921;Rouen;2 sens;120;15;[08h-09h];1.1;49.2\n");
                    }
                    if (name.equals("MRN_Manifest.json") && invalidManifest) {
                        return new ByteArrayInputStream("invalid JSON".getBytes(StandardCharsets.UTF_8));
                    }
                    return new ByteArrayInputStream(publication.getOrDefault(name, "{}".getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void putResource(String name, String type, OOSResourceMetadata meta,
                                                  InputStream body) throws IOException {
                    if (failUpload) throw new IOException("Upload failed");
                    writes.add(name);
                    if (name.endsWith(".geojson")) {
                        assertEquals("application/geo+json", type);
                        output = body.readAllBytes();
                    } else {
                        assertNotNull("GeoJSON must be uploaded before refreshing mappings", output);
                        mapping = body.readAllBytes();
                    }
                }
            };
        }
    }

    public static class MmmGeoJsonProbe extends org.traficinfo.dsfunctions.m3m.GeoJsonOutFunction {
        final List<String> clients = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
        final List<String> writes = new ArrayList<>();

        @Override
        protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
            clients.add(principal + ":" + namespace + ":" + bucket);
            return new ObjectStoreClient(null, namespace, bucket) {
                @Override public InputStream getResource(String name) throws IOException {
                    reads.add(name);
                    byte[] bytes = name.equals("MMM_Manifest.json")
                            ? ManifestTestPublication.fromRecords(GeoJsonDataSource.MMM, List.of()).get(name)
                            : "{}".getBytes(StandardCharsets.UTF_8);
                    return new ByteArrayInputStream(bytes);
                }

                @Override public void putResource(String name, String type, OOSResourceMetadata meta,
                                                  InputStream body) throws IOException {
                    assertTrue(body.readAllBytes().length > 0);
                    writes.add(name);
                }
            };
        }
    }
}
