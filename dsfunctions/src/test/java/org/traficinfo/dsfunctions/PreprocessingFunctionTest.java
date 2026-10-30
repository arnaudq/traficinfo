package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class PreprocessingFunctionTest {
    private static PreprocessingSettings settings() {
        return new PreprocessingSettings(new DataSourceSettings("NIM", "initial", List.of(
                new DataSourceSettings.Input("https://example.invalid/counts", "NIM_Comptage.csv", "text/csv"))),
                "https://example.invalid/dataset", List.of(2022));
    }

    @Test
    public void fnLoadsSettingsAndPublishesIntoConfiguredBucket() throws Exception {
        FunctionRuntimeContext context = runtime(Map.of("NAMESPACE", "namespace", "BUCKETNAME", "custom",
                PreprocessingSettings.CONFIG_KEY, new ObjectMapper().writeValueAsString(settings())));
        new FunctionConfigurer().configure(context);
        Probe probe = (Probe) context.getInvokeInstance().orElseThrow();
        assertEquals("namespace/custom", probe.bucket);
        assertEquals("OK", context.getMethod().getTargetMethod().invoke(probe, ""));
        assertTrue(probe.store.objects.containsKey("NIM_Manifest.json"));
    }

    @Test
    public void missingSettingsOrBucketFailBeforeCreatingClients() throws Exception {
        String settings = new ObjectMapper().writeValueAsString(settings());
        for (Map<String, String> config : List.of(Map.of("BUCKETNAME", "test"),
                Map.of(PreprocessingSettings.CONFIG_KEY, settings), Map.of(PreprocessingSettings.CONFIG_KEY, "{}"))) {
            Probe.created = 0;
            try {
                new FunctionConfigurer().configure(runtime(config));
                fail("Expected invalid configuration");
            } catch (Exception expected) {
                assertEquals(0, Probe.created);
            }
        }
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
            store.source(settings(), "id_camera;date;tranche;nbPassage;coordonnees_geographique\n52;2022-01-01;00:00 - 01:00;10;43.8, 4.4\n");
            return store;
        }
    }
}
