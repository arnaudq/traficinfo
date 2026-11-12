package org.traficinfo.dsfunctions.dpt34;

import org.junit.Test;
import org.traficinfo.dsfunctions.OOSResourceMetadata;
import org.traficinfo.dsfunctions.DataSourceSettings;
import org.traficinfo.dsfunctions.DataSourceTestSettings;
import org.traficinfo.dsfunctions.ObjectStoreClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.SourceObjectPublisher;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SourceObjectPublisherTest {

    private static final String ETAG = "sha256:" + "a".repeat(64);
    private static final DataSourceSettings SETTINGS = DataSourceTestSettings.settings("DPT34");
    private static final DataSourceSettings.Input CSV_INPUT = SETTINGS.inputs().get(0);
    private static final String OBJECT = CSV_INPUT.objectName();
    private static final byte[] CSV = "Identifiant du compteur;Année\nPer.034.2005.00.3;2025\n"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    public void firstDownloadPublishesCsvAndValidator() throws Exception {
        SourceStub stub = new SourceStub();
        stub.publisher().publish(SETTINGS);
        assertEquals(List.of(OBJECT), stub.uploads);
        assertArrayEquals(CSV, stub.contents.get(OBJECT));
        OOSResourceMetadata metadata = stub.metadata.get(OBJECT);
        assertEquals("\"" + ETAG + "\"", metadata.getETag());
        assertNotNull(metadata.getMetadata().get("downloaded-at"));
        assertEquals(LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE),
                metadata.getLastModified());
        assertEquals(1, stub.downloads);
        assertTrue(stub.closed);
    }

    @Test
    public void identicalDownloadDoesNotUploadOrArchive() {
        SourceStub stub = new SourceStub();
        SourceObjectPublisher publisher = stub.publisher();
        publisher.publish(SETTINGS);
        stub.uploads.clear();
        stub.unchanged = true;
        publisher.publish(SETTINGS);
        assertEquals(2, stub.downloads);
        assertTrue(stub.uploads.isEmpty());
        assertEquals(0, stub.archiveReads);
    }

    @Test
    public void changedDownloadArchivesBeforeReplacingCurrentObject() throws Exception {
        SourceStub stub = new SourceStub();
        SourceObjectPublisher publisher = stub.publisher();
        publisher.publish(SETTINGS);
        stub.metadata.get(OBJECT).setLastModified(LocalDate.of(2026, 10, 17));
        stub.uploads.clear();
        stub.downloadBytes = "new CSV content".getBytes(StandardCharsets.UTF_8);
        publisher.publish(SETTINGS);
        String archive = "DPT34_Comptage_20261017.csv";
        assertEquals(List.of(archive, OBJECT), stub.uploads);
        assertArrayEquals(CSV, stub.contents.get(archive));
        assertArrayEquals(stub.downloadBytes, stub.contents.get(OBJECT));
        assertEquals("\"" + ETAG + "\"", stub.metadata.get(archive).getETag());
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
        assertEquals(List.of(CSV_INPUT.archiveName(stub.metadata.get(OBJECT).getLastModified())), stub.uploads);
        assertArrayEquals(CSV, stub.contents.get(OBJECT));
    }

    private static SourceStub initializedStub() {
        SourceStub stub = new SourceStub();
        stub.publisher().publish(SETTINGS);
        stub.uploads.clear();
        return stub;
    }

    private static void assertFailure(SourceStub stub) {
        try {
            stub.publisher().publish(SETTINGS);
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
        boolean unchanged;
        boolean failDownload;
        boolean failArchiveUpload;
        boolean failCurrentUpload;

        SourceObjectPublisher publisher() {
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
                    assertEquals(URI.create(CSV_INPUT.url()), uri);
                    OOSResourceMetadata previous = metadata.get(OBJECT);
                    assertEquals(previous == null ? null : previous.getETag(), etag);
                    downloads++;
                    if (failDownload) {
                        throw new IOException("Download failed");
                    }
                    if (unchanged) {
                        return new EntityBody(ETAG, null, null);
                    }
                    return new EntityBody(ETAG, null, new ByteArrayInputStream(downloadBytes) {
                        @Override
                        public void close() throws IOException {
                            closed = true;
                            super.close();
                        }
                    });
                }
            };
            return new SourceObjectPublisher(http, storage);
        }
    }
}
