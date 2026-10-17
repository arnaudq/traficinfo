package org.traficinfo.dsfunctions.dpt34;

import org.junit.Test;
import org.traficinfo.dsfunctions.OOSResourceMetadata;
import org.traficinfo.dsfunctions.ObjectStoreClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import org.traficinfo.dsfunctions.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DataSourceFunctionTest {

    private static final String OBJECT = DataSourceFunction.CSV_OBJECT_NAME;
    private static final byte[] CSV = "Identifiant du compteur;Année\nPer.034.2005.00.3;2025\n"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    public void firstDownloadPublishesCsvAndContentHash() throws Exception {
        SourceStub stub = new SourceStub();
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(List.of(OBJECT), stub.uploads);
        assertArrayEquals(CSV, stub.contents.get(OBJECT));
        OOSResourceMetadata metadata = stub.metadata.get(OBJECT);
        assertEquals(hash(CSV), metadata.getMetadata().get(DataSourceFunction.CONTENT_SHA256));
        assertNotNull(metadata.getMetadata().get("downloaded-at"));
        assertNull(metadata.getETag());
        assertEquals(1, stub.downloads);
        assertTrue(stub.closed);
    }

    @Test
    public void identicalDownloadDoesNotUploadOrArchive() {
        SourceStub stub = new SourceStub();
        DataSourceFunction function = stub.function();
        function.handleRequest("");
        stub.uploads.clear();
        assertEquals("OK", function.handleRequest(""));
        assertEquals(2, stub.downloads);
        assertTrue(stub.uploads.isEmpty());
        assertEquals(0, stub.archiveReads);
    }

    @Test
    public void changedDownloadArchivesBeforeReplacingCurrentObject() throws Exception {
        SourceStub stub = new SourceStub();
        DataSourceFunction function = stub.function();
        function.handleRequest("");
        stub.uploads.clear();
        stub.downloadBytes = "new CSV content".getBytes(StandardCharsets.UTF_8);
        assertEquals("OK", function.handleRequest(""));
        String archive = "DPT34_Comptage_" + hash(CSV) + ".csv";
        assertEquals(List.of(archive, OBJECT), stub.uploads);
        assertArrayEquals(CSV, stub.contents.get(archive));
        assertArrayEquals(stub.downloadBytes, stub.contents.get(OBJECT));
        assertEquals(hash(CSV),
                stub.metadata.get(archive).getMetadata().get(DataSourceFunction.CONTENT_SHA256));
    }

    @Test
    public void existingObjectWithoutHashIsArchived() {
        SourceStub stub = new SourceStub();
        stub.contents.put(OBJECT, new byte[]{1, 2, 3});
        stub.metadata.put(OBJECT, new OOSResourceMetadata());
        stub.function().handleRequest("");
        assertEquals(2, stub.uploads.size());
        String archive = stub.uploads.get(0);
        assertTrue(archive.startsWith("DPT34_Comptage_"));
        assertArrayEquals(new byte[]{1, 2, 3}, stub.contents.get(archive));
        assertArrayEquals(CSV, stub.contents.get(OBJECT));
    }

    @Test
    public void downloadFailureLeavesCurrentObjectUntouched() {
        SourceStub stub = initializedStub();
        stub.failDownload = true;
        assertFailure(stub);
        assertTrue(stub.uploads.isEmpty());
        assertArrayEquals(CSV, stub.contents.get(OBJECT));
    }

    @Test
    public void emptyDownloadIsNotPublished() {
        SourceStub stub = initializedStub();
        stub.downloadBytes = new byte[0];
        assertFailure(stub);
        assertTrue(stub.uploads.isEmpty());
        assertArrayEquals(CSV, stub.contents.get(OBJECT));
        assertTrue(stub.closed);
    }

    @Test
    public void archiveFailurePreventsReplacementOfCurrentObject() {
        SourceStub stub = initializedStub();
        stub.downloadBytes = new byte[]{4, 5, 6};
        stub.failArchiveUpload = true;
        assertFailure(stub);
        assertTrue(stub.uploads.isEmpty());
        assertArrayEquals(CSV, stub.contents.get(OBJECT));
    }

    @Test
    public void currentUploadFailurePreservesCurrentAndArchivedContent() throws Exception {
        SourceStub stub = initializedStub();
        stub.downloadBytes = new byte[]{4, 5, 6};
        stub.failCurrentUpload = true;
        assertFailure(stub);
        assertEquals(List.of("DPT34_Comptage_" + hash(CSV) + ".csv"), stub.uploads);
        assertArrayEquals(CSV, stub.contents.get(OBJECT));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static SourceStub initializedStub() {
        SourceStub stub = new SourceStub();
        stub.function().handleRequest("");
        stub.uploads.clear();
        return stub;
    }

    private static void assertFailure(SourceStub stub) {
        try {
            stub.function().handleRequest("");
            fail("Expected publication failure");
        } catch (UncheckedIOException expected) {
            assertTrue(expected.getMessage().contains(OBJECT));
        }
    }

    private static class SourceStub {
        final Map<String, byte[]> contents = new HashMap<>();
        final Map<String, OOSResourceMetadata> metadata = new HashMap<>();
        final List<String> uploads = new ArrayList<>();
        byte[] downloadBytes = CSV;
        int downloads;
        int archiveReads;
        boolean closed;
        boolean failDownload;
        boolean failArchiveUpload;
        boolean failCurrentUpload;

        DataSourceFunction function() {
            ObjectStoreClient storage = new ObjectStoreClient(null, "namespace", "dsdpt34") {
                @Override
                public OOSResourceMetadata getResourceMetadata(String name) {
                    return metadata.get(name);
                }

                @Override
                public InputStream getResource(String name) {
                    archiveReads++;
                    return new ByteArrayInputStream(contents.get(name));
                }

                @Override
                public void putResource(String name, String type, OOSResourceMetadata meta,
                                        InputStream input) throws IOException {
                    assertEquals("text/csv", type);
                    if ((name.equals(OBJECT) && failCurrentUpload)
                            || (!name.equals(OBJECT) && failArchiveUpload)) {
                        throw new IOException("Upload failed");
                    }
                    contents.put(name, input.readAllBytes());
                    metadata.put(name, meta);
                    uploads.add(name);
                }
            };
            HttpClient http = new HttpClient() {
                @Override
                public EntityBody get(URI uri, String etag) throws IOException {
                    assertEquals(DataSourceFunction.CSV_URI, uri);
                    assertNull(etag);
                    downloads++;
                    if (failDownload) {
                        throw new IOException("Download failed");
                    }
                    return new EntityBody(null, null, new ByteArrayInputStream(downloadBytes) {
                        @Override
                        public void close() throws IOException {
                            closed = true;
                            super.close();
                        }
                    });
                }
            };
            return new DataSourceFunction(http, storage);
        }
    }
}
