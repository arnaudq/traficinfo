package org.traficinfo.dsfunctions.m3m;

import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OOSResourceMetadata;
import org.traficinfo.dsfunctions.DataSourceTestSettings;

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
import java.util.Map;
import java.util.HashMap;

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
        stub.function().handleRequest("");
        stub.uploads.clear();
        stub.downloads.clear();
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
        final Map<String, OOSResourceMetadata> metadata = new HashMap<>();
        final Map<String, byte[]> contents = new HashMap<>();
        boolean unchanged;
        boolean failDownload;
        boolean failUpload;

        DataSourceFunction function() {
            HttpClient http = new HttpClient() {
                @Override
                public EntityBody get(URI uri, String etag) throws IOException {
                    downloads.add(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1));
                    String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
                    assertEquals(metadata.containsKey(name) ? metadata.get(name).getETag() : null, etag);
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
                    return metadata.get(resourceName);
                }

                @Override
                public InputStream getResource(String name) {
                    return new ByteArrayInputStream(contents.get(name));
                }

                @Override
                public void putResource(String name, String type, OOSResourceMetadata metadata,
                                        InputStream stream) throws IOException {
                    if (failUpload) {
                        throw new IOException("upload failed");
                    }
                    contents.put(name, stream.readAllBytes());
                    SourceStub.this.metadata.put(name, metadata);
                    uploads.add(name);
                }
            };
            return new DataSourceFunction(DataSourceTestSettings.settings("MMM"), http, storage);
        }
    }
}
