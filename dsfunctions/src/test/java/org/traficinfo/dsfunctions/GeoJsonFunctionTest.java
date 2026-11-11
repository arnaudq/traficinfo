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

import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GeoJsonFunctionTest {
    @Test
    public void manualAndEventInvocationsSelectDifferentSourcesOnSameInstance() {
        Probe probe = configured();
        assertTrue(probe.clients.isEmpty());
        assertEquals("OK", probe.handleRequest("{\"datasourceId\":\"MMM\"}"));
        assertEquals("OK", probe.handleRequest(event("DPT76", "dsdpt76")));
        assertEquals(List.of("ns/ds3mbucket", "ns/traficinfo", "ns/osminfo",
                "ns/dsdpt76", "ns/traficinfo", "ns/osminfo"), probe.clients);
        assertEquals(List.of("MMM:MMM_TraficInfo.geojson", "MMM:MMM_TraficInfo_BICYCLES.geojson",
                "MMM:MMM_OSMWayMapping.json", "DPT76:DPT76_TraficInfo.geojson",
                "DPT76:DPT76_TraficInfo_BICYCLES.geojson", "DPT76:DPT76_OSMWayMapping.json"), probe.writes);
        assertEquals(6, probe.closed);
        assertEquals(2, probe.httpClosed);
        assertEquals(List.of("MMM", "MMM", "DPT76", "DPT76"), probe.metrics.stream()
                .map(sample -> sample.dimensions().get("datasourceId")).toList());
        assertNull(MDC.get("datasourceId"));
        assertNull(MDC.get("stage"));
    }

    @Test
    public void invalidRequestsAndEventsNeverCreateClients() {
        Probe probe = configured();
        for (String input : new String[]{null, "", "{}", "null", "{\"datasourceId\":\"UNKNOWN\"}",
                "{\"datasourceId\":\"MMM\",\"bucket\":\"other\"}", "{\"datasourceId\":\"MMM\"} {}",
                event("MMM", "wrong"), event("MMM", "ds3mbucket").replace("ns", "wrong"),
                event("MMM", "ds3mbucket").replace("_Manifest.json", "_Comptage.csv"),
                event("MMM", "ds3mbucket").replace("createobject", "deleteobject")}) {
            try { probe.handleRequest(input); fail("Expected invalid request: " + input); }
            catch (IllegalArgumentException expected) { assertTrue(probe.clients.isEmpty()); }
        }
    }

    @Test
    public void disabledSourceIsRejectedBeforeClientsAreCreated() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream("/dsconfig.json")) {
            var config = json.readTree(input);
            ((com.fasterxml.jackson.databind.node.ObjectNode) config.get("NIM")).put("geoJsonEnabled", false);
            var registry = DataSourceRegistry.read(new ByteArrayInputStream(json.writeValueAsBytes(config)));
            Probe probe = new Probe() {
                @Override protected DataSourceRegistry loadRegistry() { return registry; }
            };
            probe.config(runtime());
            try { probe.handleRequest("{\"datasourceId\":\"NIM\"}"); fail("Disabled source"); }
            catch (IllegalArgumentException expected) { assertTrue(probe.clients.isEmpty()); }
        }
    }

    @Test
    public void failuresCloseClientsAndDoNotLeakContextToNextInvocation() {
        Probe probe = configured();
        probe.fail = true;
        try { probe.handleRequest(event("MMM", "ds3mbucket")); fail("Expected manifest failure"); }
        catch (IllegalStateException expected) { assertTrue(probe.writes.isEmpty()); }
        assertEquals(3, probe.closed);
        assertEquals(1, probe.httpClosed);
        assertNull(MDC.get("datasourceId"));
        probe.fail = false;
        assertEquals("OK", probe.handleRequest(event("NIM", "dsnim").replace("createobject", "updateobject")));
        assertTrue(probe.writes.stream().allMatch(name -> name.startsWith("NIM:")));
    }

    private static String event(String id, String bucket) {
        return "{\"eventType\":\"com.oraclecloud.objectstorage.createobject\",\"data\":{\"resourceName\":\""
                + id + "_Manifest.json\",\"additionalDetails\":{\"bucketName\":\"" + bucket + "\",\"namespace\":\"ns\"}}}";
    }

    private static FunctionRuntimeContext runtime() {
        return new FunctionRuntimeContext(new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"),
                Map.of("NAMESPACE", "ns"));
    }

    private static Probe configured() {
        var runtime = runtime();
        new FunctionConfigurer().configure(runtime);
        return (Probe) runtime.getInvokeInstance().orElseThrow();
    }

    public static class Probe extends GeoJsonFunction {
        final List<String> clients = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        final List<MetricSample> metrics = new ArrayList<>();
        int closed;
        int httpClosed;
        boolean fail;

        @Override protected void publishMetrics(List<MetricSample> samples) {
            metrics.addAll(samples);
        }

        @Override protected OpenStreetMapClient createOpenStreetMapClient() {
            return new OpenStreetMapClient() {
                @Override public void close() { httpClosed++; super.close(); }
            };
        }

        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
            clients.add(namespace + "/" + bucket);
            return new ObjectStoreClient(null, namespace, bucket) {
                @Override public InputStream getResource(String name) throws IOException {
                    assertEquals("geojson", MDC.get("stage"));
                    if (fail) throw new IOException("manifest unavailable");
                    String id = MDC.get("datasourceId");
                    byte[] body = name.equals(id + "_Manifest.json")
                            ? ManifestTestPublication.fromRecords(GeoJsonDataSource.valueOf(id), List.of()).get(name)
                            : "{}".getBytes(StandardCharsets.UTF_8);
                    return new ByteArrayInputStream(body);
                }
                @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream input) throws IOException {
                    assertTrue(input.readAllBytes().length > 0);
                    writes.add(MDC.get("datasourceId") + ":" + name);
                }
                @Override public void close() { closed++; }
            };
        }
    }
}
