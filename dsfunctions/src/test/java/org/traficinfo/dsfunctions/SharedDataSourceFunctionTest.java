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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SharedDataSourceFunctionTest {
    @Test
    public void fnLoadsSharedHandlerAndSelectsDifferentSourcesOnTheSameInstance() {
        Probe probe = configured(Map.of("NAMESPACE", "testns", "USE_RESOURCE_PRINCIPAL", "false"));
        assertTrue(probe.buckets.isEmpty()); // Startup has no datasource-specific client.
        assertEquals("OK", probe.handleRequest("{\"datasourceId\":\"MMM\"}"));
        assertEquals("OK", probe.handleRequest("{\"datasourceId\":\"DPT76\"}"));
        assertEquals(List.of("testns/ds3mbucket", "testns/dsdpt76"), probe.buckets);
        assertEquals(List.of("MMM:MMM_MMM_Comptage.json", "MMM:MMM_MMM_Comptage.csv",
                "DPT76:DPT76_Comptage_2022.geojson"), probe.writes);
        assertEquals(2, probe.storageClosed);
        assertEquals(2, probe.httpClosed);
        assertNull(MDC.get("datasourceId"));
        assertNull(MDC.get("stage"));
    }

    @Test
    public void unconfiguredHandlerCannotPublish() {
        Probe probe = new Probe();
        try {
            probe.handleRequest("{\"datasourceId\":\"MRN\"}");
            fail("Expected missing configuration failure");
        } catch (IllegalStateException expected) {
            assertTrue(probe.requests.isEmpty());
            assertTrue(probe.writes.isEmpty());
        }
    }

    @Test
    public void invalidRequestsNeverCreateClients() {
        Probe probe = configured(Map.of());
        for (String input : new String[]{null, "", "null", "{}", "{\"datasourceId\":\"\"}",
                "{\"datasourceId\":\"UNKNOWN\"}", "{\"datasourceId\":\"MRN\",\"url\":\"https://bad.invalid\"}",
                "{\"datasourceId\":\"MRN\"} {}"}) {
            try {
                probe.handleRequest(input);
                fail("Expected invalid request: " + input);
            } catch (IllegalArgumentException expected) {
                assertTrue(probe.buckets.isEmpty());
                assertTrue(probe.requests.isEmpty());
            }
        }
    }

    @Test
    public void retrievalFailureClosesClientsAndClearsLoggingContext() {
        Probe probe = configured(Map.of());
        probe.fail = true;
        try {
            probe.handleRequest("{\"datasourceId\":\"MRN\"}");
            fail("Expected retrieval failure");
        } catch (IllegalStateException expected) {
            assertEquals("Retrieval failed for MRN", expected.getMessage());
        }
        assertEquals(List.of("/dsmrn"), probe.buckets);
        assertEquals(1, probe.storageClosed);
        assertEquals(1, probe.httpClosed);
        assertNull(MDC.get("datasourceId"));
        probe.fail = false;
        probe.handleRequest("{\"datasourceId\":\"DPT30\"}");
        assertEquals("/dsdpt30", probe.buckets.get(1));
    }

    @Test
    public void bundleMatchesExistingRetrievalSettingsForEverySource() {
        DataSourceRegistry registry = DataSourceRegistry.bundled();
        for (String id : List.of("MMM", "MRN", "DPT30", "DPT34", "DPT76", "NIM")) {
            assertEquals(DataSourceTestSettings.settings(id), registry.source(id).settings());
        }
    }

    @Test
    public void malformedRegistryIsRejected() throws Exception {
        String valid = "{\"TEST\":{\"id\":\"TEST\",\"activeDatasetId\":\"current\",\"datasets\":{\"current\":{\"inputs\":["
                + "{\"url\":\"https://example.invalid/source.csv\",\"contentType\":\"text/csv\"}]}}}}";
        var registry = DataSourceRegistry.read(new ByteArrayInputStream(valid.getBytes(StandardCharsets.UTF_8)));
        assertEquals("dstest", registry.source("TEST").bucket());
        assertEquals("TEST_Comptage.csv", registry.source("TEST").settings().inputs().get(0).objectName());
        for (String input : List.of("{}", "[]", valid.replace("\"id\":\"TEST\"", "\"id\":\"OTHER\""),
                valid.replace("\"activeDatasetId\":\"current\"", "\"activeDatasetId\":\"missing\""),
                valid.replace("https://example.invalid/source.csv", "file:///tmp/source.csv"), valid + " {}")) {
            try {
                DataSourceRegistry.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
                fail("Expected invalid registry");
            } catch (IllegalArgumentException | IOException expected) {
                // Configuration is rejected before any client construction.
            }
        }

    }

    private static Probe configured(Map<String, String> config) {
        var runtime = new FunctionRuntimeContext(new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"), config);
        new FunctionConfigurer().configure(runtime);
        return (Probe) runtime.getInvokeInstance().orElseThrow();
    }

    public static class Probe extends DataSourceFunction {
        final List<String> buckets = new ArrayList<>();
        final List<URI> requests = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        int storageClosed;
        int httpClosed;
        boolean fail;

        @Override protected HttpClient createHttpClient() {
            return new HttpClient() {
                @Override public EntityBody get(URI uri, String validator) throws IOException {
                    assertEquals("retrieval", MDC.get("stage"));
                    requests.add(uri);
                    if (fail) throw new IOException("download failed");
                    return new EntityBody("validator", null, new ByteArrayInputStream(new byte[]{1}));
                }
                @Override public void close() { httpClosed++; super.close(); }
            };
        }

        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
            buckets.add(namespace + "/" + bucket);
            return new ObjectStoreClient(null, namespace, bucket) {
                @Override public OOSResourceMetadata getResourceMetadata(String name) { return null; }
                @Override public void putResource(String name, String type, OOSResourceMetadata metadata, InputStream input) {
                    writes.add(MDC.get("datasourceId") + ":" + name);
                }
                @Override public void close() { storageClosed++; }
            };
        }
    }
}
