package org.traficinfo.dsfunctions.m3m;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

public class ObjectStoreClientTest {
    @Test
    public void uploadsGzipWithOriginalNamesTypesAndMetadata() throws IOException {
        assertCompressedUpload("data.csv", "text/csv", "name,value\nMontpellier,42\n".repeat(100));
        assertCompressedUpload("data.json", "application/json", "{\"city\":\"Montpellier\",\"value\":42}");
        assertCompressedUpload("empty.csv", "text/csv", "");
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
