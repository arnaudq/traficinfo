package org.traficinfo.dsfunctions.dpt34;

import org.traficinfo.dsfunctions.CsvDataSource;
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

public class GeoJsonOutFunctionTest {

    private static final int YEAR = Year.now().getValue();
    private static final String CSV = "\uFEFFIdentifiant du compteur;Année;Localisation;Commune;"
            + "Sens de la route;mja_tv_tcj;Latitude;Longitude\n"
            + "Per.034.2005.00.3;" + (YEAR - 1) + ";D11;BÉZIERS;3;400;43.2;3.2\n"
            + "Per.034.2005.00.3;" + YEAR + ";D11;BÉZIERS;3;500;43.3;3.3\n";

    @Test
    public void buildsPointsFromPlainAndGzipCsvAndUploadsGzipGeoJson() throws Exception {
        for (boolean gzip : new boolean[]{false, true}) {
            StorageStub stub = new StorageStub(gzip);
            assertEquals("OK", stub.function().handleRequest(""));
            assertEquals(List.of(CsvDataSource.DPT34.getCsvObjectName()), stub.downloads);
            assertEquals(1, stub.uploads);
            JsonNode result = new ObjectMapper().readTree(stub.output);
            assertEquals("FeatureCollection", result.get("type").asText());
            assertEquals(1, result.get("features").size());
            JsonNode feature = result.get("features").get(0);
            assertEquals("Point", feature.get("geometry").get("type").asText());
            assertEquals(3.3, feature.get("geometry").get("coordinates").get(0).asDouble(), 0.0);
            assertEquals(43.3, feature.get("geometry").get("coordinates").get(1).asDouble(), 0.0);
            JsonNode properties = feature.get("properties");
            assertEquals("DPT34_Per.034.2005.00.3", properties.get("code_centrale").asText());
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
        stub.csv += "Tou.034.0002.00.3;" + YEAR + ";D2;COMMUNE;3;500;;\n"
                + "Tou.034.0003.00.3;" + YEAR + ";D3;COMMUNE;3;;43;3\n";
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(1, new ObjectMapper().readTree(stub.output).get("features").size());
        stub.assertStreamsClosed();
    }

    @Test
    public void returnsKoWithoutUploadWhenDownloadFails() {
        StorageStub stub = new StorageStub(false);
        stub.failDownload = true;
        assertEquals("KO", stub.function().handleRequest(""));
        assertEquals(0, stub.uploads);
    }

    @Test
    public void returnsKoWithoutUploadWhenCsvIsInvalid() {
        StorageStub stub = new StorageStub(false);
        stub.csv = "Identifiant du compteur;Année\n;2026\n";
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
    public void savesUnmappedCounterOnceWithLatestCoordinates() throws Exception {
        StorageStub stub = new StorageStub(false);
        // Newest row deliberately comes before the old row.
        stub.csv += "Tou.034.0002.00.3;" + YEAR + ";D2;COMMUNE;3;500;44.01;4.01\n"
                + "Tou.034.0002.00.3;" + (YEAR - 1) + ";D2;COMMUNE;3;400;44;4\n";
        assertEquals("OK", stub.function().handleRequest(""));
        JsonNode saved = new ObjectMapper().readTree(stub.mappings);
        assertEquals(2, saved.size());
        JsonNode missing = saved.get("DPT34_Tou.034.0002.00.3");
        assertEquals("MISSING", missing.get("status").asText());
        assertTrue(missing.get("osmWayId").isNull());
        assertEquals(44.01, missing.get("latitude").asDouble(), 0);
        assertEquals(4.01, missing.get("longitude").asDouble(), 0);
    }

    @Test
    public void reportsMappingWriteFailureAfterPublishingGeoJson() {
        StorageStub stub = new StorageStub(false);
        stub.failMappingUpload = true;
        assertEquals("KO", stub.function().handleRequest(""));
        assertEquals(1, stub.uploads);
        assertEquals(1, stub.mappingUploads);
    }

    private static class StorageStub {
        final boolean gzip;
        final List<String> downloads = new ArrayList<>();
        final List<TrackedStream> streams = new ArrayList<>();
        String csv = CSV;
        boolean failDownload;
        boolean failUpload;
        boolean failMappingUpload;
        int mappingUploads;
        byte[] mappings;
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
                            assertEquals("dsdpt34", request.getBucketName());
                            assertEquals(CsvDataSource.DPT34.getCsvObjectName(), request.getObjectName());
                            downloads.add(request.getObjectName());
                            if (failDownload) {
                                throw new IllegalStateException("Download failed");
                            }
                            byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
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
                        assertEquals(GeoJsonOutFunction.GEOJSON_OBJECT_NAME, request.getObjectName());
                        assertEquals("application/geo+json", request.getContentType());
                        assertEquals("gzip", request.getContentEncoding());
                        uploads++;
                        if (failUpload) {
                            throw new IllegalStateException("Upload failed");
                        }
                        try (GZIPInputStream decoded = new GZIPInputStream(request.getPutObjectBody())) {
                            output = decoded.readAllBytes();
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
                            : "{\"DPT34_Per.034.2005.00.3\":{\"osmWayId\":123}}";
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
            return new GeoJsonOutFunction(new ObjectStoreClient(storage, "namespace", "dsdpt34"),
                    new ObjectStoreClient(storage, "namespace", "traficinfo"), provider);
        }

        void assertStreamsClosed() {
            for (TrackedStream stream : streams) {
                assertTrue("CSV stream must be closed", stream.closed);
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
