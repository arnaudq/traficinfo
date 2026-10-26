package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.*;

public class StorageContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void fixturesRoundTripWithTheExistingJacksonVersion() throws Exception {
        roundTrip("manifest.json", DataManifest.class);
        roundTrip("manifest-companion.json", DataManifest.class);
        roundTrip("import.json", ImportRecord.class);
        roundTrip("import-companion.json", ImportRecord.class);
        for (String file : List.of("comptage-2022.json", "comptage-2023.json",
                "comptage-2024.json")) {
            roundTrip(file, NormalizedComptage[].class);
        }
    }

    @Test
    public void manifestPreservesInactiveResourceYearsAndImmutableObjectReferences() throws Exception {
        DataManifest manifest = read("manifest.json", DataManifest.class);
        assertEquals("nim-2023-2024", manifest.activeResourceId());
        assertEquals(List.of(2023, 2024), manifest.resources().get(manifest.activeResourceId()).years());
        assertEquals("nim-2022", manifest.years().get(2022).resourceId());
        assertEquals("data/r0001/NIM_Comptage_2022.json", manifest.years().get(2022).object());
        assertEquals("data/r0002/NIM_Comptage_2024.json", manifest.years().get(2024).object());
        for (int year : manifest.years().keySet()) {
            assertEquals(sha256(bytes("comptage-" + year + ".json")),
                    manifest.years().get(year).sha256());
        }
    }

    @Test
    public void companionInputsHaveIndependentSchemaAndProvenance() throws Exception {
        DataManifest manifest = read("manifest-companion.json", DataManifest.class);
        ImportRecord provenance = read("import-companion.json", ImportRecord.class);
        var inputs = manifest.resources().get(manifest.activeResourceId()).inputs();
        assertEquals(inputs.keySet(), provenance.inputs().keySet());
        assertNotNull(inputs.get("counts").headers());
        assertNull(inputs.get("geometry").headers());
        assertNull(provenance.inputs().get("geometry").observedHeaders());
        assertNotEquals(provenance.inputs().get("counts").object(),
                provenance.inputs().get("geometry").object());
    }

    @Test
    public void unknownOrMissingEnvelopeVersionsAreRejected() throws Exception {
        for (int version : new int[]{0, 2}) {
            for (String[] example : new String[][]{
                    {"manifest.json", "manifestVersion", DataManifest.class.getName()},
                    {"manifest.json", "comptageSchemaVersion", DataManifest.class.getName()},
                    {"import.json", "importVersion", ImportRecord.class.getName()}}) {
                ObjectNode tree = (ObjectNode) JSON.readTree(bytes(example[0]));
                if (version == 0) tree.remove(example[1]);
                else tree.put(example[1], version);
                try {
                    JSON.treeToValue(tree, Class.forName(example[2]));
                    fail("Accepted unsupported " + example[1]);
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("Unsupported storage contract version"));
                }
            }
        }
    }

    @Test
    public void normalizedDataRetainsZeroAndUnknownWithoutOsmEnrichment() throws Exception {
        ObjectNode tree = (ObjectNode) JSON.readTree(bytes("comptage-2024.json")).get(0);
        tree.put("moyenneJourAnnuelle", 0);
        NormalizedComptage value = JSON.treeToValue(tree, NormalizedComptage.class);
        assertEquals(Integer.valueOf(0), value.moyenneJourAnnuelle());
        assertNull(value.v85Speed());
        JsonNode serialized = JSON.valueToTree(value);
        assertFalse(serialized.has("osmWayId"));
        assertFalse(serialized.has("maximumSpeed"));
        assertEquals("Point", serialized.get("geometry").get("type").asText());
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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
