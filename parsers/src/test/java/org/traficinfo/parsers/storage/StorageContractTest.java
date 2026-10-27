package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.*;

public class StorageContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void fixturesRoundTripWithTheExistingJacksonVersion() throws Exception {
        roundTrip("manifest.json", DataManifest.class);
        roundTrip("manifest-companion.json", DataManifest.class);
    }

    @Test
    public void manifestPreservesInactiveResourceYearsAndImmutableObjectReferences() throws Exception {
        DataManifest manifest = read("manifest.json", DataManifest.class);
        assertEquals("nim-2023-2024", manifest.activeResourceId());
        assertEquals(List.of(2023, 2024), manifest.resources().get(manifest.activeResourceId()).years());
        assertEquals("nim-2022", manifest.years().get(2022).resourceId());
        assertEquals("data/r0001/NIM_Comptage_2022.json", manifest.years().get(2022).object());
        assertEquals("data/r0002/NIM_Comptage_2024.json", manifest.years().get(2024).object());
    }

    @Test
    public void companionInputsHaveIndependentHeaders() throws Exception {
        DataManifest manifest = read("manifest-companion.json", DataManifest.class);
        var inputs = manifest.resources().get(manifest.activeResourceId()).inputs();
        assertNotNull(inputs.get("counts").headers());
        assertNull(inputs.get("geometry").headers());
        assertEquals("CSV", inputs.get("counts").format());
        assertEquals("GEOJSON", inputs.get("geometry").format());
        assertNotEquals(inputs.get("counts").url(), inputs.get("geometry").url());
    }

    @Test
    public void unknownOrMissingManifestVersionsAreRejected() throws Exception {
        for (int version : new int[]{0, 2}) {
            ObjectNode tree = (ObjectNode) JSON.readTree(bytes("manifest.json"));
            if (version == 0) tree.remove("manifestVersion");
            else tree.put("manifestVersion", version);
            try {
                JSON.treeToValue(tree, DataManifest.class);
                fail("Accepted unsupported manifest version");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("Unsupported manifest version"));
            }
        }
    }

    private static <T> void roundTrip(String file, Class<T> type) throws Exception {
        byte[] original = bytes(file);
        T value = JSON.readValue(original, type);
        assertEquals(file, JSON.readTree(original), JSON.readTree(JSON.writeValueAsBytes(value)));
    }

    private static <T> T read(String file, Class<T> type) throws IOException {
        return JSON.readValue(bytes(file), type);
    }

    private static byte[] bytes(String file) throws IOException {
        try (InputStream input = StorageContractTest.class.getResourceAsStream("/storage/" + file)) {
            if (input == null) throw new IOException("Missing fixture " + file);
            return input.readAllBytes();
        }
    }
}
