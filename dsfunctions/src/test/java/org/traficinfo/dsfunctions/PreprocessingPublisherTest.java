package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.traficinfo.parsers.nim.NIMComptage;
import org.traficinfo.parsers.storage.ComptageJson;
import org.traficinfo.parsers.storage.DataManifest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class PreprocessingPublisherTest {
    private static final String HEADER = "id_camera;date;tranche;nbPassage\n";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String row(String camera, int year, int count) {
        return camera + ";" + year + "-01-01;00:00 - 01:00;" + count + "\n";
    }

    @Test
    public void bootstrapsAndRoundTripsNullMeasuresAndPublishesManifestLast() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022));
        store.source(settings, HEADER + row("52", 2022, 10) + row("52", 1970, 20));
        DataManifest manifest = new PreprocessingPublisher(store).publish(settings);
        assertEquals("initial", manifest.activeDatasetId());
        assertEquals(List.of("id_camera", "date", "tranche", "nbPassage"),
                manifest.datasets().get("initial").inputs().get("NIM_Comptage.csv").headers());
        DataManifest.YearData year = manifest.years().get(2022);
        byte[] bytes = store.objects.get(year.object());
        assertEquals(1, year.recordCount());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), year.sha256());
        List<? extends org.traficinfo.parsers.Comptage> counts = new ComptageJson().read(new ByteArrayInputStream(bytes), NIMComptage.class);
        NIMComptage count = (NIMComptage) counts.get(0);
        assertNull(count.getDailyAverage());
        assertEquals(1, count.getObservedHours());
        assertEquals(10, count.getTotalPassages());
        assertEquals(List.of(year.object(), "NIM_Manifest.json"), store.publications());
        assertFalse(store.objects.containsKey("NIM_Preprocessing.lock"));
    }

    @Test
    public void reorderingRowsAndRetryingDoNotRepublish() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022));
        store.source(settings, HEADER + row("52", 2022, 10) + row("53", 2022, 20));
        PreprocessingPublisher publisher = new PreprocessingPublisher(store);
        DataManifest first = publisher.publish(settings);
        store.writes.clear();
        store.source(settings, HEADER + row("53", 2022, 20) + row("52", 2022, 10));
        assertEquals(first, publisher.publish(settings));
        assertTrue(store.publications().isEmpty());
    }

    @Test
    public void preservesInactiveYearsAndRetainsUnchangedYearPaths() throws Exception {
        Store store = new Store();
        PreprocessingPublisher publisher = new PreprocessingPublisher(store);
        PreprocessingSettings initial = settings("initial", List.of(2022));
        store.source(initial, HEADER + row("52", 2022, 10));
        DataManifest first = publisher.publish(initial);
        PreprocessingSettings current = settings("current", List.of(2023, 2024));
        store.source(current, HEADER + row("52", 2023, 20) + row("52", 2024, 30));
        DataManifest second = publisher.publish(current);
        assertEquals(first.years().get(2022), second.years().get(2022));
        assertEquals(2, second.datasets().size());
        store.source(current, HEADER + row("52", 2023, 20) + row("52", 2024, 40));
        DataManifest third = publisher.publish(current);
        assertEquals(second.years().get(2023), third.years().get(2023));
        assertNotEquals(second.years().get(2024).object(), third.years().get(2024).object());
        assertTrue(store.objects.containsKey(second.years().get(2024).object()));
    }

    @Test
    public void dpt34DatasetSwitchRetainsEarlierYearsWhenManifestHeadHasNoCustomMetadata() throws Exception {
        Store store = new Store() {
            @Override public OOSResourceMetadata getResourceMetadata(String name) {
                var headClient = (com.oracle.bmc.objectstorage.ObjectStorage) java.lang.reflect.Proxy.newProxyInstance(
                        com.oracle.bmc.objectstorage.ObjectStorage.class.getClassLoader(),
                        new Class<?>[]{com.oracle.bmc.objectstorage.ObjectStorage.class},
                        (proxy, method, args) -> {
                            assertEquals("headObject", method.getName());
                            if (!objects.containsKey(name)) {
                                throw new com.oracle.bmc.model.BmcException(404, "test", "Missing Object", "request");
                            }
                            Map<String, String> values = metadata.get(name);
                            return com.oracle.bmc.objectstorage.responses.HeadObjectResponse.builder()
                                    .opcMeta(values == null || values.isEmpty() ? null : values).build();
                        });
                return new ObjectStoreClient(headClient, "test", "dsdpt34").getResourceMetadata(name);
            }
        };
        StringBuilder csv = new StringBuilder("Identifiant du compteur;Année;mja_tv_tcj\n");
        for (int year = 2014; year <= 2025; year++) csv.append("site;").append(year).append(";100\n");
        List<Integer> earlier = List.of(2014, 2015, 2016, 2017, 2018, 2019);
        List<Integer> later = List.of(2020, 2021, 2022, 2023, 2024, 2025);
        var input = new DataSourceSettings.Input("https://example.invalid/dpt34.csv", "DPT34_Comptage.csv", "text/csv");
        var initial = new PreprocessingSettings(new DataSourceSettings("DPT34", "dpt34-2014-2019", List.of(input)),
                "https://example.invalid/dpt34", earlier);
        store.source(initial, csv.toString());
        PreprocessingPublisher publisher = new PreprocessingPublisher(store);
        DataManifest first = publisher.publish(initial);
        Map<Integer, byte[]> originalYears = new HashMap<>();
        first.years().forEach((year, data) -> originalYears.put(year, store.objects.get(data.object())));
        var current = new PreprocessingSettings(new DataSourceSettings("DPT34", "dpt34-2020-2025", List.of(input)),
                "https://example.invalid/dpt34", later);
        // Reuse the same downloaded CSV, whose metadata still names the earlier dataset.
        DataManifest next = publisher.publish(current);
        assertEquals("dpt34-2014-2019", store.metadata.get(input.objectName()).get("dataset-id"));
        assertEquals("dpt34-2020-2025", next.activeDatasetId());
        assertEquals(12, next.years().size());
        assertEquals(2, next.datasets().size());
        for (int year : earlier) {
            assertEquals(first.years().get(year), next.years().get(year));
            assertArrayEquals(originalYears.get(year), store.objects.get(next.years().get(year).object()));
        }
        for (int year : later) assertEquals("dpt34-2020-2025", next.years().get(year).datasetId());
    }

    @Test
    public void logsDistinctYearsOutsideScopeAndConfiguredYearsWithoutRecordsAtInfoLevel() throws Exception {
        Store store = new Store();
        List<String> messages = new ArrayList<>();
        PreprocessingSettings settings = settings("initial", List.of(2022, 2023, 2024));
        store.source(settings, HEADER + row("52", 2025, 10) + row("53", 2025, 20)
                + row("52", 2022, 30) + row("52", 2021, 40));
        DataManifest manifest = new PreprocessingPublisher(store, infoLogger(messages)).publish(settings);
        assertEquals(java.util.Set.of(2022), manifest.years().keySet());
        assertEquals(settings.years(), manifest.datasets().get("initial").years());
        assertTrue(messages.contains("Skipping records outside configured years: dataSourceId=NIM, datasetId=initial, years=[2021, 2025]"));
        assertTrue(messages.contains("Configured years with no records: dataSourceId=NIM, datasetId=initial, years=[2023, 2024]"));
    }

    @Test
    public void missingYearsRetainPublishedRecordsWhilePresentYearsCanChange() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022, 2023));
        store.source(settings, HEADER + row("52", 2022, 10) + row("52", 2023, 20));
        PreprocessingPublisher publisher = new PreprocessingPublisher(store);
        DataManifest first = publisher.publish(settings);
        store.source(settings, HEADER + row("52", 2022, 30) + row("52", 2024, 40));
        DataManifest next = publisher.publish(settings);
        assertEquals(first.years().get(2023), next.years().get(2023));
        assertNotEquals(first.years().get(2022).object(), next.years().get(2022).object());
        assertFalse(next.years().containsKey(2024));
        for (String csv : List.of(HEADER, HEADER + row("52", 2024, 10))) {
            store.writes.clear();
            store.source(settings, csv);
            assertEquals(next, publisher.publish(settings));
            assertTrue(store.publications().isEmpty());
        }
    }

    @Test
    public void noRecordsCanBootstrapAnEmptyPublicationAndLaterAddRecords() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022, 2023));
        store.source(settings, HEADER);
        PreprocessingPublisher publisher = new PreprocessingPublisher(store);
        DataManifest empty = publisher.publish(settings);
        assertTrue(empty.years().isEmpty());
        assertEquals(settings.years(), empty.datasets().get("initial").years());
        assertEquals(empty, publisher.publish(settings));
        store.source(settings, HEADER + row("52", 2022, 10));
        assertEquals(java.util.Set.of(2022), publisher.publish(settings).years().keySet());
    }

    @Test
    public void checksHeadersInitiallyAndAgainstPreviousDatasetWhileAllowingReorderingAndAdditions() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022));
        for (String bad : List.of("id_camera;date;tranche\n", "id_camera;date;tranche;nbPassage;NBPASSAGE\n")) {
            store.source(settings, bad);
            expectFailure(store, settings, "header");
            assertNull(store.objects.get("NIM_Manifest.json"));
        }
        store.source(settings, HEADER + row("52", 2022, 10));
        new PreprocessingPublisher(store).publish(settings);
        store.source(settings, "nbPassage;tranche;date;id_camera;extra\n10;00:00 - 01:00;2022-01-01;52;ignored\n");
        new PreprocessingPublisher(store).publish(settings);
        byte[] previous = store.objects.get("NIM_Manifest.json");
        store.source(settings, HEADER + row("52", 2022, 10));
        expectFailure(store, settings, "headers removed");
        assertArrayEquals(previous, store.objects.get("NIM_Manifest.json"));
    }

    @Test
    public void warnsOnlyForHeadersAddedSinceThePreviousPublication() throws Exception {
        List<String> warnings = new ArrayList<>();
        org.slf4j.Logger logger = (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(
                org.slf4j.Logger.class.getClassLoader(), new Class<?>[]{org.slf4j.Logger.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("warn")) {
                        Object[] values = args.length == 2 && args[1] instanceof Object[] array
                                ? array : java.util.Arrays.copyOfRange(args, 1, args.length);
                        warnings.add(org.slf4j.helpers.MessageFormatter.arrayFormat((String) args[0], values).getMessage());
                    }
                    return method.getReturnType() == boolean.class ? true : null;
                });
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022));
        PreprocessingPublisher publisher = new PreprocessingPublisher(store, logger);
        store.source(settings, HEADER + row("52", 2022, 10));
        publisher.publish(settings);
        assertTrue(warnings.isEmpty());
        store.source(settings, "nbPassage;tranche;date;id_camera\n10;00:00 - 01:00;2022-01-01;52\n");
        publisher.publish(settings);
        assertTrue(warnings.isEmpty());
        store.source(settings, "nbPassage;tranche;date;id_camera;extra;another\n10;00:00 - 01:00;2022-01-01;52;ignored;ignored\n");
        publisher.publish(settings);
        assertEquals(List.of("CSV headers added: dataSourceId=NIM, datasetId=initial, input=NIM_Comptage.csv, headers=[extra, another]"), warnings);
        warnings.clear();
        publisher.publish(settings);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void rejectsMissingInputWrongProvenanceAndDatasetScopeChanges() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022));
        expectFailure(store, settings, "Missing input");
        store.source(settings, HEADER + row("52", 2022, 10));
        for (String field : List.of("datasource-id", "source-url")) {
            store.metadata.get("NIM_Comptage.csv").put(field, "wrong");
            expectFailure(store, settings, "configured source");
            store.source(settings, HEADER + row("52", 2022, 10));
        }
        store.metadata.get("NIM_Comptage.csv").remove("dataset-id");
        new PreprocessingPublisher(store).publish(settings);
        expectFailure(store, settings("initial", List.of(2023)), "scope");
    }

    @Test
    public void failedWriteOrReadbackNeverReplacesManifestAndRetryCanRecover() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022));
        store.source(settings, HEADER + row("52", 2022, 10));
        DataManifest first = new PreprocessingPublisher(store).publish(settings);
        byte[] manifest = store.objects.get("NIM_Manifest.json");
        store.source(settings, HEADER + row("52", 2022, 20));
        store.failYearWrite = true;
        expectFailure(store, settings, "write failed");
        assertArrayEquals(manifest, store.objects.get("NIM_Manifest.json"));
        store.failYearWrite = false;
        store.corruptYearRead = true;
        expectFailure(store, settings, "verification failed");
        assertArrayEquals(manifest, store.objects.get("NIM_Manifest.json"));
        store.corruptYearRead = false;
        DataManifest next = new PreprocessingPublisher(store).publish(settings);
        assertNotEquals(first.revision(), next.revision());
    }

    @Test
    public void sourceChangeDuringProcessingAndCorruptInactiveYearBlockPublication() throws Exception {
        Store store = new Store();
        PreprocessingSettings initial = settings("initial", List.of(2022));
        store.source(initial, HEADER + row("52", 2022, 10));
        DataManifest first = new PreprocessingPublisher(store).publish(initial);
        byte[] manifest = store.objects.get("NIM_Manifest.json");
        store.mutateSourceOnYearRead = true;
        expectFailure(store, initial, "Source changed");
        assertArrayEquals(manifest, store.objects.get("NIM_Manifest.json"));
        store.mutateSourceOnYearRead = false;
        PreprocessingSettings current = settings("current", List.of(2023));
        store.source(current, HEADER + row("52", 2023, 10));
        store.objects.put(first.years().get(2022).object(), "[]".getBytes(StandardCharsets.UTF_8));
        expectFailure(store, current, "verification failed");
        assertArrayEquals(manifest, store.objects.get("NIM_Manifest.json"));
    }

    @Test
    public void concurrentInvocationCannotAcquirePublicationLock() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022));
        store.source(settings, HEADER + row("52", 2022, 10));
        store.objects.put("NIM_Preprocessing.lock", new byte[0]);
        expectFailure(store, settings, "already locked");
        assertTrue(store.objects.containsKey("NIM_Preprocessing.lock"));
        assertNull(store.objects.get("NIM_Manifest.json"));
    }

    @Test
    public void rejectsUnsupportedAndWrongDatasourceManifests() throws Exception {
        Store store = new Store();
        PreprocessingSettings settings = settings("initial", List.of(2022));
        store.source(settings, HEADER + row("52", 2022, 10));
        new PreprocessingPublisher(store).publish(settings);
        String valid = new String(store.objects.get("NIM_Manifest.json"), StandardCharsets.UTF_8);
        for (String invalid : List.of(valid.replace("\"manifestVersion\":1", "\"manifestVersion\":2"),
                valid.replace("\"dataSourceId\":\"NIM\"", "\"dataSourceId\":\"MMM\""))) {
            store.objects.put("NIM_Manifest.json", invalid.getBytes(StandardCharsets.UTF_8));
            expectFailure(store, settings, "");
        }
    }

    @Test
    public void mmmIncludesCompanionGeometryInTheStoredCountsAndRequiresIt() throws Exception {
        Store store = new Store();
        var csv = new DataSourceSettings.Input("https://example.invalid/counts", "legacy.csv", "text/csv");
        var geometry = new DataSourceSettings.Input("https://example.invalid/geometry", "legacy.json", "application/json");
        var settings = new PreprocessingSettings(new DataSourceSettings("MMM", "current", List.of(csv, geometry)),
                "https://example.invalid/dataset", List.of(2024));
        store.source(settings, "code_centrale,annee,mja_jo,type_compteur,code_canal,nom_commune,name\n"
                + "52,2024,100,compteur feux,Z,Montpellier,Rue exemple\n");
        expectFailure(store, settings, "Missing input");
        store.objects.put(geometry.objectName(), ("{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"code_centrale\":\"52\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[3.8,43.6]}}]}").getBytes(StandardCharsets.UTF_8));
        store.metadata.put(geometry.objectName(), Map.of("datasource-id", "MMM", "dataset-id", "current",
                "source-url", geometry.url(), "downloaded-at", "2026-01-01T00:00:00Z"));
        DataManifest manifest = new PreprocessingPublisher(store).publish(settings);
        byte[] bytes = store.objects.get(manifest.years().get(2024).object());
        var counts = new ComptageJson().read(new ByteArrayInputStream(bytes), org.traficinfo.parsers.m3m.M3MComptage.class);
        assertEquals(new org.geojson.Point(3.8, 43.6), counts.get(0).getGeometry());
        assertNull(manifest.datasets().get("current").inputs().get("legacy.json").headers());
    }

    private static org.slf4j.Logger infoLogger(List<String> messages) {
        return (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(
                org.slf4j.Logger.class.getClassLoader(), new Class<?>[]{org.slf4j.Logger.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("info")) {
                        Object[] values = args.length == 2 && args[1] instanceof Object[] array
                                ? array : java.util.Arrays.copyOfRange(args, 1, args.length);
                        messages.add(org.slf4j.helpers.MessageFormatter.arrayFormat((String) args[0], values).getMessage());
                    }
                    return method.getReturnType() == boolean.class ? true : null;
                });
    }

    private static PreprocessingSettings settings(String dataset, List<Integer> years) {
        return new PreprocessingSettings(new DataSourceSettings("NIM", dataset, List.of(
                new DataSourceSettings.Input("https://example.invalid/" + dataset, "NIM_Comptage.csv", "text/csv"))),
                "https://example.invalid/dataset", years);
    }

    private static void expectFailure(Store store, PreprocessingSettings settings, String message) throws Exception {
        try {
            new PreprocessingPublisher(store).publish(settings);
            fail("Expected preprocessing failure");
        } catch (IOException failure) {
            assertTrue(failure.toString(), failure.toString().toLowerCase().contains(message.toLowerCase()));
        }
    }

    static class Store extends ObjectStoreClient {
        final Map<String, byte[]> objects = new HashMap<>();
        final Map<String, Map<String, String>> metadata = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean failYearWrite;
        boolean corruptYearRead;
        boolean mutateSourceOnYearRead;

        Store() { super(null, "test", "test"); }

        void source(PreprocessingSettings settings, String csv) {
            DataSourceSettings.Input input = settings.source().inputs().get(0);
            objects.put(input.objectName(), csv.getBytes(StandardCharsets.UTF_8));
            metadata.put(input.objectName(), new HashMap<>(Map.of("datasource-id", settings.source().dataSourceId(),
                    "dataset-id", settings.source().datasetId(), "source-url", input.url(), "downloaded-at", "2026-01-01T00:00:00Z")));
        }

        List<String> publications() { return writes.stream().filter(name -> !name.endsWith(".lock")).toList(); }

        @Override public OOSResourceMetadata getResourceMetadata(String name) {
            return !objects.containsKey(name) ? null : new OOSResourceMetadata(metadata.getOrDefault(name, Map.of()));
        }

        @Override public InputStream getResource(String name) throws IOException {
            if (mutateSourceOnYearRead && name.startsWith("data/")) {
                metadata.get("NIM_Comptage.csv").put("downloaded-at", "2026-01-02T00:00:00Z");
            }
            byte[] bytes = objects.get(name);
            if (bytes == null) throw new IOException("Missing Object: " + name);
            if (corruptYearRead && name.startsWith("data/")) bytes = "[]".getBytes(StandardCharsets.UTF_8);
            return new ByteArrayInputStream(bytes);
        }

        @Override public void putResource(String name, String type, OOSResourceMetadata meta, InputStream body) throws IOException {
            if (failYearWrite && name.startsWith("data/")) throw new IOException("Year write failed");
            objects.put(name, body.readAllBytes());
            metadata.put(name, Map.copyOf(meta.getMetadata()));
            writes.add(name);
        }

        @Override public synchronized boolean createResource(String name, String type, OOSResourceMetadata meta, InputStream body) throws IOException {
            if (objects.containsKey(name)) return false;
            putResource(name, type, meta, body);
            return true;
        }

        @Override public void deleteResource(String name) { objects.remove(name); metadata.remove(name); }
    }
}
