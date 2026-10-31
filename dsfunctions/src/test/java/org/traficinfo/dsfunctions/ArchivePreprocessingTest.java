package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.traficinfo.parsers.storage.DataManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class ArchivePreprocessingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER = "code_centrale,annee,mja_jo,type_compteur,code_canal\n";
    private static final String GEOMETRY = """
            {"type":"FeatureCollection","features":[{"type":"Feature",
            "properties":{"code_centrale":"A"},"geometry":{"type":"Point","coordinates":[3.8,43.6]}}]}
            """;

    private static PreprocessingSettings settings(String id, String suffix, List<Integer> years) {
        return new PreprocessingSettings(new DataSourceSettings("MMM", id, List.of(
                new DataSourceSettings.Input("https://example.invalid/counts.json", "MMM_Counts" + suffix + ".json", "application/json"),
                new DataSourceSettings.Input("https://example.invalid/counts.csv", "MMM_Counts" + suffix + ".csv", "text/csv"))),
                "https://example.invalid/dataset", years);
    }

    private static PreprocessingSettings active() { return settings("current", "", List.of(2025, 2026)); }
    private static PreprocessingSettings archive() { return settings("mmm-2022-2024", "_20250915", List.of(2022, 2023, 2024)); }

    private static void source(PreprocessingPublisherTest.Store store, PreprocessingSettings settings, boolean metadata) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int year : settings.years()) csv.append("A,").append(year).append(",100,compteur permanent,Z\n");
        for (var input : settings.source().inputs()) {
            store.objects.put(input.objectName(), (input.contentType().equals("text/csv") ? csv.toString() : GEOMETRY)
                    .getBytes(StandardCharsets.UTF_8));
            store.metadata.put(input.objectName(), metadata ? Map.of("datasource-id", "MMM", "source-url", input.url(),
                    "downloaded-at", "2026-01-01T00:00:00Z") : Map.of());
        }
    }

    @Test
    public void archiveBootstrapsWithoutCurrentInputsOrMetadataAndRetryDoesNotRepublish() throws Exception {
        var store = new PreprocessingPublisherTest.Store();
        source(store, archive(), false);
        byte[] csv = store.objects.get("MMM_Counts_20250915.csv").clone();
        var publisher = new PreprocessingPublisher(store);
        DataManifest first = publisher.publishArchive(archive(), active());
        assertEquals("current", first.activeDatasetId());
        assertEquals(Set.of("current", "mmm-2022-2024"), first.datasets().keySet());
        assertEquals(Set.of(2022, 2023, 2024), first.years().keySet());
        assertEquals("mmm-2022-2024", first.years().get(2022).datasetId());
        assertEquals(Set.of("MMM_Counts_20250915.csv", "MMM_Counts_20250915.json"),
                first.datasets().get("mmm-2022-2024").inputs().keySet());
        assertEquals(3, new ManifestComptageReader(store).read("MMM").size());
        assertArrayEquals(csv, store.objects.get("MMM_Counts_20250915.csv"));
        assertTrue(store.metadata.get("MMM_Counts_20250915.csv").isEmpty());
        store.writes.clear();
        assertEquals(first, publisher.publishArchive(archive(), active()));
        assertTrue(store.publications().isEmpty());
    }

    @Test
    public void invocationImportsArchiveAndFollowingEmptyRequestProcessesCurrentAndRetainsHistory() throws Exception {
        var store = new PreprocessingPublisherTest.Store();
        source(store, active(), true);
        source(store, archive(), false);
        var function = new PreprocessingFunction(active(), store);
        assertEquals("OK", function.handleRequest(""));
        var before = JSON.readValue(store.objects.get("MMM_Manifest.json"), DataManifest.class);
        String body = JSON.writeValueAsString(new PreprocessingFunction.ArchiveRequest(archive()));
        assertEquals("OK", function.handleRequest(body));
        var imported = JSON.readValue(store.objects.get("MMM_Manifest.json"), DataManifest.class);
        assertEquals("current", imported.activeDatasetId());
        assertEquals(before.years().get(2025), imported.years().get(2025));
        assertEquals(before.datasets().get("current"), imported.datasets().get("current"));
        assertEquals("OK", function.handleRequest(""));
        var after = JSON.readValue(store.objects.get("MMM_Manifest.json"), DataManifest.class);
        assertEquals(imported.years(), after.years());
    }

    @Test
    public void archiveModeRejectsConflictingIdentityAndNormalModeStillRequiresDownloadMetadata() throws Exception {
        for (String field : List.of("datasource-id", "source-url")) {
            var store = new PreprocessingPublisherTest.Store();
            source(store, archive(), false);
            store.metadata.put("MMM_Counts_20250915.csv", Map.of(field, "wrong"));
            expectFailure(store, "configured source");
        }
        var store = new PreprocessingPublisherTest.Store();
        source(store, active(), false);
        try {
            new PreprocessingPublisher(store).publish(active());
            fail("Normal processing requires provenance");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("configured source"));
        }
    }

    @Test
    public void changingLegacyArchiveBytesWithoutMetadataChangePreventsPublication() throws Exception {
        var store = new PreprocessingPublisherTest.Store() {
            @Override public InputStream getResource(String name) throws IOException {
                if (name.startsWith("data/")) {
                    String csv = new String(objects.get("MMM_Counts_20250915.csv"), StandardCharsets.UTF_8);
                    objects.put("MMM_Counts_20250915.csv", csv.replace(",100,", ",101,").getBytes(StandardCharsets.UTF_8));
                }
                return super.getResource(name);
            }
        };
        source(store, archive(), false);
        expectFailure(store, "content changed");
    }

    @Test
    public void invalidGeometryOrMissingYearsLeavesPreviousManifestUntouched() throws Exception {
        for (boolean missingYear : List.of(true, false)) {
            var store = new PreprocessingPublisherTest.Store();
            source(store, active(), true);
            new PreprocessingPublisher(store).publish(active());
            byte[] before = store.objects.get("MMM_Manifest.json").clone();
            source(store, archive(), false);
            String name = missingYear ? "MMM_Counts_20250915.csv" : "MMM_Counts_20250915.json";
            String content = new String(store.objects.get(name), StandardCharsets.UTF_8);
            store.objects.put(name, (missingYear ? content.replace("A,2023,100,compteur permanent,Z\n", "")
                    : content.replace("\"A\"", "\"missing\"")).getBytes(StandardCharsets.UTF_8));
            try {
                new PreprocessingPublisher(store).publishArchive(archive(), active());
                fail("Invalid archive should fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(missingYear ? "no records" : "geometry"));
            }
            assertArrayEquals(before, store.objects.get("MMM_Manifest.json"));
            assertFalse(store.objects.containsKey("MMM_Preprocessing.lock"));
        }
    }

    @Test
    public void requestsCannotSelectAnotherDatasourceActiveDatasetOrActiveObjects() throws Exception {
        var store = new PreprocessingPublisherTest.Store();
        var function = new PreprocessingFunction(active(), store);
        var wrongSource = new PreprocessingSettings(new DataSourceSettings("DPT30", "old", List.of(
                new DataSourceSettings.Input("https://example.invalid/counts.csv", "old.csv", "text/csv"))),
                "https://example.invalid/dataset", List.of(2022));
        for (var invalid : List.of(wrongSource, active(), settings("old", "", List.of(2022)))) {
            try {
                function.handleRequest(JSON.writeValueAsString(new PreprocessingFunction.ArchiveRequest(invalid)));
                fail("Invalid archive request");
            } catch (IllegalArgumentException expected) {
                assertTrue(store.objects.isEmpty());
            }
        }
        for (String invalid : List.of("{}", "null", "{\"archive\":null}", "{\"unexpected\":true}",
                JSON.writeValueAsString(new PreprocessingFunction.ArchiveRequest(archive())) + " {}")) {
            try {
                function.handleRequest(invalid);
                fail("Invalid body must not fall back to current processing");
            } catch (IllegalArgumentException | java.io.UncheckedIOException expected) {
                assertTrue(store.objects.isEmpty());
                assertTrue(store.writes.isEmpty());
            }
        }
    }

    @Test
    public void repeatedGeometryAcrossYearsAndChannelsIsAllowedOnlyWhenIdentical() throws Exception {
        var store = new PreprocessingPublisherTest.Store();
        source(store, archive(), false);
        String name = "MMM_Counts_20250915.json";
        var collection = JSON.readTree(GEOMETRY);
        var features = (com.fasterxml.jackson.databind.node.ArrayNode) collection.get("features");
        features.add(features.get(0).deepCopy());
        store.objects.put(name, JSON.writeValueAsBytes(collection));
        var publisher = new PreprocessingPublisher(store);
        publisher.publishArchive(archive(), active());
        byte[] before = store.objects.get("MMM_Manifest.json").clone();
        ((com.fasterxml.jackson.databind.node.ArrayNode) features.get(1).get("geometry").get("coordinates")).set(0,
                JSON.getNodeFactory().numberNode(4.0));
        store.objects.put(name, JSON.writeValueAsBytes(collection));
        try {
            publisher.publishArchive(archive(), active());
            fail("Conflicting geometry must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Conflicting MMM geometry"));
        }
        assertArrayEquals(before, store.objects.get("MMM_Manifest.json"));
    }

    private static void expectFailure(PreprocessingPublisherTest.Store store, String message) throws Exception {
        try {
            new PreprocessingPublisher(store).publishArchive(archive(), active());
            fail("Expected archive validation failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
        assertFalse(store.objects.containsKey("MMM_Manifest.json"));
        assertFalse(store.objects.containsKey("MMM_Preprocessing.lock"));
    }
}
