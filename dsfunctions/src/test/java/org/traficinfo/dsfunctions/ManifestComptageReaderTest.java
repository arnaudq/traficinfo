package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import org.geojson.Point;
import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.traficinfo.parsers.storage.ComptageJson;
import org.traficinfo.parsers.storage.DataManifest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class ManifestComptageReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void readsOneManifestAndExactReferencesIncludingInactiveDatasetYears() throws Exception {
        Fixture fixture = new Fixture(GeoJsonParser.DataSource.M3MV2, false);
        fixture.objects.put("data/unused/MMM_Comptage_2021.json", "invalid".getBytes(StandardCharsets.UTF_8));
        byte[] snapshot = fixture.objects.get(fixture.manifestName());
        fixture.afterManifestRead = () -> fixture.objects.put(fixture.manifestName(), "invalid".getBytes(StandardCharsets.UTF_8));

        List<? extends Comptage> records = fixture.read();

        assertEquals(List.of(2022, 2024), records.stream().map(Comptage::getAnnee).toList());
        assertEquals(List.of(fixture.manifestName(), fixture.yearName(2022), fixture.yearName(2024)), fixture.reads);
        assertEquals(3, fixture.closedStreams);
        assertEquals(new Point(3.8, 43.6), records.get(0).getGeometry());
        assertEquals(500, records.get(0).getMoyenneJourAnnuelle());
        assertEquals(0, fixture.writes);
        assertFalse(java.util.Arrays.equals(snapshot, fixture.objects.get(fixture.manifestName())));
    }

    @Test
    public void readsPlainAndGzipPublicationsForEveryDatasource() throws Exception {
        for (GeoJsonParser.DataSource source : GeoJsonParser.DataSource.values()) {
            for (boolean gzip : new boolean[]{false, true}) {
                Fixture fixture = new Fixture(source, gzip);
                List<? extends Comptage> records = fixture.read();
                assertEquals(2, records.size());
                assertEquals(source.getDsComptageClass(), records.get(0).getClass());
                assertEquals(List.of(2022, 2024), records.stream().map(Comptage::getAnnee).toList());
                assertEquals(3, fixture.closedStreams);
            }
        }
    }

    @Test
    public void emptyPublicationReturnsNoRecords() throws Exception {
        Fixture fixture = new Fixture(GeoJsonParser.DataSource.M3MV2, false);
        fixture.edit(root -> root.putObject("years"));
        assertTrue(fixture.read().isEmpty());
        assertEquals(List.of(fixture.manifestName()), fixture.reads);
        assertEquals(1, fixture.closedStreams);
    }

    @Test
    public void missingManifestOrYearFailsAndClosesOpenedStreams() throws Exception {
        for (String missing : List.of("MMM_Manifest.json", "data/old/MMM_Comptage_2022.json",
                "data/new/MMM_Comptage_2024.json")) {
            Fixture fixture = new Fixture(GeoJsonParser.DataSource.M3MV2, false);
            fixture.objects.remove(missing);
            expectFailure(fixture);
            assertEquals(fixture.reads.size() - 1, fixture.closedStreams);
        }
    }

    @Test
    public void rejectsInvalidManifestBeforeReadingYears() throws Exception {
        List<Consumer<ObjectNode>> changes = List.of(
                root -> root.remove("manifestVersion"),
                root -> root.put("manifestVersion", 2),
                root -> root.put("dataSourceId", "DPT30"),
                root -> root.put("revision", ""),
                root -> root.put("publishedAt", "invalidZ"),
                root -> root.put("activeDatasetId", "missing"),
                root -> root.putNull("activeDatasetId"),
                root -> root.put("unknownField", true),
                root -> ((ObjectNode) root.path("datasets").path("current")).putArray("years"),
                root -> ((ObjectNode) root.path("datasets").path("current")).putObject("inputs"),
                root -> year(root).put("datasetId", "missing"),
                root -> year(root).putNull("datasetId"),
                root -> year(root).put("datasetId", "current"),
                root -> year(root).put("recordCount", 0),
                root -> year(root).put("sha256", "bad"),
                root -> year(root).put("object", "data/old/DPT30_Comptage_2022.json"),
                root -> year(root).put("object", "data/old/MMM_Comptage_2024.json"),
                root -> year(root).put("parserVersion", ""));
        for (Consumer<ObjectNode> change : changes) {
            Fixture fixture = new Fixture(GeoJsonParser.DataSource.M3MV2, false);
            fixture.edit(change);
            expectFailure(fixture);
            assertEquals(List.of(fixture.manifestName()), fixture.reads);
            assertEquals(1, fixture.closedStreams);
        }
    }

    @Test
    public void rejectsMalformedAndNullManifests() throws Exception {
        for (String payload : List.of("invalid", "null", "{}")) {
            Fixture fixture = new Fixture(GeoJsonParser.DataSource.M3MV2, false);
            fixture.objects.put(fixture.manifestName(), payload.getBytes(StandardCharsets.UTF_8));
            expectFailure(fixture);
            assertEquals(1, fixture.closedStreams);
        }
    }

    @Test
    public void rejectsChecksumAndRecordCountMismatches() throws Exception {
        List<Consumer<ObjectNode>> changes = List.of(
                root -> year(root).put("sha256", "0".repeat(64)),
                root -> year(root).put("recordCount", 2));
        for (Consumer<ObjectNode> change : changes) {
            Fixture fixture = new Fixture(GeoJsonParser.DataSource.M3MV2, false);
            fixture.edit(change);
            expectFailure(fixture);
            assertEquals(2, fixture.closedStreams);
        }
    }

    @Test
    public void rejectsInvalidYearlyRecordsEvenWithMatchingChecksums() throws Exception {
        for (String payload : List.of("invalid", "null", "[null]", "[]", "[{\"annee\":2023}]")) {
            Fixture fixture = new Fixture(GeoJsonParser.DataSource.M3MV2, false);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            fixture.objects.put(fixture.yearName(2022), bytes);
            fixture.edit(root -> year(root).put("sha256", checksum(bytes)));
            expectFailure(fixture);
            assertEquals(2, fixture.closedStreams);
        }
    }

    @Test
    public void checksumIncludesTrailingWhitespaceAfterTheArray() throws Exception {
        Fixture fixture = new Fixture(GeoJsonParser.DataSource.M3MV2, false);
        String name = fixture.yearName(2022);
        byte[] bytes = (new String(fixture.objects.get(name), StandardCharsets.UTF_8) + "\n ").getBytes(StandardCharsets.UTF_8);
        fixture.objects.put(name, bytes);
        expectFailure(fixture);
        fixture.edit(root -> year(root).put("sha256", checksum(bytes)));
        assertEquals(2, fixture.read().size());
    }

    private static ObjectNode year(ObjectNode root) {
        return (ObjectNode) root.path("years").path("2022");
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void expectFailure(Fixture fixture) throws Exception {
        try {
            fixture.read();
            fail("Expected invalid publication to fail");
        } catch (IOException | BmcException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static class Fixture {
        final GeoJsonParser.DataSource source;
        final boolean gzip;
        final Map<String, byte[]> objects = new HashMap<>();
        final List<String> reads = new ArrayList<>();
        int closedStreams;
        int writes;
        Runnable afterManifestRead = () -> {};

        Fixture(GeoJsonParser.DataSource source, boolean gzip) throws Exception {
            this.source = source;
            this.gzip = gzip;
            Map<Integer, DataManifest.YearData> years = new HashMap<>();
            for (int year : List.of(2022, 2024)) {
                Comptage record = source.getDsComptageClass().getConstructor().newInstance();
                record.setAnnee(year);
                record.setCodeCentrale("sample");
                record.setMoyenneJourAnnuelle(500);
                if (record instanceof M3MComptage mmm) mmm.setGeometry(new Point(3.8, 43.6));
                byte[] bytes = ("[" + new String(new ComptageJson().write(record), StandardCharsets.UTF_8) + "]")
                        .getBytes(StandardCharsets.UTF_8);
                objects.put(yearName(year), bytes);
                years.put(year, new DataManifest.YearData(yearName(year), checksum(bytes), 1,
                        year == 2022 ? "historic" : "current", "1"));
            }
            var inputs = Map.of("counts.csv", new DataManifest.Input("https://example.invalid/counts", "CSV", List.of("year")));
            var datasets = Map.of(
                    "historic", new DataManifest.Dataset("https://example.invalid/historic", List.of(2022), inputs),
                    "current", new DataManifest.Dataset("https://example.invalid/current", List.of(2024), inputs));
            objects.put(manifestName(), JSON.writeValueAsBytes(new DataManifest(1, source.getId(), "new",
                    "2026-10-29T00:00:00Z", "current", datasets, years)));
        }

        String manifestName() { return source.getId() + "_Manifest.json"; }

        String yearName(int year) {
            return "data/" + (year == 2022 ? "old" : "new") + "/" + source.getId() + "_Comptage_" + year + ".json";
        }

        void edit(Consumer<ObjectNode> change) throws IOException {
            ObjectNode root = (ObjectNode) JSON.readTree(objects.get(manifestName()));
            change.accept(root);
            objects.put(manifestName(), JSON.writeValueAsBytes(root));
        }

        List<? extends Comptage> read() throws IOException {
            ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(ObjectStorage.class.getClassLoader(),
                    new Class<?>[]{ObjectStorage.class}, (proxy, method, args) -> {
                        if (method.getName().equals("putObject")) writes++;
                        assertEquals("Only exact Object reads are allowed", "getObject", method.getName());
                        String name = ((GetObjectRequest) args[0]).getObjectName();
                        reads.add(name);
                        byte[] bytes = objects.get(name);
                        if (bytes == null) throw new BmcException(404, "ObjectNotFound", "Missing Object: " + name, "test");
                        if (name.equals(manifestName())) afterManifestRead.run();
                        if (gzip) {
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            try (GZIPOutputStream compressed = new GZIPOutputStream(output)) { compressed.write(bytes); }
                            bytes = output.toByteArray();
                        }
                        ByteArrayInputStream input = new ByteArrayInputStream(bytes) {
                            boolean closed;
                            @Override public void close() throws IOException {
                                if (!closed) closedStreams++;
                                closed = true;
                                super.close();
                            }
                        };
                        return GetObjectResponse.builder().inputStream(input).contentEncoding(gzip ? "gzip" : null).build();
                    });
            return new ManifestComptageReader(new ObjectStoreClient(storage, "test", "test")).read(source.getId());
        }
    }
}
