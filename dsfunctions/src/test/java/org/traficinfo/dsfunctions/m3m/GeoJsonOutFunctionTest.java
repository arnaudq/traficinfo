package org.traficinfo.dsfunctions.m3m;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.objectstorage.ObjectStorage;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class GeoJsonOutFunctionTest {
    private static final String CSV = "code_centrale,name,nom_commune,annee,type_compteur,code_canal,"
            + "mja_jo,hp_johvs_matin,hp_johvs_soir,v85ma_tcj\n"
            + "100457,Rue de l'École,Montpellier," + Year.now().getValue()
            + ",compteur feux,1,577,72,43,30\n";
    private static final String GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[3.88,43.60]},"
            + "\"properties\":{\"code_centrale\":\"100457\"}}]}";

    @Test
    public void mergesPlainAndGzipSourcesAndUploadsToOutputBucket() throws Exception {
        for (boolean gzip : new boolean[]{false, true}) {
            StorageStub stub = new StorageStub(gzip);
            assertEquals("OK", stub.function().handleRequest(""));
            assertEquals(Arrays.asList("MMM_MMM_Comptage.csv", "MMM_MMM_Comptage.json"), stub.downloads);
            assertEquals(1, stub.uploads);
            JsonNode result = new ObjectMapper().readTree(stub.output);
            assertEquals("FeatureCollection", result.get("type").asText());
            assertEquals(1, result.get("features").size());
            JsonNode feature = result.get("features").get(0);
            assertEquals(3.88, feature.get("geometry").get("coordinates").get(0).asDouble(), 0.0001);
            assertEquals("100457", feature.get("properties").get("code_centrale").asText());
            assertEquals(577, feature.get("properties").get("moyenne_jour_annuelle").asInt());
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

    private static class StorageStub {
        final boolean gzip;
        final List<String> downloads = new ArrayList<>();
        final List<TrackedStream> streams = new ArrayList<>();
        String geojson = GEOJSON;
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
                            assertEquals("input-bucket", request.getBucketName());
                            downloads.add(request.getObjectName());
                            if (failDownload && downloads.size() == 2) {
                                throw new IllegalStateException("download failed");
                            }
                            byte[] bytes = (request.getObjectName().endsWith(".csv") ? CSV : geojson)
                                    .getBytes(StandardCharsets.UTF_8);
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
                    new ObjectStoreClient(storage, "namespace", "output-bucket"));
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
