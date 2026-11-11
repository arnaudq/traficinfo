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

import java.util.Map;

import static org.junit.Assert.*;

public class PreprocessingFunctionTest {
    private static PreprocessingSettings settings() {
        return DataSourceRegistry.bundled().preprocessing("NIM", null);
    }

    @Test
    public void fnSelectsBundledSettingsAndBucketAtInvocation() throws Exception {
        FunctionRuntimeContext context = runtime(Map.of("NAMESPACE", "namespace"));
        new FunctionConfigurer().configure(context);
        Probe probe = (Probe) context.getInvokeInstance().orElseThrow();
        assertNull(probe.bucket);
        assertEquals("OK", context.getMethod().getTargetMethod().invoke(probe, "{\"datasourceId\":\"NIM\"}"));
        assertEquals("namespace/dsnim", probe.bucket);
        assertTrue(probe.store.objects.containsKey("NIM_Manifest.json"));
    }

    @Test
    public void inputEventsSelectSourceAndUnchangedOutputKeepsManifest() {
        var context = runtime(Map.of("NAMESPACE", "namespace"));
        new FunctionConfigurer().configure(context);
        Probe probe = (Probe) context.getInvokeInstance().orElseThrow();
        String event = "{\"eventType\":\"com.oraclecloud.objectstorage.updateobject\",\"data\":{"
                + "\"resourceName\":\"NIM_Comptage.csv\",\"additionalDetails\":{\"bucketName\":\"dsnim\",\"namespace\":\"namespace\"}}}";
        assertEquals("OK", probe.handleRequest(event));
        String manifest = new String(probe.store.objects.get("NIM_Manifest.json"), java.nio.charset.StandardCharsets.UTF_8);
        probe.store.writes.clear();
        assertEquals("OK", probe.handleRequest(event));
        assertEquals(manifest, new String(probe.store.objects.get("NIM_Manifest.json"), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(probe.store.writes.contains("NIM_Manifest.json"));
        for (String invalid : new String[]{"", "{}", event.replace("dsnim", "wrong"),
                event.replace("NIM_Comptage.csv", "archive.csv"), event.replace("updateobject", "deleteobject")}) {
            int created = Probe.created;
            try { probe.handleRequest(invalid); fail("Expected invalid event"); }
            catch (IllegalArgumentException expected) { assertEquals(created, Probe.created); }
        }
    }

    @Test
    public void eventRoutingUsesLastConfiguredInputAndBucket() {
        var registry = DataSourceRegistry.bundled();
        assertEquals("MMM", registry.datasourceForInput("ds3mbucket", "MMM_MMM_Comptage.csv"));
        assertEquals("DPT76", registry.datasourceForInput("dsdpt76", "DPT76_Comptage_2022.geojson"));
        assertEquals("NIM", registry.datasourceForInput("dsnim", "NIM_Comptage.csv"));
        var inputs = registry.source("MMM").settings().inputs();
        try {
            registry.datasourceForInput("ds3mbucket", inputs.get(0).objectName());
            fail("Companion input must not trigger preprocessing");
        } catch (IllegalArgumentException expected) { }
    }

    private static FunctionRuntimeContext runtime(Map<String, String> config) {
        return new FunctionRuntimeContext(new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"), config);
    }

    public static class Probe extends PreprocessingFunction {
        static int created;
        final PreprocessingPublisherTest.Store store = new PreprocessingPublisherTest.Store();
        String bucket;

        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
            created++;
            this.bucket = namespace + "/" + bucket;
            if (!store.objects.containsKey("NIM_Comptage.csv")) {
                store.source(settings(), "id_camera;date;tranche;nbPassage;coordonnees_geographique\n52;2023-01-01;00:00 - 01:00;10;43.8, 4.4\n");
            }
            return store;
        }
    }
}
