package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DataSourceSettingsTest {
    private static final DataSourceSettings.Input CSV = new DataSourceSettings.Input(
            "https://example.invalid/counts.csv", "custom-counts.csv", "text/csv");

    @Test
    public void fnReceivesDeploymentSettingsAndUsesConfiguredInput() throws Exception {
        DataSourceSettings settings = settings("resource-2022", CSV);
        String json = new ObjectMapper().writeValueAsString(settings);
        FunctionRuntimeContext runtime = new FunctionRuntimeContext(
                new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"),
                Map.of("NAMESPACE", "testns", "BUCKETNAME", "custom-bucket",
                        DataSourceSettings.CONFIG_KEY, json));
        new FunctionConfigurer().configure(runtime);
        Probe probe = (Probe) runtime.getInvokeInstance().orElseThrow();
        assertEquals("OK", runtime.getMethod().getTargetMethod().invoke(probe, ""));
        assertEquals("testns/custom-bucket", probe.bucket);
        assertEquals(List.of(URI.create(CSV.url())), probe.stub.requests);
        assertEquals(List.of(CSV.objectName()), probe.stub.writes);
        OOSResourceMetadata metadata = probe.stub.metadata.get(CSV.objectName());
        assertEquals("resource-2022", metadata.getMetadata().get("resource-id"));
        assertEquals(CSV.url(), metadata.getMetadata().get("source-url"));
        assertEquals("MRN", metadata.getMetadata().get("datasource-id"));
    }

    @Test
    public void sourceSwitchDoesNotReuseValidatorAndArchivesPreviousContents() {
        Stub stub = new Stub();
        SourceObjectPublisher publisher = new SourceObjectPublisher(stub.http, stub.storage);
        publisher.publish(settings("resource-2022", CSV));
        publisher.publish(settings("resource-2022", CSV));
        assertEquals("\"validator\"", stub.validators.get(1));
        DataSourceSettings.Input next = new DataSourceSettings.Input(
                "https://example.invalid/new.csv", CSV.objectName(), CSV.contentType());
        publisher.publish(settings("resource-2022", next));
        assertNull(stub.validators.get(2));
        publisher.publish(settings("resource-2023", next));
        assertNull(stub.validators.get(3));
        assertTrue(stub.writes.stream().anyMatch(name -> name.startsWith("custom-counts_")));
        assertEquals("resource-2023", stub.metadata.get(CSV.objectName()).getMetadata().get("resource-id"));
    }

    @Test
    public void legacyObjectsWithoutSourceIdentityAreRefreshedOnce() {
        Stub stub = new Stub();
        OOSResourceMetadata metadata = new OOSResourceMetadata();
        metadata.setETag("old");
        metadata.setLastModified(LocalDate.of(2026, 10, 1));
        stub.metadata.put(CSV.objectName(), metadata);
        stub.contents.put(CSV.objectName(), new byte[]{1});
        SourceObjectPublisher publisher = new SourceObjectPublisher(stub.http, stub.storage);
        publisher.publish(settings("current", CSV));
        assertNull(stub.validators.get(0));
        assertEquals("custom-counts_20261001.csv", stub.writes.get(0));
        publisher.publish(settings("current", CSV));
        assertEquals("\"validator\"", stub.validators.get(1));
    }

    @Test
    public void unchangedResponseDoesNotArchiveOrUpload() {
        Stub stub = new Stub();
        SourceObjectPublisher publisher = new SourceObjectPublisher(stub.http, stub.storage);
        publisher.publish(settings("current", CSV));
        stub.writes.clear();
        stub.unchanged = true;
        publisher.publish(settings("current", CSV));
        assertTrue(stub.writes.isEmpty());
    }

    @Test
    public void unchangedResponseRequiresAMatchingStoredSource() {
        Stub stub = new Stub();
        stub.unchanged = true;
        try {
            new SourceObjectPublisher(stub.http, stub.storage).publish(settings("current", CSV));
            fail("An unchanged response cannot bootstrap a missing source");
        } catch (UncheckedIOException expected) {
            assertTrue(stub.writes.isEmpty());
        }
    }

    @Test
    public void companionFailureStopsBeforeCsvAndLeavesPreviousCompanionReadable() {
        Stub stub = new Stub();
        DataSourceSettings.Input geometry = new DataSourceSettings.Input(
                "https://example.invalid/geometry.json", "geometry.json", "application/json");
        DataSourceSettings settings = settings("current", geometry, CSV);
        SourceObjectPublisher publisher = new SourceObjectPublisher(stub.http, stub.storage);
        publisher.publish(settings);
        byte[] original = stub.contents.get(geometry.objectName());
        stub.requests.clear();
        stub.failArchive = true;
        try {
            publisher.publish(settings);
            fail("Expected archive failure");
        } catch (UncheckedIOException expected) {
            assertTrue(expected.getMessage().contains(geometry.objectName()));
        }
        assertEquals(List.of(URI.create(geometry.url())), stub.requests);
        assertArrayEquals(original, stub.contents.get(geometry.objectName()));
    }

    @Test
    public void invalidInputsPreventStorageConfigurationAndDownloads() throws Exception {
        for (String json : List.of(
                new ObjectMapper().writeValueAsString(settings("current", CSV)).replace("MRN", "WRONG"),
                "{\"dataSourceId\":\"WRONG\",\"resourceId\":\"x\",\"inputs\":[]}",
                "{\"dataSourceId\":\"MRN\",\"resourceId\":\"x\",\"inputs\":[]}",
                "{\"dataSourceId\":\"MRN\",\"resourceId\":\"x\",\"inputs\":["
                        + "{\"url\":\"file:///tmp/counts.csv\",\"objectName\":\"x.csv\",\"contentType\":\"text/csv\"}]}")) {
            Probe probe = new Probe();
            FunctionRuntimeContext runtime = new FunctionRuntimeContext(
                    new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"),
                    Map.of(DataSourceSettings.CONFIG_KEY, json));
            try {
                probe.config(runtime);
                fail("Expected invalid configuration");
            } catch (IllegalArgumentException expected) {
                assertNull(probe.bucket);
                assertTrue(probe.stub.requests.isEmpty());
            }
        }
        try {
            settings("current", CSV, CSV);
            fail("Expected duplicate input rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate"));
        }
    }

    private static DataSourceSettings settings(String resourceId, DataSourceSettings.Input... inputs) {
        return new DataSourceSettings("MRN", resourceId, List.of(inputs));
    }

    public static class Probe extends org.traficinfo.dsfunctions.mrn.DataSourceFunction {
        final Stub stub = new Stub();
        String bucket;
        @Override protected HttpClient createHttpClient() { return stub.http; }
        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String ns, String name) {
            bucket = ns + "/" + name;
            return stub.storage;
        }
    }

    private static class Stub {
        final Map<String, byte[]> contents = new HashMap<>();
        final Map<String, OOSResourceMetadata> metadata = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        final List<URI> requests = new ArrayList<>();
        final List<String> validators = new ArrayList<>();
        boolean unchanged;
        boolean failArchive;
        final HttpClient http = new HttpClient() {
            @Override public EntityBody get(URI uri, String etag) {
                requests.add(uri);
                validators.add(etag);
                return new EntityBody("validator", null, unchanged ? null : new ByteArrayInputStream(
                        "new content".getBytes(StandardCharsets.UTF_8)));
            }
        };
        final ObjectStoreClient storage = new ObjectStoreClient(null, "namespace", "bucket") {
            @Override public OOSResourceMetadata getResourceMetadata(String name) { return metadata.get(name); }
            @Override public InputStream getResource(String name) { return new ByteArrayInputStream(contents.get(name)); }
            @Override public void putResource(String name, String type, OOSResourceMetadata meta,
                                              InputStream stream) throws IOException {
                if (failArchive && name.startsWith("geometry_")) throw new IOException("Archive failure");
                contents.put(name, stream.readAllBytes());
                metadata.put(name, meta);
                writes.add(name);
            }
        };
    }
}
