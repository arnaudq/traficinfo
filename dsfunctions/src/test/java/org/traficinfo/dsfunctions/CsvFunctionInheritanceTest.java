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
import com.fnproject.fn.api.MethodWrapper;
import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;
import org.traficinfo.parsers.GeoJsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
            assertEquals(id + "_TraficInfo.geojson", source.getGeoJsonObjectName());
            assertEquals(id + "_OSMInfo.json", source.getOsmCacheObjectName());
            assertEquals(id + "_OSMWayMapping.json", source.getOsmWayMappingObjectName());
        }
    }

    @Test
    public void fnConfiguresAndInvokesSharedRetrieval() throws Exception {
        for (boolean overrides : new boolean[]{false, true}) {
            Map<String, String> config = overrides ? Map.of("NAMESPACE", "testns", "USE_RESOURCE_PRINCIPAL", "false") : Map.of();
            FunctionRuntimeContext runtime = configuredRuntime(SourceProbe.class, config);
            SourceProbe function = (SourceProbe) runtime.getInvokeInstance().orElseThrow();
            assertTrue(function.clients.isEmpty());
            assertEquals("OK", runtime.getMethod().getTargetMethod().invoke(function, "{\"datasourceId\":\"MRN\"}"));
            assertEquals(List.of((overrides ? "false:testns:dsmrn" : "true::dsmrn")), function.clients);
            assertEquals(List.of("MRN_Comptage.csv"), function.writes);
            assertTrue(function.closed);
            assertEquals("\"sha256:" + "a".repeat(64) + "\"",
                    function.metadata.get("MRN_Comptage.csv").getETag());

            function.unchanged = true;
            assertEquals("OK", function.handleRequest("{\"datasourceId\":\"MRN\"}"));
            assertEquals(1, function.writes.size());
            function.unchanged = false;
            assertEquals("OK", function.handleRequest("{\"datasourceId\":\"MRN\"}"));
            assertEquals(3, function.writes.size());
            assertTrue(function.writes.get(1).startsWith("MRN_Comptage_"));
            assertEquals("MRN_Comptage.csv", function.writes.get(2));

            function.empty = true;
            try {
                function.handleRequest("{\"datasourceId\":\"MRN\"}");
                fail("Empty CSV must fail before archive or replacement");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getCause().getMessage().contains("MRN_Comptage.csv"));
            }
            assertEquals(3, function.writes.size());
        }
    }

    @Test
    public void fnConfiguresAndInvokesSharedMrnGeoJsonWithDefaultsAndOverrides() throws Exception {
        for (boolean overrides : new boolean[]{false, true}) {
            Map<String, String> config = overrides ? Map.of("NAMESPACE", "testns",
                    "BUCKETNAME_OUT", "custom-out",
                    "BUCKETNAME_OSM", "custom-osm", "USE_RESOURCE_PRINCIPAL", "false") : Map.of();
            FunctionRuntimeContext runtime = configuredRuntime(GeoJsonProbe.class, config);
            GeoJsonProbe function = (GeoJsonProbe) runtime.getInvokeInstance().orElseThrow();
            assertTrue(function.clients.isEmpty());
            assertEquals("OK", runtime.getMethod().getTargetMethod().invoke(function, "{\"datasourceId\":\"MRN\"}"));
            assertEquals(overrides ? List.of("false:testns:dsmrn", "false:testns:custom-out",
                    "false:testns:custom-osm") : List.of("true::dsmrn", "true::traficinfo", "true::osminfo"),
                    function.clients);
            assertEquals(List.of("MRN_Manifest.json",
                    ManifestTestPublication.yearObjectName(GeoJsonDataSource.MRN, Year.now().getValue()),
                    "MRN_OSMInfo.json",
                    "MRN_OSMWayMapping.json"), function.reads);
            assertEquals(List.of("MRN_TraficInfo.geojson", "MRN_TraficInfo_BICYCLES.geojson",
                    "MRN_OSMWayMapping.json"), function.writes);
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
            FunctionFailureAssertions.assertFunctionFailure(() -> function.handleRequest("{\"datasourceId\":\"MRN\"}"));
            assertSame(previousOutput, function.output);
            assertEquals(previousWrites, function.writes.size());
            function.invalidManifest = false;
            function.failUpload = true;
            FunctionFailureAssertions.assertFunctionFailure(() -> function.handleRequest("{\"datasourceId\":\"MRN\"}"));
        }
    }

    @Test
    public void fnConfiguresAndInvokesSharedMmmGeoJsonWithDefaultsAndOverrides() throws Exception {
        for (boolean overrides : new boolean[]{false, true}) {
            Map<String, String> config = overrides ? Map.of("NAMESPACE", "testns",
                    "BUCKETNAME_OUT", "custom-out",
                    "BUCKETNAME_OSM", "custom-osm", "USE_RESOURCE_PRINCIPAL", "false") : Map.of();
            FunctionRuntimeContext runtime = configuredRuntime(MmmGeoJsonProbe.class, config);
            MmmGeoJsonProbe function = (MmmGeoJsonProbe) runtime.getInvokeInstance().orElseThrow();
            assertTrue(function.clients.isEmpty());
            assertEquals("OK", runtime.getMethod().getTargetMethod().invoke(function, "{\"datasourceId\":\"MMM\"}"));
            assertEquals(overrides ? List.of("false:testns:ds3mbucket", "false:testns:custom-out",
                    "false:testns:custom-osm") : List.of("true::ds3mbucket", "true::traficinfo", "true::osminfo"),
                    function.clients);
            assertEquals(List.of("MMM_Manifest.json", "MMM_OSMInfo.json",
                    "MMM_OSMWayMapping.json"), function.reads);
            assertEquals(List.of("MMM_TraficInfo.geojson", "MMM_TraficInfo_BICYCLES.geojson", "MMM_OSMWayMapping.json"),
                    function.writes);
        }
    }

    private static FunctionRuntimeContext configuredRuntime(Class<?> handler, Map<String, String> config) {
        MethodWrapper wrapper = new FunctionLoader().loadClass(handler.getName(), "handleRequest");
        Map<String, String> settings = new HashMap<>();
        settings.putAll(config);
        FunctionRuntimeContext runtime = new FunctionRuntimeContext(wrapper, settings);
        new FunctionConfigurer().configure(runtime);
        return runtime;
    }

    /** Only client factories are overridden; Fn discovers the actual inherited lifecycle methods. */
    public static class SourceProbe extends DataSourceFunction {
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

    public static class GeoJsonProbe extends GeoJsonFunction {
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
                        byte[] bytes = body.readAllBytes();
                        if (!name.endsWith("_BICYCLES.geojson")) output = bytes;
                    } else {
                        assertNotNull("GeoJSON must be uploaded before refreshing mappings", output);
                        mapping = body.readAllBytes();
                    }
                }
            };
        }
    }

    public static class MmmGeoJsonProbe extends GeoJsonFunction {
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
