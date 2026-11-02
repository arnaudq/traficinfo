package org.traficinfo.dsfunctions.m3m;

import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.GeoJsonDataSource;
import org.traficinfo.dsfunctions.ManifestTestPublication;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.geojson.GeoJsonObject;
import org.geojson.Point;
import java.io.StringReader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.model.BmcException;
import org.traficinfo.parsers.OSMWayInfo;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;
import org.traficinfo.dsfunctions.OpenStreetMapClient;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import org.junit.Test;
import org.traficinfo.dsfunctions.GeoJsonPublisher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;
import static org.traficinfo.dsfunctions.FunctionFailureAssertions.assertFunctionFailure;

public class GeoJsonPublisherTest {
    private static final String CSV = "code_centrale,name,nom_commune,annee,type_compteur,code_canal,"
            + "mja_jo,hp_johvs_matin,hp_johvs_soir,v85ma_tcj,vtma_tcj\n"
            + "100457,Rue de l'École,Montpellier," + Year.now().getValue()
            + ",compteur feux,1,577,72,43,30,28\n";

    @Test
    public void publishesBicyclesSeparatelyAndClearsRemovedCounters() throws Exception {
        StorageStub stub = new StorageStub(true);
        stub.csv += "146966,Piste,Montpellier," + Year.now().getValue()
                + ",compteur permanent vélo,Z,500,,,,\n";
        assertEquals("OK", stub.function().publish());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode vehicles = mapper.readTree(stub.output).path("features");
        JsonNode bicycles = mapper.readTree(stub.bicycleOutput).path("features");
        assertEquals(1, vehicles.size());
        assertEquals("VEHICULES", vehicles.get(0).path("properties").path("type").asText());
        assertEquals(1, bicycles.size());
        assertEquals("146966", bicycles.get(0).path("properties").path("code_centrale").asText());
        assertFalse(bicycles.get(0).path("properties").has("shape-icon"));
        stub.csv = CSV;
        assertEquals("OK", stub.function().publish());
        assertEquals(0, mapper.readTree(stub.bicycleOutput).path("features").size());
        stub.assertStreamsClosed();
    }

