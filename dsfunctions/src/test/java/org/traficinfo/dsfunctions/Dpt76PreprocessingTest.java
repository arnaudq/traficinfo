package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.geojson.Point;
import org.junit.Test;
import org.traficinfo.parsers.dpt76.DPT76Comptage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class Dpt76PreprocessingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GEOJSON = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"FID":1,"objectid":5401,"Code_stat_":1235,
                "axe":"76 D0925","Annee_de_":"2022","Trafic_Mo_":14793,
                "Tmja_sens_":7387,"Tmja_sen_2":7406,"Pourcenta_":5},
                "geometry":{"type":"Point","coordinates":[1.13819767843498,49.9304738120914]}},
              {"type":"Feature","properties":{"FID":236,"objectid":5289,"Code_stat_":1235,
                "Annee_de_":"2022","Trafic_Mo_":7730},
                "geometry":{"type":"Point","coordinates":[1.2394107905937,49.9645878144106]}},
              {"type":"Feature","properties":{"FID":300,"Code_stat_":null,
                "Annee_de_":"2022","Trafic_Mo_":null},"geometry":null}
            ]}
            """;

    private static PreprocessingSettings settings() throws Exception {
        var source = DataSourceTestSettings.settings("DPT76");
        return new PreprocessingSettings(source, "https://example.invalid/dpt76", List.of(2022));
    }

    @Test
    public void publishesGeoJsonAsYearlyCountsAndRoundTripsThroughManifest() throws Exception {
        var settings = settings();
        var store = new PreprocessingPublisherTest.Store();
        store.source(settings, GEOJSON);
        var manifest = new PreprocessingPublisher(store).publish(settings);
        assertEquals("dpt76-2022", manifest.activeDatasetId());
        var input = manifest.datasets().get("dpt76-2022").inputs().get("DPT76_Comptage_2022.geojson");
        assertEquals("GEOJSON", input.format());
        assertNull(input.headers());
        assertEquals(3, manifest.years().get(2022).recordCount());
        assertEquals("1", manifest.years().get(2022).parserVersion());
        assertTrue(manifest.years().get(2022).object().endsWith("/DPT76_Comptage_2022.json"));
        assertEquals(List.of(manifest.years().get(2022).object(), "DPT76_Manifest.json"), store.publications());
        var records = new ManifestComptageReader(store).read("DPT76");
        var byId = new java.util.HashMap<String, DPT76Comptage>();
        records.forEach(record -> byId.put(record.getCodeCentrale(), (DPT76Comptage) record));
        assertEquals(3, byId.size());
        assertEquals(byId.get("DPT76_1").getStationCode(), byId.get("DPT76_236").getStationCode());
        assertEquals(14793, byId.get("DPT76_1").getMoyenneJourAnnuelle());
        assertEquals(Integer.valueOf(7387), byId.get("DPT76_1").getTmjaSens1());
        assertEquals(new Point(1.13819767843498, 49.9304738120914), byId.get("DPT76_1").getGeometry());
        assertNull(byId.get("DPT76_300").getMjaTvTcj());
        assertNull(byId.get("DPT76_300").getGeometry());
        assertFalse(store.objects.containsKey("DPT76_Preprocessing.lock"));
    }

    @Test
    public void reorderingFeaturesIsUnchangedAndUpdatesRepublishOnlyTheYear() throws Exception {
        var settings = settings();
        var store = new PreprocessingPublisherTest.Store();
        store.source(settings, GEOJSON);
        var publisher = new PreprocessingPublisher(store);
        var first = publisher.publish(settings);
        ObjectNode root = (ObjectNode) JSON.readTree(GEOJSON);
        ArrayNode features = (ArrayNode) root.get("features");
        var last = features.remove(2);
        features.insert(0, last);
        store.source(settings, root.toString());
        store.writes.clear();
        assertEquals(first, publisher.publish(settings));
        assertTrue(store.publications().isEmpty());
        ((ObjectNode) features.get(1).get("properties")).put("Trafic_Mo_", 15000);
        store.source(settings, root.toString());
        var next = publisher.publish(settings);
        assertNotEquals(first.years().get(2022).object(), next.years().get(2022).object());
        assertTrue(store.objects.containsKey(first.years().get(2022).object()));
    }

    @Test
    public void invalidGeoJsonAndFailedYearWritePreservePreviousManifest() throws Exception {
        var settings = settings();
        var store = new PreprocessingPublisherTest.Store();
        store.source(settings, GEOJSON);
        var publisher = new PreprocessingPublisher(store);
        publisher.publish(settings);
        byte[] previous = store.objects.get("DPT76_Manifest.json");
        for (String invalid : List.of("{\"error\":{\"message\":\"Failed query\"}}",
                GEOJSON.replace("\"FID\":236", "\"FID\":1"),
                GEOJSON.replace("\"features\":", "\"exceededTransferLimit\":true,\"features\":"))) {
            store.source(settings, invalid);
            store.writes.clear();
            try {
                publisher.publish(settings);
                fail("Expected invalid GeoJSON rejection");
            } catch (IOException expected) {
                assertArrayEquals(previous, store.objects.get("DPT76_Manifest.json"));
                assertTrue(store.publications().isEmpty());
                assertFalse(store.objects.containsKey("DPT76_Preprocessing.lock"));
            }
        }
        store.source(settings, GEOJSON.replace("14793", "15000"));
        store.failYearWrite = true;
        try {
            publisher.publish(settings);
            fail("Expected year write failure");
        } catch (IOException expected) {
            assertArrayEquals(previous, store.objects.get("DPT76_Manifest.json"));
            assertFalse(store.objects.containsKey("DPT76_Preprocessing.lock"));
        }
    }

    @Test
    public void includesOnlyConfiguredYearAndRetainsPublicationWhenYearHasNoRecords() throws Exception {
        var settings = settings();
        var store = new PreprocessingPublisherTest.Store();
        ObjectNode root = (ObjectNode) JSON.readTree(GEOJSON);
        ArrayNode features = (ArrayNode) root.get("features");
        ObjectNode outside = features.get(0).deepCopy();
        ((ObjectNode) outside.get("properties")).put("FID", 400).put("Annee_de_", "2021");
        features.add(outside);
        store.source(settings, root.toString());
        var publisher = new PreprocessingPublisher(store);
        var first = publisher.publish(settings);
        assertEquals(java.util.Set.of(2022), first.years().keySet());
        assertEquals(3, first.years().get(2022).recordCount());
        store.source(settings, GEOJSON.replace("2022", "2021"));
        store.writes.clear();
        assertEquals(first, publisher.publish(settings));
        assertTrue(store.publications().isEmpty());
    }

    @Test
    public void rejectsCsvAndExtraInputsForGeoJsonSource() throws Exception {
        var input = settings().source().inputs().get(0);
        for (var inputs : List.of(List.of(new DataSourceSettings.Input(input.url(), "counts.csv", "text/csv")),
                List.of(input, new DataSourceSettings.Input(input.url(), "extra.geojson", input.contentType())))) {
            try {
                new PreprocessingSettings(new DataSourceSettings("DPT76", "dpt76-2022", inputs),
                        "https://example.invalid/dataset", List.of(2022));
                fail("Expected input shape rejection");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("application/geo+json"));
            }
        }
    }

    @Test
    public void fnLoadsDeployedSettingsAndPublishesIntoConfiguredBucket() throws Exception {
        var context = new FunctionRuntimeContext(
                new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"),
                Map.of("NAMESPACE", "testns", "BUCKETNAME", "dsdpt76",
                        PreprocessingSettings.CONFIG_KEY, JSON.writeValueAsString(settings())));
        new FunctionConfigurer().configure(context);
        var probe = (Probe) context.getInvokeInstance().orElseThrow();
        assertEquals("testns/dsdpt76", probe.bucket);
        assertEquals("OK", context.getMethod().getTargetMethod().invoke(probe, ""));
        assertTrue(probe.store.objects.containsKey("DPT76_Manifest.json"));
    }

    public static class Probe extends PreprocessingFunction {
        final PreprocessingPublisherTest.Store store = new PreprocessingPublisherTest.Store();
        String bucket;
        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String name) {
            bucket = namespace + "/" + name;
            try { store.source(settings(), GEOJSON); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return store;
        }
    }
}
