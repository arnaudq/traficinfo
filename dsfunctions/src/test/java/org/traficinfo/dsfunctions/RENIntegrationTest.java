package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;
import org.traficinfo.parsers.ren.RENComptage;
import org.traficinfo.parsers.ren.RENComptageParser;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RENIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PreprocessingSettings settings() {
        return DataSourceRegistry.bundled().preprocessing("REN", null);
    }

    private static String sample() throws Exception {
        Path root = Path.of(System.getProperty("basedir", ".")).resolve("..");
        String snapshot = System.getProperty("ren.snapshot");
        if (snapshot != null) return Files.readString(Path.of(snapshot));
        // Reuse the source fixture's three 2017 directions as current-year records.
        return Files.readString(root.resolve("parsers/src/test/resources/RENSample.csv"))
                .replace(";2017;", ";2026;");
    }

    @Test public void publishesScopedYearlyRecordsAndGeoJsonUsingCachedOsmWithoutHttp() throws Exception {
        String input = sample();
        var settings = settings();
        assertEquals(List.of(2026), settings.years());
        assertTrue(settings.source().inputs().getFirst().url().contains("where=annee%3E%3D2026"));
        var source = new PreprocessingPublisherTest.Store();
        try (var download = new HttpClient() {
            @Override public EntityBody get(java.net.URI uri, String validator) {
                assertEquals(settings.source().inputs().getFirst().url(), uri.toString());
                return new EntityBody("source-validator", null, new java.io.ByteArrayInputStream(
                        input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }) {
            new SourceObjectPublisher(download, source).publish(settings.source());
        }
        assertEquals("REN", source.metadata.get("REN_Comptage.csv").get("datasource-id"));
        var manifest = new PreprocessingPublisher(source).publish(settings);
        var parsed = new RENComptageParser().parseComptages(new StringReader(input));
        long expected = parsed.stream().filter(c -> c.getAnnee() == 2026).count();
        assertEquals(expected, manifest.years().values().stream().mapToLong(y -> y.recordCount()).sum());
        assertEquals(java.util.Set.of(2026), manifest.years().keySet());
        assertEquals("CSV", manifest.datasets().get("current").inputs().get("REN_Comptage.csv").format());
        assertTrue(manifest.datasets().get("current").inputs().get("REN_Comptage.csv").headers().contains("geo_point_2d"));
        assertEquals("REN_Manifest.json", source.publications().getLast());
        var restored = new ManifestComptageReader(source).read("REN");
        assertEquals(expected, restored.size());
        RENComptage first = (RENComptage) restored.getFirst();
        assertEquals(parsed.stream().filter(c -> c.getCodeCentrale().equals(first.getCodeCentrale())
                && c.getAnnee() == first.getAnnee()).findFirst().orElseThrow(), first);
        var output = new PreprocessingPublisherTest.Store();
        var osm = new PreprocessingPublisherTest.Store();
        osm.objects.put("REN_OSMInfo.json", JSON.writeValueAsBytes(Map.of("123", Map.of(
                "lastUpdated", java.time.Instant.now().toString(), "tags", Map.of("maxspeed", "50")))));
        osm.objects.put("REN_OSMWayMapping.json", JSON.writeValueAsBytes(Map.of(first.getCodeCentrale(),
                Map.of("osmWayId", 123, "status", "MATCHED"))));
        try (var http = new OpenStreetMapClient() {
            @Override public Map<String, String> getWayTags(long id) {
                throw new AssertionError("Publication must use the OSM cache");
            }
        }) {
            var provider = new OSMInfoProviderImpl(osm, http, "REN_OSMInfo.json", "REN_OSMWayMapping.json");
            var publisher = new GeoJsonPublisher(GeoJsonDataSource.REN, source, output, provider);
            assertEquals("OK", publisher.publish());
            var features = JSON.readTree(output.objects.get("REN_TraficInfo.geojson")).get("features");
            assertTrue(features.size() > 0);
            System.out.println("REN rendered vehicle features: " + features.size());
            var ids = new java.util.HashSet<String>();
            for (var feature : features) {
                assertEquals("REN", feature.at("/properties/data_source").asText());
                assertTrue(ids.add(feature.at("/properties/code_centrale").asText()));
                if (feature.at("/properties/code_centrale").asText().equals(first.getCodeCentrale())) {
                    assertEquals("50", feature.at("/properties/maximum_speed").asText());
                }
            }
            assertEquals(0, JSON.readTree(output.objects.get("REN_TraficInfo_BICYCLES.geojson")).get("features").size());
            assertTrue(osm.objects.containsKey("REN_OSMRequests.json"));
            byte[] previousOutput = output.objects.get("REN_TraficInfo.geojson");
            source.objects.put(manifest.years().values().iterator().next().object(), "[]".getBytes());
            assertThrows(IllegalStateException.class, publisher::publish);
            assertSame(previousOutput, output.objects.get("REN_TraficInfo.geojson"));
        }
        System.out.println("REN local pipeline: " + parsed.size() + " source records, " + expected
                + " retained for the current period, " + manifest.years().size() + " published years");
    }

    @Test public void reorderedInputIsUnchangedAndInvalidOrFailedWritesPreserveManifest() throws Exception {
        var store = new PreprocessingPublisherTest.Store();
        var settings = settings();
        store.source(settings, sample());
        var publisher = new PreprocessingPublisher(store);
        var first = publisher.publish(settings);
        var lines = sample().lines().toList();
        var reordered = new java.util.ArrayList<>(lines.subList(1, lines.size()));
        java.util.Collections.reverse(reordered);
        store.source(settings, lines.getFirst() + "\n" + String.join("\n", reordered) + "\n");
        store.writes.clear();
        assertEquals(first, publisher.publish(settings));
        assertTrue(store.publications().isEmpty());
        byte[] previous = store.objects.get("REN_Manifest.json");
        store.source(settings, sample() + lines.get(1) + "\n");
        assertThrows(java.io.IOException.class, () -> publisher.publish(settings));
        assertArrayEquals(previous, store.objects.get("REN_Manifest.json"));
        // Change one count while preserving the source's full CSV shape.
        store.source(settings, sample().replace(";6.0;", ";123.0;"));
        store.failYearWrite = true;
        assertThrows(java.io.IOException.class, () -> publisher.publish(settings));
        assertArrayEquals(previous, store.objects.get("REN_Manifest.json"));
        assertFalse(store.objects.containsKey("REN_Preprocessing.lock"));
    }

    private static String archiveCsv(PreprocessingSettings archive) throws Exception {
        String directory = System.getProperty("ren.archiveDirectory");
        if (directory != null) return Files.readString(Path.of(directory,
                archive.source().inputs().getFirst().objectName()));
        // One source-shaped record for each of the five configured observation years.
        var lines = sample().lines().toList();
        var csv = new StringBuilder(lines.getFirst()).append('\n');
        for (int year : archive.years()) {
            csv.append(lines.get(1).replace(";2026;", ";" + year + ";")).append('\n');
        }
        return csv.toString();
    }

    @Test public void archivePeriodsRetainEarlierYearsAndCurrentRemainsActive() throws Exception {
        var registry = DataSourceRegistry.bundled();
        var active = settings();
        var store = new PreprocessingPublisherTest.Store();
        var publisher = new PreprocessingPublisher(store);
        int expectedYears = 0;
        long expectedRecords = 0;
        for (String dataset : List.of("ren-2021-2025", "ren-2016-2020", "ren-2011-2015")) {
            var archive = registry.preprocessing("REN", dataset);
            String name = archive.source().inputs().getFirst().objectName();
            String csv = archiveCsv(archive);
            byte[] raw = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            store.objects.put(name, raw); // Manually uploaded CSVs have no downloader metadata.
            var manifest = publisher.publishArchive(archive, active);
            expectedYears += 5;
            expectedRecords += new RENComptageParser().parseComptages(new StringReader(csv)).size();
            assertEquals(expectedYears, manifest.years().size());
            assertEquals("current", manifest.activeDatasetId());
            assertEquals(List.of(2026), manifest.datasets().get("current").years());
            assertEquals(archive.years(), manifest.datasets().get(dataset).years());
            assertEquals(expectedRecords, new ManifestComptageReader(store).read("REN").size());
            for (int year : archive.years()) assertEquals(dataset, manifest.years().get(year).datasetId());
            assertSame(raw, store.objects.get(name));
            store.writes.clear();
            assertEquals(manifest, publisher.publishArchive(archive, active));
            assertTrue(store.publications().isEmpty());
            System.out.println("REN archive " + dataset + ": " + manifest.years().size()
                    + " years, " + expectedRecords + " cumulative records");
        }
        store.source(active, sample());
        var combined = publisher.publish(active);
        assertEquals(16, combined.years().size());
        assertEquals(4, combined.datasets().size());
        assertEquals("current", combined.years().get(2026).datasetId());
        assertEquals("ren-2021-2025", combined.years().get(2025).datasetId());
        assertEquals("ren-2016-2020", combined.years().get(2020).datasetId());
        assertEquals("ren-2011-2015", combined.years().get(2011).datasetId());
    }

    @Test public void sharedPreprocessingHandlerLoadsRenBucketAndSettings() {
        var context = new FunctionRuntimeContext(new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"),
                Map.of("NAMESPACE", "testns"));
        new FunctionConfigurer().configure(context);
        var probe = (Probe) context.getInvokeInstance().orElseThrow();
        assertEquals("OK", probe.handleRequest("{\"datasourceId\":\"REN\"}"));
        assertEquals("testns/dsren", probe.bucket);
        assertTrue(probe.store.objects.containsKey("REN_Manifest.json"));
        assertEquals("OK", probe.handleRequest("{\"datasourceId\":\"REN\",\"datasetId\":\"ren-2021-2025\"}"));
        try {
            var manifest = JSON.readTree(probe.store.objects.get("REN_Manifest.json"));
            assertEquals("current", manifest.get("activeDatasetId").asText());
            assertTrue(manifest.get("years").has("2021"));
            assertTrue(manifest.get("years").has("2026"));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    public static class Probe extends PreprocessingFunction {
        final PreprocessingPublisherTest.Store store = new PreprocessingPublisherTest.Store();
        String bucket;
        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String name) {
            bucket = namespace + "/" + name;
            try {
                store.source(settings(), sample());
                var archive = DataSourceRegistry.bundled().preprocessing("REN", "ren-2021-2025");
                store.objects.put(archive.source().inputs().getFirst().objectName(),
                        archiveCsv(archive).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return store;
        }
    }
}
