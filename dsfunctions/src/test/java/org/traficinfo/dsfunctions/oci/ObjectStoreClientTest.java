package org.traficinfo.dsfunctions.oci;

import org.traficinfo.dsfunctions.ObjectMetadata;
import org.traficinfo.dsfunctions.StorageException;
import org.traficinfo.dsfunctions.ObjectNotFoundException;
import org.traficinfo.dsfunctions.ObjectStore;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.model.ListObjects;
import com.oracle.bmc.objectstorage.model.ObjectSummary;
import com.oracle.bmc.objectstorage.responses.ListObjectsResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import com.oracle.bmc.retrier.Retriers;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class ObjectStoreClientTest {
    @Test
    public void normalizesProviderFailuresAcrossStorageOperations() throws Exception {
        for (String operation : List.of("read", "metadata", "list", "write", "create", "delete")) {
            var cause = new com.oracle.bmc.model.BmcException(403, "Forbidden", "Denied", "request-id");
            ObjectStorage client = (ObjectStorage) Proxy.newProxyInstance(ObjectStorage.class.getClassLoader(),
                    new Class<?>[]{ObjectStorage.class}, (proxy, method, args) -> { throw cause; });
            ObjectStore store = new ObjectStoreClient(client, "namespace", "bucket");
            StorageException failure = assertThrows(StorageException.class, () -> {
                switch (operation) {
                    case "read" -> store.getResource("object");
                    case "metadata" -> store.getResourceMetadata("object");
                    case "list" -> store.listResources();
                    case "write" -> store.putResource("object", "application/json", new ObjectMetadata(),
                            new ByteArrayInputStream(new byte[0]));
                    case "create" -> store.createResource("object", "application/json", new ObjectMetadata(),
                            new ByteArrayInputStream(new byte[0]));
                    case "delete" -> store.deleteResource("object");
                    default -> fail("Unknown operation");
                }
            });
            assertFalse(failure instanceof ObjectNotFoundException);
            assertSame(cause, failure.getCause());
            assertEquals("request-id", failure.getRequestId());
        }
    }

    @Test
    public void missingBodyIsDistinctFromOtherReadFailures() {
        var cause = new com.oracle.bmc.model.BmcException(404, "NotFound", "Missing", "request-id");
        ObjectStorage client = (ObjectStorage) Proxy.newProxyInstance(ObjectStorage.class.getClassLoader(),
                new Class<?>[]{ObjectStorage.class}, (proxy, method, args) -> { throw cause; });
        ObjectStore store = new ObjectStoreClient(client, "namespace", "bucket");
        var failure = assertThrows(ObjectNotFoundException.class, () -> store.getResource("object"));
        assertSame(cause, failure.getCause());
        assertEquals("request-id", failure.getRequestId());
    }

    @Test
    public void readsPlainAndGzipBodiesAndClosesStreamsAndClient() throws Exception {
        byte[] original = "stored contents".getBytes(StandardCharsets.UTF_8);
        for (boolean compressed : List.of(false, true)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (compressed) {
                try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(original); }
            } else {
                bytes.write(original);
            }
            AtomicBoolean streamClosed = new AtomicBoolean();
            AtomicBoolean clientClosed = new AtomicBoolean();
            var body = new ByteArrayInputStream(bytes.toByteArray()) {
                @Override public void close() throws IOException { streamClosed.set(true); super.close(); }
            };
            ObjectStorage client = (ObjectStorage) Proxy.newProxyInstance(ObjectStorage.class.getClassLoader(),
                    new Class<?>[]{ObjectStorage.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) { clientClosed.set(true); return null; }
                        assertEquals("getObject", method.getName());
                        return com.oracle.bmc.objectstorage.responses.GetObjectResponse.builder()
                                .inputStream(body).contentEncoding(compressed ? "gzip" : null).build();
                    });
            try (ObjectStore store = new ObjectStoreClient(client, "namespace", "bucket")) {
                try (InputStream decoded = store.getResource("object")) {
                    assertArrayEquals(original, decoded.readAllBytes());
                    assertFalse(streamClosed.get());
                }
                assertTrue(streamClosed.get());
            }
            assertTrue(clientClosed.get());
        }
    }

    @Test
    public void closesBodyWhenGzipCannotBeDecoded() {
        AtomicBoolean closed = new AtomicBoolean();
        var body = new ByteArrayInputStream("not gzip".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        ObjectStorage client = (ObjectStorage) Proxy.newProxyInstance(ObjectStorage.class.getClassLoader(),
                new Class<?>[]{ObjectStorage.class}, (proxy, method, args) ->
                        com.oracle.bmc.objectstorage.responses.GetObjectResponse.builder()
                                .inputStream(body).contentEncoding("gzip").build());
        ObjectStore store = new ObjectStoreClient(client, "namespace", "bucket");
        assertThrows(IOException.class, () -> store.getResource("object"));
        assertTrue(closed.get());
    }

    @Test
    public void existingObjectsWithoutCustomMetadataReturnAnEmptyMetadataObject() {
        for (java.util.Map<String, String> values : java.util.Arrays.<java.util.Map<String, String>>asList(
                null, java.util.Map.of(), java.util.Map.of("dataset-id", "dpt34-2014-2019"))) {
            ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                    ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                    (proxy, method, args) -> {
                        assertEquals("headObject", method.getName());
                        var request = (com.oracle.bmc.objectstorage.requests.HeadObjectRequest) args[0];
                        assertEquals("namespace", request.getNamespaceName());
                        assertEquals("dsdpt34", request.getBucketName());
                        assertEquals("DPT34_Manifest.json", request.getObjectName());
                        return com.oracle.bmc.objectstorage.responses.HeadObjectResponse.builder().opcMeta(values).build();
                    });
            ObjectMetadata metadata = new ObjectStoreClient(storage, "namespace", "dsdpt34")
                    .getResourceMetadata("DPT34_Manifest.json");
            assertNotNull(metadata);
            assertEquals(values == null ? java.util.Map.of() : values, metadata.getMetadata());
            assertNull(metadata.getETag());
            assertNull(metadata.getLastModified());
            if (values == null) {
                metadata.setETag("test");
                assertEquals("\"test\"", metadata.getETag());
            }
        }
    }

    @Test
    public void onlyMissingObjectsReturnNullMetadataAndOtherHeadFailuresPropagate() {
        for (int status : new int[]{404, 500}) {
            ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                    ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                    (proxy, method, args) -> {
                        throw new com.oracle.bmc.model.BmcException(status, "test", "HEAD failed", "request");
                    });
            ObjectStoreClient store = new ObjectStoreClient(storage, "namespace", "dsdpt34");
            if (status == 404) {
                assertNull(store.getResourceMetadata("DPT34_Manifest.json"));
            } else {
                try {
                    store.getResourceMetadata("DPT34_Manifest.json");
                    fail("Expected HEAD failure");
                } catch (StorageException expected) {
                    assertEquals(status, ((com.oracle.bmc.model.BmcException) expected.getCause()).getStatusCode());
                    assertEquals("request", expected.getRequestId());
                }
            }
        }
    }

    @Test
    public void listsNamesAcrossAllPagesIncludingAnEmptyBucket() {
        AtomicInteger calls = new AtomicInteger();
        ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                (proxy, method, args) -> {
                    assertEquals("listObjects", method.getName());
                    ListObjectsRequest request = (ListObjectsRequest) args[0];
                    assertEquals("namespace", request.getNamespaceName());
                    assertEquals("osminfo", request.getBucketName());
                    int call = calls.getAndIncrement();
                    if (call == 1) {
                        assertEquals("second", request.getStart());
                    } else {
                        assertNull(request.getStart());
                    }
                    return ListObjectsResponse.builder().listObjects(ListObjects.builder()
                            .objects(call == 2 ? List.of() : List.of(ObjectSummary.builder()
                                    .name(call == 0 ? "first" : "second").build()))
                            .nextStartWith(call == 0 ? "second" : null).build()).build();
                });
        ObjectStoreClient store = new ObjectStoreClient(storage, "namespace", "osminfo");
        assertEquals(List.of("first", "second"), store.listResources());
        assertTrue(store.listResources().isEmpty());
        assertEquals(3, calls.get());
    }

    @Test
    public void uploadsGzipWithOriginalNamesTypesAndMetadata() throws IOException {
        assertCompressedUpload("data.csv", "text/csv", "name,value\nMontpellier,42\n".repeat(100));
        assertCompressedUpload("data.json", "application/json", "{\"city\":\"Montpellier\",\"value\":42}");
        assertCompressedUpload("empty.csv", "text/csv", "");
    }

    @Test
    public void createsOnlyAbsentObjectsAndPropagatesOtherStorageFailures() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                (proxy, method, args) -> {
                    PutObjectRequest request = (PutObjectRequest) args[0];
                    assertEquals("*", request.getIfNoneMatch());
                    assertNull(request.getIfMatch());
                    int call = calls.getAndIncrement();
                    if (call > 0) throw new com.oracle.bmc.model.BmcException(call == 1 ? 412 : 500,
                            "test", "create failed", "request");
                    return PutObjectResponse.builder().build();
                });
        ObjectStoreClient store = new ObjectStoreClient(storage, "namespace", "bucket");
        assertTrue(store.createResource("lock", "application/json", new ObjectMetadata(), new ByteArrayInputStream(new byte[0])));
        assertFalse(store.createResource("lock", "application/json", new ObjectMetadata(), new ByteArrayInputStream(new byte[0])));
        try {
            store.createResource("lock", "application/json", new ObjectMetadata(), new ByteArrayInputStream(new byte[0]));
            fail("Expected storage failure");
        } catch (StorageException expected) {
            assertEquals(500, ((com.oracle.bmc.model.BmcException) expected.getCause()).getStatusCode());
            assertEquals("request", expected.getRequestId());
        }
    }

    private void assertCompressedUpload(String name, String contentType, String content) throws IOException {
        byte[] original = content.getBytes(StandardCharsets.UTF_8);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setETag("\"source-etag\"");
        AtomicBoolean uploaded = new AtomicBoolean();
        ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                (proxy, method, args) -> {
                    assertEquals("putObject", method.getName());
                    PutObjectRequest request = (PutObjectRequest) args[0];
                    assertEquals("namespace", request.getNamespaceName());
                    assertEquals("bucket", request.getBucketName());
                    assertEquals(name, request.getObjectName());
                    assertEquals(contentType, request.getContentType());
                    assertEquals("gzip", request.getContentEncoding());
                    assertEquals(metadata.getMetadata(), request.getOpcMeta());
                    InputStream retryableBody = Retriers.wrapInputStreamForRetry(request.getPutObjectBody());
                    retryableBody.mark(Integer.MAX_VALUE);
                    byte[] compressed = retryableBody.readAllBytes();
                    retryableBody.reset();
                    assertArrayEquals(compressed, retryableBody.readAllBytes());
                    assertEquals(Long.valueOf(compressed.length), request.getContentLength());
                    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                        assertArrayEquals(original, gzip.readAllBytes());
                    }
                    if (original.length > 1000) {
                        assertTrue(compressed.length < original.length);
                    }
                    uploaded.set(true);
                    return PutObjectResponse.builder().build();
                });
        new ObjectStoreClient(storage, "namespace", "bucket").putResource(
                name, contentType, metadata, new ByteArrayInputStream(original));
        assertTrue(uploaded.get());
    }

    @Test
    public void publishesCompressedJsonWithExplicitCacheControl() throws Exception {
        ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                (proxy, method, args) -> {
                    assertEquals("putObject", method.getName());
                    var request = (PutObjectRequest) args[0];
                    assertEquals("public, max-age=300", request.getCacheControl());
                    assertEquals("application/json", request.getContentType());
                    assertEquals("gzip", request.getContentEncoding());
                    try (var gzip = new GZIPInputStream(request.getPutObjectBody())) {
                        assertEquals("{}", new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    return PutObjectResponse.builder().build();
                });
        new ObjectStoreClient(storage, "namespace", "bucket").putResource("history/MMM.json",
                "application/json", new ObjectMetadata(),
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), "public, max-age=300");
    }

    @Test
    public void doesNotUploadWhenSourceCannotBeRead() {
        ObjectStorage storage = (ObjectStorage) Proxy.newProxyInstance(
                ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
                (proxy, method, args) -> {
                    fail("Failed compression must not upload an object");
                    return null;
                });
        InputStream source = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("source failed");
            }
        };
        try {
            new ObjectStoreClient(storage, "namespace", "bucket").putResource(
                    "data.csv", "text/csv", new ObjectMetadata(), source);
            fail("Expected source failure");
        } catch (IOException expected) {
            assertEquals("source failed", expected.getMessage());
        }
    }
}