    @Test
    public void reportsBicycleUploadFailureBeforeRefreshingCache() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.failBicycleUpload = true;
        assertFunctionFailure(() -> stub.function().publish());
        assertEquals(2, stub.uploads);
        assertNotNull(stub.output);
        assertNull(stub.bicycleOutput);
        assertEquals(0, stub.cacheUploads);
        stub.assertStreamsClosed();
    }
    @Test
    public void rendersPlainAndGzipManifestPublicationsWithoutRawCompanionAndUploadsToOutputBucket() throws Exception {
        for (boolean gzip : new boolean[]{false, true}) {
            StorageStub stub = new StorageStub(gzip);
            assertEquals("OK", stub.function().publish());
            assertEquals(List.of("MMM_Manifest.json",
                    ManifestTestPublication.yearObjectName(GeoJsonDataSource.MMM, Year.now().getValue()),
                    "MMM_OSMInfo.json"), stub.downloads);
            assertEquals(2, stub.uploads);
            JsonNode result = new ObjectMapper().readTree(stub.output);
            assertEquals("FeatureCollection", result.get("type").asText());
            assertEquals(1, result.get("features").size());
            JsonNode feature = result.get("features").get(0);
            assertEquals(3.88, feature.get("geometry").get("coordinates").get(0).asDouble(), 0.0001);
            assertEquals("100457", feature.get("properties").get("code_centrale").asText());
            assertEquals(577, feature.get("properties").get("moyenne_jour_annuelle").asInt());
            assertEquals("28", feature.get("properties").get("mean_speed").asText());
            assertEquals("30", feature.get("properties").get("v85_speed").asText());
            assertFalse(feature.get("properties").has("vitesse_pratiquee"));
            assertEquals("Rue de l'École - Montpellier", feature.get("properties").get("description").asText());
            stub.assertStreamsClosed();
        }
    }

    @Test
    public void throwsWithoutUploadWhenDownloadFails() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.failDownload = true;
        assertFunctionFailure(() -> stub.function().publish());
        assertEquals(0, stub.uploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void throwsWithoutUploadWhenManifestIsInvalid() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.invalidManifest = true;
        assertFunctionFailure(() -> stub.function().publish());
        assertEquals(0, stub.uploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void throwsWhenUploadFails() throws Exception {
        StorageStub stub = new StorageStub(true);
        stub.failUpload = true;
        assertFunctionFailure(() -> stub.function().publish());
        assertEquals(1, stub.uploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void refreshesMissingCacheAfterOutputAndUsesItOnNextRun() throws Exception {
        StorageStub stub = new StorageStub(true);
        stub.cacheObject = "custom-cache.json";
        stub.csv = csvWithWays(1, 1);
        assertEquals("OK", stub.function().publish());
        assertEquals(Arrays.asList(1L), stub.osmRequests);
        assertNull(maximumSpeed(stub.output));
        assertEquals(1, stub.cacheUploads);
        JsonNode cache = new ObjectMapper().readTree(stub.cache);
        assertEquals(stub.now.toString(), cache.get("1").get("lastUpdated").asText());
        assertEquals("residential", cache.get("1").get("tags").get("highway").asText());
        assertFalse(cache.get("1").has("maximumSpeed"));
        stub.assertStreamsClosed();

        assertEquals("OK", stub.function().publish());
        assertEquals("50", maximumSpeed(stub.output));
        assertEquals(1, stub.osmRequests.size());
        assertEquals(1, stub.cacheUploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void refreshesStaleCacheButKeepsUnrelatedEntries() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.csv = csvWithWays(1);
        Map<Long, OSMWayInfo> cache = new LinkedHashMap<>();
        cache.put(1L, new OSMWayInfo(stub.now.atZone(ZoneOffset.UTC).minusMonths(4).toInstant(),
                Map.of("maxspeed", "30", "name", "Old road")));
        cache.put(2L, new OSMWayInfo(stub.now, Map.of("maxspeed", "70")));
        stub.cache = new ObjectMapper().writeValueAsBytes(cache);
        assertEquals("OK", stub.function().publish());
        assertEquals("30", maximumSpeed(stub.output));
        assertEquals(Arrays.asList(1L), stub.osmRequests);
        JsonNode updated = new ObjectMapper().readTree(stub.cache);
        assertEquals("50", updated.get("1").get("tags").get("maxspeed").asText());
        assertEquals("70", updated.get("2").get("tags").get("maxspeed").asText());
    }

    @Test
    public void limitsRequestsToOneHundredAndLeavesRemainingWaysForLaterRuns() throws Exception {
        StorageStub stub = new StorageStub(false);
        long[] ids = LongStream.rangeClosed(1, 102).toArray();
        long[] idsWithDuplicate = Arrays.copyOf(ids, ids.length + 1);
        idsWithDuplicate[ids.length] = 1;
        stub.csv = csvWithWays(idsWithDuplicate);
        assertEquals("OK", stub.function().publish());
        assertEquals(100, stub.osmRequests.size());
        assertEquals(100, new ObjectMapper().readTree(stub.cache).size());
        assertEquals("OK", stub.function().publish());
        assertEquals(LongStream.of(ids).boxed()
                .collect(Collectors.toList()), stub.osmRequests);
    }

    @Test
    public void failedRequestsCountTowardLimitAndPreserveOldCache() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.csv = csvWithWays(LongStream.rangeClosed(1, 101).toArray());
        stub.cache = new ObjectMapper().writeValueAsBytes(Map.of(1L,
                new OSMWayInfo(stub.now.atZone(ZoneOffset.UTC).minusMonths(4).toInstant(),
                        Map.of("maxspeed", "30"))));
        byte[] original = stub.cache.clone();
        stub.failOsm = true;
        assertEquals("OK", stub.function().publish());
        assertEquals(100, stub.osmRequests.size());
        assertEquals(0, stub.cacheUploads);
        assertArrayEquals(original, stub.cache);
    }

    @Test
    public void refusesToOverwriteUnreadableCache() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.failCacheDownload = true;
        assertFunctionFailure(() -> stub.function().publish());
        assertEquals(0, stub.uploads);
        assertEquals(0, stub.cacheUploads);
        stub.failCacheDownload = false;
        stub.cache = "invalid JSON".getBytes(StandardCharsets.UTF_8);
        assertFunctionFailure(() -> stub.function().publish());
        assertEquals(0, stub.cacheUploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void reportsCacheUploadFailure() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.csv = csvWithWays(1);
        stub.failCacheUpload = true;
        assertFunctionFailure(() -> stub.function().publish());
        assertEquals(2, stub.uploads);
        assertEquals(1, stub.cacheUploads);
    }

    @Test
    public void preservesStoredPointLineAndPolygonGeometry() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String geometry : List.of(
                "{\"type\":\"Point\",\"coordinates\":[3.88,43.6]}",
                "{\"type\":\"LineString\",\"coordinates\":[[3.88,43.6],[3.89,43.61]]}",
                "{\"type\":\"Polygon\",\"coordinates\":[[[3.88,43.6],[3.89,43.6],[3.89,43.61],[3.88,43.6]]]}")) {
            StorageStub stub = new StorageStub(true);
            stub.geometry = mapper.readValue(geometry, GeoJsonObject.class);
            assertEquals("OK", stub.function().publish());
            assertEquals(mapper.readTree(geometry), mapper.readTree(stub.output).path("features").get(0).path("geometry"));
            stub.assertStreamsClosed();
        }
    }

    @Test
    public void retainedYearsSupplyHistoryWhileLatestYearSuppliesGeometryAndSpeeds() throws Exception {
        StorageStub stub = new StorageStub(false);
        int year = Year.now().getValue();
        stub.csv += "100457,Rue de l'École,Montpellier," + year + ",compteur feux,2,400,20,30,50,35\n"
                + "100457,Rue de l'École,Montpellier," + (year - 1) + ",compteur feux,1,800,80,60,90,80\n";
        stub.geometryByYear = Map.of(year - 1, new Point(1, 2));
        assertEquals("OK", stub.function().publish());
        assertEquals(List.of("MMM_Manifest.json",
                ManifestTestPublication.yearObjectName(GeoJsonDataSource.MMM, year - 1),
                ManifestTestPublication.yearObjectName(GeoJsonDataSource.MMM, year),
                "MMM_OSMInfo.json"), stub.downloads);
        JsonNode feature = new ObjectMapper().readTree(stub.output).path("features").get(0);
        assertEquals(3.88, feature.path("geometry").path("coordinates").get(0).asDouble(), 0);
        assertEquals(577, feature.path("properties").path("moyenne_jour_annuelle").asInt());
        assertEquals("50", feature.path("properties").path("v85_speed").asText());
        assertEquals("35", feature.path("properties").path("mean_speed").asText());
        assertEquals("577 (" + year + ") 800 (" + (year - 1) + ") ",
                feature.path("properties").path("moyenne_annuelle_history").asText());
    }

    @Test
    public void matchesLegacyOutputApartFromCompanionOnlyProperties() throws Exception {
        String companion = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[3.88,43.6]},"
                + "\"properties\":{\"code_centrale\":\"100457\",\"gid\":\"old-id\",\"name:q\":\"old-label\"}}]}";
        java.io.StringWriter legacy = new java.io.StringWriter();
        GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2, new StringReader(CSV),
                new StringReader(companion), legacy);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expected = mapper.readTree(legacy.toString());
        var properties = (com.fasterxml.jackson.databind.node.ObjectNode) expected.path("features").get(0).path("properties");
        assertNotNull(properties.remove("gid"));
        assertNotNull(properties.remove("name:q"));
        StorageStub stub = new StorageStub(false);
        assertEquals("OK", stub.function().publish());
        assertEquals(expected, mapper.readTree(stub.output));
    }

    @Test
    public void usesCounterIdWhenStoredDisplayNameIsMissing() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.csv = CSV.replace("Rue de l'École,Montpellier", ",");
        assertEquals("OK", stub.function().publish());
        JsonNode properties = new ObjectMapper().readTree(stub.output).path("features").get(0).path("properties");
        assertEquals("100457", properties.path("description").asText());
        assertFalse(properties.has("gid"));
        assertFalse(properties.has("name:q"));
    }

    @Test
    public void invalidPublicationsPreserveOutputAndDoNotRefreshCaches() throws Exception {
        for (String failure : List.of("manifest", "missingYear", "corruptYear")) {
            StorageStub stub = new StorageStub(false);
            stub.invalidManifest = failure.equals("manifest");
            stub.missingYear = failure.equals("missingYear");
            stub.corruptYear = failure.equals("corruptYear");
            byte[] previousOutput = "previous output".getBytes(StandardCharsets.UTF_8);
            stub.output = previousOutput;
            assertFunctionFailure(() -> stub.function().publish());
            assertSame(previousOutput, stub.output);
            assertEquals(0, stub.uploads);
            assertEquals(0, stub.cacheUploads);
            assertTrue(stub.osmRequests.isEmpty());
            stub.assertStreamsClosed();
        }
    }

    private static String csvWithWays(long... ids) {
        String[] lines = CSV.split("\\n");
        StringBuilder csv = new StringBuilder(lines[0]).append(",osm_id\n");
        for (long id : ids) {
            csv.append(lines[1]).append(',').append(id).append('\n');
        }
        return csv.toString();
    }

    private static String maximumSpeed(byte[] output) throws IOException {
        return new ObjectMapper().readTree(output).get("features").get(0)
                .get("properties").path("maximum_speed").asText(null);
    }

    private static class StorageStub {
        final boolean gzip;
        final List<String> downloads = new ArrayList<>();
        final List<TrackedStream> streams = new ArrayList<>();
        GeoJsonObject geometry = new Point(3.88, 43.60);
        Map<Integer, GeoJsonObject> geometryByYear = Map.of();
        boolean invalidManifest;
        boolean missingYear;
        boolean corruptYear;
        String csv = CSV;
        String cacheObject = GeoJsonDataSource.MMM.getOsmCacheObjectName();
        byte[] cache;
        int cacheUploads;
        final Instant now = Instant.now();
        final List<Long> osmRequests = new ArrayList<>();
        boolean failOsm;
        boolean failCacheUpload;
        boolean failCacheDownload;
        boolean failDownload;
        boolean failUpload;
        boolean failBicycleUpload;
        int uploads;
        byte[] output;
        byte[] bicycleOutput;

        StorageStub(boolean gzip) {
            this.gzip = gzip;
        }

        GeoJsonPublisher function() throws IOException {
            var records = GeoJsonDataSource.MMM.getParserDataSource().getParser().parseComptages(new StringReader(csv));
            for (var record : records) {
                ((M3MComptage) record).setGeometry(geometryByYear.getOrDefault(record.getAnnee(), geometry));
            }
            Map<String, byte[]> objects = ManifestTestPublication.fromRecords(GeoJsonDataSource.MMM, records);
            if (invalidManifest) objects.put("MMM_Manifest.json", "invalid JSON".getBytes(StandardCharsets.UTF_8));
            String latestYear = ManifestTestPublication.yearObjectName(GeoJsonDataSource.MMM, Year.now().getValue());
            if (missingYear) objects.remove(latestYear);
            if (corruptYear) objects.put(latestYear, "[]".getBytes(StandardCharsets.UTF_8));
            ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                    ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                    (proxy, method, args) -> {
                        if ("getObject".equals(method.getName())) {
                            GetObjectRequest request = (GetObjectRequest) args[0];
                            assertEquals("namespace", request.getNamespaceName());
                            assertEquals(request.getObjectName().equals(cacheObject) ? "osminfo" : "input-bucket",
                                    request.getBucketName());
                            downloads.add(request.getObjectName());
                            if (failDownload && request.getObjectName().equals("MMM_Manifest.json")) {
                                throw new IllegalStateException("download failed");
                            }
                            byte[] bytes;
                            if (request.getObjectName().equals(cacheObject)) {
                                if (failCacheDownload) {
                                    throw new BmcException(403, "Forbidden", "denied", "request");
                                }
                                if (cache == null) {
                                    throw new BmcException(404, "NotFound", "missing cache", "request");
                                }
                                bytes = cache;
                            } else {
                                bytes = objects.get(request.getObjectName());
                                assertFalse("Raw CSV must not be read", request.getObjectName().endsWith(".csv"));
                                assertNotEquals("Raw companion must not be read", "MMM_MMM_Comptage.json", request.getObjectName());
                                if (bytes == null) throw new BmcException(404, "NotFound", "Missing yearly Object", "request");
                            }
                            if (gzip) {
                                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                                try (GZIPOutputStream out = new GZIPOutputStream(compressed)) {
                                    out.write(bytes);
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
                        if (request.getObjectName().equals(cacheObject)) {
                            assertEquals("osminfo", request.getBucketName());
                            assertEquals("application/json", request.getContentType());
                            assertEquals("gzip", request.getContentEncoding());
                            cacheUploads++;
                            if (failCacheUpload) {
                                throw new IllegalStateException("cache upload failed");
                            }
                            try (GZIPInputStream decoded = new GZIPInputStream(request.getPutObjectBody())) {
                                cache = decoded.readAllBytes();
                            }
                            return PutObjectResponse.builder().build();
                        }
                        assertEquals("output-bucket", request.getBucketName());
                        assertTrue(List.of("MMM_TraficInfo.geojson", "MMM_TraficInfo_BICYCLES.geojson")
                                .contains(request.getObjectName()));
                        assertEquals("application/geo+json", request.getContentType());
                        assertEquals("gzip", request.getContentEncoding());
                        uploads++;
                        if (failUpload || (failBicycleUpload && request.getObjectName().endsWith("_BICYCLES.geojson"))) {
                            throw new IllegalStateException("upload failed");
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
            return new GeoJsonPublisher(GeoJsonDataSource.MMM, new ObjectStoreClient(storage, "namespace", "input-bucket"),
                    new ObjectStoreClient(storage, "namespace", "output-bucket"),
                    new OSMInfoProviderImpl(new ObjectStoreClient(storage, "namespace", "osminfo"), new OpenStreetMapClient() {
                        @Override
                        public Map<String, String> getWayTags(long wayId) throws IOException {
                            assertNotNull("GeoJSON must be uploaded before OSM requests", output);
                            osmRequests.add(wayId);
                            if (failOsm) {
                                throw new IOException("OSM unavailable");
                            }
                            return Map.of("maxspeed", "50", "highway", "residential");
                        }
                    }, cacheObject, Clock.fixed(now, ZoneOffset.UTC)));
        }

        void assertStreamsClosed() {
            for (TrackedStream stream : streams) {
                assertTrue("Download stream must be closed", stream.closed);
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
