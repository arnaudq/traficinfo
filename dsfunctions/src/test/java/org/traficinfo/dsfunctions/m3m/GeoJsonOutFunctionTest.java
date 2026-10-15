package org.traficinfo.dsfunctions.m3m;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.model.BmcException;
import org.traficinfo.parser.OSMWayInfo;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;
import org.traficinfo.dsfunctions.OpenStreetMapClient;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import org.junit.Test;

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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class GeoJsonOutFunctionTest {
    private static final String CSV = "code_centrale,name,nom_commune,annee,type_compteur,code_canal,"
            + "mja_jo,hp_johvs_matin,hp_johvs_soir,v85ma_tcj,vtma_tcj\n"
            + "100457,Rue de l'École,Montpellier," + Year.now().getValue()
            + ",compteur feux,1,577,72,43,30,28\n";
    private static final String GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[3.88,43.60]},"
            + "\"properties\":{\"code_centrale\":\"100457\"}}]}";

    @Test
    public void mergesPlainAndGzipSourcesAndUploadsToOutputBucket() throws Exception {
        for (boolean gzip : new boolean[]{false, true}) {
            StorageStub stub = new StorageStub(gzip);
            assertEquals("OK", stub.function().handleRequest(""));
            assertEquals(Arrays.asList("MMM_OSMInfo.json", "MMM_MMM_Comptage.csv", "MMM_MMM_Comptage.json"), stub.downloads);
            assertEquals(1, stub.uploads);
            JsonNode result = new ObjectMapper().readTree(stub.output);
            assertEquals("FeatureCollection", result.get("type").asText());
            assertEquals(1, result.get("features").size());
            JsonNode feature = result.get("features").get(0);
            assertEquals(3.88, feature.get("geometry").get("coordinates").get(0).asDouble(), 0.0001);
            assertEquals("100457", feature.get("properties").get("code_centrale").asText());
            assertEquals(577, feature.get("properties").get("moyenne_jour_annuelle").asInt());
            assertEquals("28", feature.get("properties").get("mean_speed").asText());
            assertEquals("Rue de l'École - Montpellier", feature.get("properties").get("description").asText());
            stub.assertStreamsClosed();
        }
    }

    @Test
    public void returnsKoWithoutUploadWhenDownloadFails() {
        StorageStub stub = new StorageStub(false);
        stub.failDownload = true;
        assertEquals("KO", stub.function().handleRequest(""));
        assertEquals(0, stub.uploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void returnsKoWithoutUploadWhenParsingFails() {
        StorageStub stub = new StorageStub(false);
        stub.geojson = "invalid JSON";
        assertEquals("KO", stub.function().handleRequest(""));
        assertEquals(0, stub.uploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void returnsKoWhenUploadFails() {
        StorageStub stub = new StorageStub(true);
        stub.failUpload = true;
        assertEquals("KO", stub.function().handleRequest(""));
        assertEquals(1, stub.uploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void refreshesMissingCacheAfterOutputAndUsesItOnNextRun() throws Exception {
        StorageStub stub = new StorageStub(true);
        stub.cacheObject = "custom-cache.json";
        stub.csv = csvWithWays(1, 1);
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(Arrays.asList(1L), stub.osmRequests);
        assertEquals("-", maximumSpeed(stub.output));
        assertEquals(1, stub.cacheUploads);
        JsonNode cache = new ObjectMapper().readTree(stub.cache);
        assertEquals(stub.now.toString(), cache.get("1").get("lastUpdated").asText());
        assertEquals("residential", cache.get("1").get("tags").get("highway").asText());
        assertFalse(cache.get("1").has("maximumSpeed"));
        stub.assertStreamsClosed();

        assertEquals("OK", stub.function().handleRequest(""));
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
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals("30", maximumSpeed(stub.output));
        assertEquals(Arrays.asList(1L), stub.osmRequests);
        JsonNode updated = new ObjectMapper().readTree(stub.cache);
        assertEquals("50", updated.get("1").get("tags").get("maxspeed").asText());
        assertEquals("70", updated.get("2").get("tags").get("maxspeed").asText());
    }

    @Test
    public void limitsRequestsToTenAndLeavesRemainingWaysForLaterRuns() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.csv = csvWithWays(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 1);
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(10, stub.osmRequests.size());
        assertEquals(10, new ObjectMapper().readTree(stub.cache).size());
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L), stub.osmRequests);
    }

    @Test
    public void failedRequestsCountTowardLimitAndPreserveOldCache() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.csv = csvWithWays(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        stub.cache = new ObjectMapper().writeValueAsBytes(Map.of(1L,
                new OSMWayInfo(stub.now.atZone(ZoneOffset.UTC).minusMonths(4).toInstant(),
                        Map.of("maxspeed", "30"))));
        byte[] original = stub.cache.clone();
        stub.failOsm = true;
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(10, stub.osmRequests.size());
        assertEquals(0, stub.cacheUploads);
        assertArrayEquals(original, stub.cache);
    }

    @Test
    public void refusesToOverwriteUnreadableCache() {
        StorageStub stub = new StorageStub(false);
        stub.failCacheDownload = true;
        assertEquals("KO", stub.function().handleRequest(""));
        assertEquals(0, stub.uploads);
        assertEquals(0, stub.cacheUploads);
        stub.failCacheDownload = false;
        stub.cache = "invalid JSON".getBytes(StandardCharsets.UTF_8);
        assertEquals("KO", stub.function().handleRequest(""));
        assertEquals(0, stub.cacheUploads);
        stub.assertStreamsClosed();
    }

    @Test
    public void reportsCacheUploadFailure() {
        StorageStub stub = new StorageStub(false);
        stub.csv = csvWithWays(1);
        stub.failCacheUpload = true;
        assertEquals("KO", stub.function().handleRequest(""));
        assertEquals(1, stub.uploads);
        assertEquals(1, stub.cacheUploads);
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
                .get("properties").get("maximum_speed").asText();
    }

    private static class StorageStub {
        final boolean gzip;
        final List<String> downloads = new ArrayList<>();
        final List<TrackedStream> streams = new ArrayList<>();
        String geojson = GEOJSON;
        String csv = CSV;
        String cacheObject = GeoJsonOutFunction.DEFAULT_OSM_CACHE_OBJECT;
        byte[] cache;
        int cacheUploads;
        final Instant now = Instant.now();
        final List<Long> osmRequests = new ArrayList<>();
        boolean failOsm;
        boolean failCacheUpload;
        boolean failCacheDownload;
        boolean failDownload;
        boolean failUpload;
        int uploads;
        byte[] output;

        StorageStub(boolean gzip) {
            this.gzip = gzip;
        }

        GeoJsonOutFunction function() {
            ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                    ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                    (proxy, method, args) -> {
                        if ("getObject".equals(method.getName())) {
                            GetObjectRequest request = (GetObjectRequest) args[0];
                            assertEquals("namespace", request.getNamespaceName());
                            assertEquals(request.getObjectName().equals(cacheObject) ? "osminfo" : "input-bucket",
                                    request.getBucketName());
                            downloads.add(request.getObjectName());
                            if (failDownload && request.getObjectName().endsWith(".csv")) {
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
                                bytes = (request.getObjectName().endsWith(".csv") ? csv : geojson)
                                        .getBytes(StandardCharsets.UTF_8);
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
                        assertEquals("MMM_TraficInfo.geojson", request.getObjectName());
                        assertEquals("application/geo+json", request.getContentType());
                        assertEquals("gzip", request.getContentEncoding());
                        uploads++;
                        if (failUpload) {
                            throw new IllegalStateException("upload failed");
                        }
                        try (GZIPInputStream decoded = new GZIPInputStream(request.getPutObjectBody())) {
                            output = decoded.readAllBytes();
                        }
                        return PutObjectResponse.builder().build();
                    });
            return new GeoJsonOutFunction(new ObjectStoreClient(storage, "namespace", "input-bucket"),
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
