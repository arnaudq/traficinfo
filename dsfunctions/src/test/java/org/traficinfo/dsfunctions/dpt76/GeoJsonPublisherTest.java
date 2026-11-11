/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.dsfunctions.dpt76;

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
import org.traficinfo.dsfunctions.GeoJsonPublisher;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;
import org.traficinfo.dsfunctions.OpenStreetMapClient;
import java.io.InputStream;
import org.traficinfo.parsers.dpt76.DPT76Comptage;
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

public class GeoJsonPublisherTest {

    private static final int YEAR = Year.now().getValue();
    private static DPT76Comptage record(long fid, int year, Integer total, Double latitude, Double longitude) {
        DPT76Comptage count = new DPT76Comptage();
        count.setFid(fid);
        count.setCodeCentrale("DPT76_" + fid);
        count.setStationCode(1235.0);
        count.setAnnee(year);
        count.setMjaTvTcj(total);
        count.setLatitude(latitude);
        count.setLongitude(longitude);
        count.setRouteId("76 D0925");
        count.setDisplayName("76 D0925 - PR 104 + 200");
        return count;
    }

    @Test
    public void buildsPointsFromPlainAndGzipManifestPublicationsAndUploadsGzipGeoJson() throws Exception {
        for (boolean gzip : new boolean[]{false, true}) {
            StorageStub stub = new StorageStub(gzip);
            assertEquals("OK", stub.function().publish());
            assertEquals(List.of("DPT76_Manifest.json",
                    ManifestTestPublication.yearObjectName(GeoJsonDataSource.DPT76, YEAR - 1),
                    ManifestTestPublication.yearObjectName(GeoJsonDataSource.DPT76, YEAR)), stub.downloads);
            assertEquals(2, stub.uploads);
            JsonNode result = new ObjectMapper().readTree(stub.output);
            assertEquals("FeatureCollection", result.get("type").asText());
            assertEquals(2, result.get("features").size());
            JsonNode feature = result.get("features").get(0);
            assertEquals("Point", feature.get("geometry").get("type").asText());
            assertEquals(3.3, feature.get("geometry").get("coordinates").get(0).asDouble(), 0.0);
            assertEquals(43.3, feature.get("geometry").get("coordinates").get(1).asDouble(), 0.0);
            JsonNode properties = feature.get("properties");
            assertEquals("DPT76_1", properties.get("code_centrale").asText());
            assertEquals(YEAR, properties.get("annee").asInt());
            assertEquals(500, properties.get("moyenne_jour_annuelle").asInt());
            assertEquals("76 D0925 - PR 104 + 200", properties.get("description").asText());
            assertEquals("DPT76_236", result.get("features").get(1).get("properties").get("code_centrale").asText());
            assertEquals(0, new ObjectMapper().readTree(stub.bicycleOutput).get("features").size());
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
        stub.counts.add(record(2, YEAR, 500, null, null));
        stub.counts.add(record(3, YEAR, null, 43.0, 3.0));
        assertEquals("OK", stub.function().publish());
        assertEquals(2, new ObjectMapper().readTree(stub.output).get("features").size());
        stub.assertStreamsClosed();
    }

    @Test
    public void throwsWithoutUploadWhenDownloadFails() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.failDownload = true;
        assertFunctionFailure(() -> stub.function().publish());
        assertEquals(0, stub.uploads);
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
    public void requestsUnmappedCounterOnceWithLatestCoordinates() throws Exception {
        StorageStub stub = new StorageStub(false);
        // Newest row deliberately comes before the old row.
        stub.counts.add(record(2, YEAR, 500, 44.01, 4.01));
        stub.counts.add(record(2, YEAR - 1, 400, 44.0, 4.0));
        assertEquals("OK", stub.function().publish());
        JsonNode saved = new ObjectMapper().readTree(stub.requests).get("reverseLookups");
        assertEquals(2, saved.size());
        JsonNode missing = saved.get("DPT76_2");
        assertFalse(missing.has("status"));
        assertFalse(missing.has("osmWayId"));
        assertEquals(44.01, missing.get("latitude").asDouble(), 0);
        assertEquals(4.01, missing.get("longitude").asDouble(), 0);
    }

    @Test
    public void reportsRequestWriteFailureAfterPublishingGeoJson() throws Exception {
        StorageStub stub = new StorageStub(false);
        stub.failMappingUpload = true;
        assertFunctionFailure(() -> stub.function().publish());
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
            assertFunctionFailure(() -> stub.function().publish());
            assertSame(previousOutput, stub.output);
            assertEquals(0, stub.uploads);
            assertEquals(0, stub.mappingUploads);
            stub.assertStreamsClosed();
        }
    }

    private static class StorageStub {
        final boolean gzip;
        final List<String> downloads = new ArrayList<>();
        final List<TrackedStream> streams = new ArrayList<>();
        final List<DPT76Comptage> counts = new ArrayList<>(List.of(
                record(1, YEAR - 1, 400, 43.2, 3.2),
                record(1, YEAR, 500, 43.3, 3.3),
                record(236, YEAR, 7730, 49.9645878144106, 1.2394107905937)));
        boolean failDownload;
        boolean invalidManifest;
        boolean missingYear;
        boolean corruptYear;
        boolean failUpload;
        boolean failMappingUpload;
        int mappingUploads;
        byte[] mappings;
        byte[] requests;
        int uploads;
        byte[] output;
        byte[] bicycleOutput;

        StorageStub(boolean gzip) {
            this.gzip = gzip;
        }

        GeoJsonPublisher function() throws IOException {
            Map<String, byte[]> objects = ManifestTestPublication.fromRecords(GeoJsonDataSource.DPT76, counts);
            if (invalidManifest) objects.put("DPT76_Manifest.json", "invalid JSON".getBytes(StandardCharsets.UTF_8));
            String latestYear = ManifestTestPublication.yearObjectName(GeoJsonDataSource.DPT76, YEAR);
            if (missingYear) objects.remove(latestYear);
            if (corruptYear) objects.put(latestYear, "[]".getBytes(StandardCharsets.UTF_8));
            ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                    ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                    (proxy, method, args) -> {
                        if ("getObject".equals(method.getName())) {
                            GetObjectRequest request = (GetObjectRequest) args[0];
                            assertEquals("namespace", request.getNamespaceName());
                            assertEquals("dsdpt76", request.getBucketName());
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
                        assertTrue(List.of(GeoJsonDataSource.DPT76.getGeoJsonObjectName(),
                                GeoJsonDataSource.DPT76.getGeoJsonObjectName().replace(".geojson", "_BICYCLES.geojson"))
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
                            : "{\"DPT76_1\":{\"osmWayId\":123}}";
                    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
                }
                @Override
                public void putResource(String name, String type, org.traficinfo.dsfunctions.OOSResourceMetadata metadata,
                                        InputStream body) throws IOException {
                    assertNotNull("Cache must be saved after GeoJSON publication", output);
                    assertEquals("DPT76_OSMRequests.json", name);
                    mappingUploads++;
                    if (failMappingUpload) throw new IOException("Mapping upload failed");
                    requests = body.readAllBytes();
                }
            };
            OSMInfoProviderImpl provider = new OSMInfoProviderImpl(cacheStore, new OpenStreetMapClient() {
                @Override public Map<String, String> getWayTags(long id) {
                    throw new AssertionError("Fresh tags should not need requests");
                }
            }, "tags.json", "mappings.json");
            return new GeoJsonPublisher(GeoJsonDataSource.DPT76, new ObjectStoreClient(storage, "namespace", "dsdpt76"),
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
