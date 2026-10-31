package org.traficinfo.dsfunctions.nim;

import org.traficinfo.dsfunctions.GeoJsonDataSource;
import org.traficinfo.dsfunctions.ManifestTestPublication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import org.junit.Test;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;
import org.traficinfo.dsfunctions.OpenStreetMapClient;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;
import static org.traficinfo.dsfunctions.FunctionFailureAssertions.assertFunctionFailure;

public class GeoJsonOutFunctionTest {

    private static final int YEAR = Year.now().getValue();
    private static org.traficinfo.parsers.nim.NIMComptage count(String id, int year,
                                                                  Integer average, Double latitude, Double longitude) {
        var count = new org.traficinfo.parsers.nim.NIMComptage();
        count.setCodeCentrale("NIM_" + id);
        count.setCameraId(id.split("_")[0]);
        count.setAnnee(year);
        count.setDailyAverage(average);
        count.setLatitude(latitude);
        count.setLongitude(longitude);
        count.setDisplayName("D11 - Béziers");
        count.setCityName("Béziers");
        count.setObservedDays(2);
        count.setCompleteDays(average == null ? 0 : 1);
        return count;
    }

    @Test
    public void buildsPointsFromPlainAndGzipManifestPublicationsAndUploadsGzipGeoJson() throws Exception {
        for (boolean gzip : new boolean[]{false, true}) {
            StorageStub stub = new StorageStub(gzip);
            assertEquals("OK", stub.function().handleRequest(""));
            assertEquals(List.of("NIM_Manifest.json",
                    ManifestTestPublication.yearObjectName(GeoJsonDataSource.NIM, YEAR - 1),
                    ManifestTestPublication.yearObjectName(GeoJsonDataSource.NIM, YEAR)), stub.downloads);
            assertEquals(2, stub.uploads);
            JsonNode result = new ObjectMapper().readTree(stub.output);
            assertEquals("FeatureCollection", result.get("type").asText());
            assertEquals(1, result.get("features").size());
            JsonNode feature = result.get("features").get(0);
            assertEquals("Point", feature.get("geometry").get("type").asText());
            assertEquals(3.3, feature.get("geometry").get("coordinates").get(0).asDouble(), 0.0);
            assertEquals(43.3, feature.get("geometry").get("coordinates").get(1).asDouble(), 0.0);
            JsonNode properties = feature.get("properties");
            assertEquals("NIM_52_S01", properties.get("code_centrale").asText());
            assertEquals("NIM", properties.get("data_source").asText());
            assertEquals(YEAR, properties.get("annee").asInt());
            assertEquals(500, properties.get("moyenne_jour_annuelle").asInt());
            assertEquals("D11 - Béziers", properties.get("description").asText());
            assertEquals("500 ⇄", properties.get("name").asText());
            assertEquals("500 (" + YEAR + ") 400 (" + (YEAR - 1) + ") ",
                    properties.get("moyenne_annuelle_history").asText());
            assertEquals("Green", properties.get("_umap_options").get("color").asText());
            assertEquals("80", properties.get("maximum_speed").asText());
            assertEquals(1, stub.mappingUploads);
            stub.assertStreamsClosed();
        }
    }

