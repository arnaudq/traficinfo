package org.traficinfo.dsfunctions;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.model.ListObjects;
import com.oracle.bmc.objectstorage.model.ObjectSummary;
import com.oracle.bmc.objectstorage.responses.ListObjectsResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

public class ObjectStoreClientTest {
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
        assertTrue(store.createResource("lock", "application/json", new OOSResourceMetadata(), new ByteArrayInputStream(new byte[0])));
        assertFalse(store.createResource("lock", "application/json", new OOSResourceMetadata(), new ByteArrayInputStream(new byte[0])));
        try {
            store.createResource("lock", "application/json", new OOSResourceMetadata(), new ByteArrayInputStream(new byte[0]));
            fail("Expected storage failure");
        } catch (com.oracle.bmc.model.BmcException expected) {
            assertEquals(500, expected.getStatusCode());
        }
    }

    private void assertCompressedUpload(String name, String contentType, String content) throws IOException {
        byte[] original = content.getBytes(StandardCharsets.UTF_8);
        OOSResourceMetadata metadata = new OOSResourceMetadata();
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
                    byte[] compressed = request.getPutObjectBody().readAllBytes();
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
                    "data.csv", "text/csv", new OOSResourceMetadata(), source);
            fail("Expected source failure");
        } catch (IOException expected) {
            assertEquals("source failed", expected.getMessage());
        }
    }
}
