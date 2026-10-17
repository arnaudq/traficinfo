package org.traficinfo.dsfunctions.m3m;

import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OOSResourceMetadata;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.*;

public class DataSourceFunctionTest {
    @Test
    public void publishesJsonBeforeCsv() {
        SourceStub stub = new SourceStub();
        assertEquals("OK", stub.function().handleRequest(""));
        assertEquals(Arrays.asList("MMM_MMM_Comptage.json", "MMM_MMM_Comptage.csv"), stub.uploads);
    }

    @Test
    public void unchangedSourcesDoNotUpload() {
        SourceStub stub = new SourceStub();
        stub.unchanged = true;
        assertEquals("OK", stub.function().handleRequest(""));
        assertTrue(stub.uploads.isEmpty());
        assertEquals(Arrays.asList("MMM_MMM_Comptage.json", "MMM_MMM_Comptage.csv"), stub.downloads);
    }

    @Test
    public void jsonDownloadFailurePreventsCsvPublication() {
        SourceStub stub = new SourceStub();
        stub.failDownload = true;
        assertJsonFailure(stub);
    }

    @Test
    public void jsonUploadFailurePreventsCsvPublication() {
        SourceStub stub = new SourceStub();
        stub.failUpload = true;
        assertJsonFailure(stub);
    }

    private static void assertJsonFailure(SourceStub stub) {
        try {
            stub.function().handleRequest("");
            fail("Expected failure");
        } catch (UncheckedIOException expected) {
            assertTrue(expected.getMessage().contains("MMM_MMM_Comptage.json"));
        }
        assertEquals(Arrays.asList("MMM_MMM_Comptage.json"), stub.downloads);
        assertTrue(stub.uploads.isEmpty());
    }

    private static class SourceStub {
        final List<String> downloads = new ArrayList<>();
        final List<String> uploads = new ArrayList<>();
        boolean unchanged;
        boolean failDownload;
        boolean failUpload;

        DataSourceFunction function() {
            HttpClient http = new HttpClient() {
                @Override
                public EntityBody get(URI uri, String etag) throws IOException {
                    downloads.add(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1));
                    assertEquals("\"previous\"", etag);
                    if (failDownload) {
                        throw new IOException("download failed");
                    }
                    return new EntityBody("next", new Date(0), unchanged ? null
                            : new ByteArrayInputStream(new byte[]{1, 2, 3}));
                }
            };
            ObjectStoreClient storage = new ObjectStoreClient(null, "namespace", "bucket") {
                @Override
                public OOSResourceMetadata getResourceMetadata(String resourceName) {
                    OOSResourceMetadata metadata = new OOSResourceMetadata();
                    metadata.setETag("previous");
                    return metadata;
                }

                @Override
                public void renameResource(String oldName, String newName) {
                    // Archive without contacting OCI.
                }

                @Override
                public void putResource(String name, String type, OOSResourceMetadata metadata,
                                        InputStream stream) throws IOException {
                    if (failUpload) {
                        throw new IOException("upload failed");
                    }
                    uploads.add(name);
                }
            };
            return new DataSourceFunction(http, storage);
        }
    }
}