    @Test
    public void skipsCountersWithoutCoordinatesOrTraffic() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.counts.add(count("53_S01", YEAR, 500, null, null));
        stub.counts.add(count("54_S01", YEAR, null, 43.0, 3.0));
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(1, new ObjectMapper().readTree(stub.output).get("features").size());
        stub.assertStreamsClosed();
    }

    @Test
    public void preservesSeparateSitesForTheSameCamera() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.counts.add(count("52_S02", YEAR, 600, 44.0, 4.0));
        assertEquals("OK", stub.function().handleRequest(""));
        JsonNode features = new ObjectMapper().readTree(stub.output).get("features");
        assertEquals(2, features.size());
        assertEquals("NIM_52_S01", features.get(0).get("properties").get("code_centrale").asText());
        assertEquals("NIM_52_S02", features.get(1).get("properties").get("code_centrale").asText());
        assertEquals(600, features.get(1).get("properties").get("moyenne_jour_annuelle").asInt());
        assertEquals(4.0, features.get(1).get("geometry").get("coordinates").get(0).asDouble(), 0);
        stub.assertStreamsClosed();
    }

    @Test
    public void throwsWithoutUploadWhenDownloadFails() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.failDownload = true;
        assertFunctionFailure(() -> stub.function().handleRequest(""));
        assertEquals(0, stub.uploads);
    }

    @Test
    public void throwsWithoutUploadWhenManifestIsInvalid() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.invalidManifest = true;
        assertFunctionFailure(() -> stub.function().handleRequest(""));
        assertEquals(0, stub.uploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void throwsWhenUploadFails() throws Exception {
        StorageStub stub = new StorageStub(true);
        stub.failUpload = true;
        assertFunctionFailure(() -> stub.function().handleRequest(""));
        assertEquals(1, stub.uploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void savesUnmappedCounterOnceWithLatestCoordinates() throws Exception {
        StorageStub stub = new StorageStub(false);
        // Newest row deliberately comes before the old row.
        stub.counts.add(count("53_S01", YEAR, 500, 44.01, 4.01));
        stub.counts.add(count("53_S01", YEAR - 1, 400, 44.0, 4.0));
        assertEquals("OK", stub.function().handleRequest(""));
        JsonNode saved = new ObjectMapper().readTree(stub.mappings);
        assertEquals(2, saved.size());
        JsonNode missing = saved.get("NIM_53_S01");
        assertEquals("MISSING", missing.get("status").asText());
        assertTrue(missing.get("osmWayId").isNull());
        assertEquals(44.01, missing.get("latitude").asDouble(), 0);
        assertEquals(4.01, missing.get("longitude").asDouble(), 0);
    }

    @Test
    public void reportsMappingWriteFailureAfterPublishingGeoJson() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.failMappingUpload = true;
        assertFunctionFailure(() -> stub.function().handleRequest(""));
        assertEquals(2, stub.uploads);
        assertEquals(1, stub.mappingUploads);
    }

    @Test
    public void invalidPublicationsPreservePreviousOutputAndDoNotRefreshCaches() throws Exception {
        for (String failure : List.of("manifest", "missingYear", "corruptYear")) {
            StorageStub stub = new StorageStub(false);
            stub.invalidManifest = failure.equals("manifest");
            stub.missingYear = failure.equals("missingYear");
            stub.corruptYear = failure.equals("corruptYear");
            byte[] previousOutput = "previous output".getBytes(StandardCharsets.UTF_8);
            stub.output = previousOutput;
            assertFunctionFailure(() -> stub.function().handleRequest(""));
            assertSame(previousOutput, stub.output);
            assertEquals(0, stub.uploads);
            assertEquals(0, stub.mappingUploads);
            stub.assertStreamsClosed();
        }
    }

    @Test
    public void emptyManifestPublishesAnEmptyFeatureCollection() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.counts.clear();
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(List.of("NIM_Manifest.json"), stub.downloads);
        assertEquals(0, new ObjectMapper().readTree(stub.output).get("features").size());
        stub.assertStreamsClosed();
    }

    @Test
    public void deletesTemporaryOutputOnSuccessAndUploadFailure() throws Exception {
        for (boolean failUpload : new boolean[]{false, true}) {
            var before = temporaryOutputs();
            StorageStub stub = new StorageStub(false);
            stub.failUpload = failUpload;
            if (failUpload) {
                assertFunctionFailure(() -> stub.function().handleRequest(""));
            } else {
                assertEquals("OK", stub.function().handleRequest(""));
            }
            assertEquals(before, temporaryOutputs());
        }
    }

    private static java.util.Set<java.nio.file.Path> temporaryOutputs() throws IOException {
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("NIM-geojson-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static class StorageStub {
        final boolean gzip;
        final List<String> downloads = new ArrayList<>();
        final List<TrackedStream> streams = new ArrayList<>();
        final List<org.traficinfo.parsers.nim.NIMComptage> counts = new ArrayList<>(List.of(
                count("52_S01", YEAR - 1, 400, 43.2, 3.2),
                count("52_S01", YEAR, 500, 43.3, 3.3)));
        boolean failDownload;
        boolean invalidManifest;
        boolean missingYear;
        boolean corruptYear;
        boolean failUpload;
        boolean failMappingUpload;
        int mappingUploads;
        byte[] mappings;
        int uploads;
        byte[] output;
        byte[] bicycleOutput;

        StorageStub(boolean gzip) {
            this.gzip = gzip;
        }

        GeoJsonOutFunction function() throws IOException {
            Map<String, byte[]> objects = ManifestTestPublication.fromRecords(GeoJsonDataSource.NIM, counts);
            if (invalidManifest) objects.put("NIM_Manifest.json", "invalid JSON".getBytes(StandardCharsets.UTF_8));
            String latestYear = ManifestTestPublication.yearObjectName(GeoJsonDataSource.NIM, YEAR);
            if (missingYear) objects.remove(latestYear);
            if (corruptYear) objects.put(latestYear, "[]".getBytes(StandardCharsets.UTF_8));
            ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                    ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                    (proxy, method, args) -> {
                        if ("getObject".equals(method.getName())) {
                            GetObjectRequest request = (GetObjectRequest) args[0];
                            assertEquals("namespace", request.getNamespaceName());
                            assertEquals("dsnim", request.getBucketName());
                            downloads.add(request.getObjectName());
                            if (failDownload) {
                                throw new IllegalStateException("Download failed");
                            }
                            byte[] bytes = objects.get(request.getObjectName());
                            if (bytes == null) throw new IllegalStateException("Missing Object: " + request.getObjectName());
                            if (gzip) {
                                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                                try (GZIPOutputStream stream = new GZIPOutputStream(compressed)) {
                                    stream.write(bytes);
                                }
                                bytes = compressed.toByteArray();
                            }
                            TrackedStream stream = new TrackedStream(bytes);
                            streams.add(stream);
                            return GetObjectResponse.builder().inputStream(stream)
                                    .contentEncoding(gzip ? "gzip" : null).build();
                        }
                        assertEquals("putObject", method.getName());
                        PutObjectRequest request = (PutObjectRequest) args[0];
                        assertEquals("namespace", request.getNamespaceName());
                        assertEquals("traficinfo", request.getBucketName());
                        assertTrue(List.of(GeoJsonOutFunction.GEOJSON_OBJECT_NAME,
                                GeoJsonOutFunction.GEOJSON_OBJECT_NAME.replace(".geojson", "_BICYCLES.geojson"))
                                .contains(request.getObjectName()));
                        assertEquals("application/geo+json", request.getContentType());
                        assertEquals("gzip", request.getContentEncoding());
                        uploads++;
                        if (failUpload) {
                            throw new IllegalStateException("Upload failed");
                        }
                        try (GZIPInputStream decoded = new GZIPInputStream(request.getPutObjectBody())) {
                            byte[] bytes = decoded.readAllBytes();
                            if (request.getObjectName().endsWith("_BICYCLES.geojson")) {
                                bicycleOutput = bytes;
                            } else {
                                output = bytes;
                            }
                        }
                        return PutObjectResponse.builder().build();
                    });
            ObjectStoreClient cacheStore = new ObjectStoreClient(null, "namespace", "osminfo") {
                @Override
                public InputStream getResource(String name) {
                    if (name.equals("mappings.json") && mappings != null) {
                        return new ByteArrayInputStream(mappings);
                    }
                    String json = name.equals("tags.json")
                            ? "{\"123\":{\"lastUpdated\":\"" + Instant.now() + "\",\"tags\":{\"maxspeed\":\"80\"}}}"
                            : "{\"NIM_52_S01\":{\"osmWayId\":123}}";
                    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
                }
                @Override
                public void putResource(String name, String type, org.traficinfo.dsfunctions.OOSResourceMetadata metadata,
                                        InputStream body) throws IOException {
                    assertNotNull("Cache must be saved after GeoJSON publication", output);
                    assertEquals("mappings.json", name);
                    mappingUploads++;
                    if (failMappingUpload) throw new IOException("Mapping upload failed");
                    mappings = body.readAllBytes();
                }
            };
            OSMInfoProviderImpl provider = new OSMInfoProviderImpl(cacheStore, new OpenStreetMapClient() {
                @Override public Map<String, String> getWayTags(long id) {
                    throw new AssertionError("Fresh tags should not need requests");
                }
            }, "tags.json", "mappings.json");
            return new GeoJsonOutFunction(new ObjectStoreClient(storage, "namespace", "dsnim"),
                    new ObjectStoreClient(storage, "namespace", "traficinfo"), provider);
        }

        void assertStreamsClosed() {
            for (TrackedStream stream : streams) {
                assertTrue("Manifest and yearly streams must be closed", stream.closed);
            }
        }
    }

    private static class TrackedStream extends ByteArrayInputStream {
        boolean closed;

        TrackedStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
