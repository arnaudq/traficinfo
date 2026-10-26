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
        roundTrip("proposal.json", ReviewProposal.class);
        roundTrip("proposal-initial.json", ReviewProposal.class);
        roundTrip("decision.json", ReviewDecision.class);
        roundTrip("review-index.json", ReviewIndex.class);
        roundTrip("diff.json", ReviewDiff.class);
        for (String file : List.of("comptage-2022.json", "comptage-2023.json",
                "comptage-2024.json", "candidate-2024.json")) {
            roundTrip(file, NormalizedComptage[].class);
        }
    }

    @Test
    public void manifestPreservesInactiveResourceYearsAndImmutableObjectReferences() throws Exception {
        DataManifest manifest = read("manifest.json", DataManifest.class);
        assertEquals("nim-2023-2024", manifest.activeResourceId());
        assertEquals(List.of(2023, 2024), manifest.resources().get(manifest.activeResourceId()).years());
        assertEquals("nim-2022", manifest.acceptedYears().get(2022).resourceId());
        assertEquals("accepted/r0001/NIM_Comptage_2022.json", manifest.acceptedYears().get(2022).object());
        assertEquals("accepted/r0002/NIM_Comptage_2024.json", manifest.acceptedYears().get(2024).object());
        for (int year : manifest.acceptedYears().keySet()) {
            assertEquals(sha256(bytes("comptage-" + year + ".json")),
                    manifest.acceptedYears().get(year).sha256());
        }
    }

    @Test
    public void approvalBindsProposalBaselineAndCandidateWithoutPublishingIt() throws Exception {
        ReviewProposal proposal = read("proposal.json", ReviewProposal.class);
        ReviewDecision decision = read("decision.json", ReviewDecision.class);
        ReviewIndex.Entry entry = read("review-index.json", ReviewIndex.class).reviews().get(0);
        DataManifest manifest = read("manifest.json", DataManifest.class);
        assertEquals(sha256(bytes("proposal.json")), decision.proposalSha256());
        assertEquals(proposal.reviewId(), decision.reviewId());
        assertEquals(manifest.revision(), decision.baseManifestRevision());
        assertEquals(proposal.baseManifestRevision(), decision.baseManifestRevision());
        assertEquals(sha256(bytes("candidate-2024.json")), proposal.changes().get(2024).candidateSha256());
        assertEquals(manifest.acceptedYears().get(2024).object(), proposal.changes().get(2024).baselineObject());
        assertEquals(ReviewIndex.Status.APPROVED, entry.status());
        assertNull(entry.publishedRevision());
        ReviewDiff.Change change = read("diff.json", ReviewDiff.class).changes().get(2024).get(0);
        assertEquals(Integer.valueOf(1000), change.before().moyenneJourAnnuelle());
        assertEquals(Integer.valueOf(1100), change.after().moyenneJourAnnuelle());
        assertEquals(List.of("moyenneJourAnnuelle"), change.changedFields());
    }

    @Test
    public void companionInputsHaveIndependentSchemaAndProvenance() throws Exception {
        DataManifest manifest = read("manifest-companion.json", DataManifest.class);
        ImportRecord provenance = read("import-companion.json", ImportRecord.class);
        var inputs = manifest.resources().get(manifest.activeResourceId()).inputs();
        assertEquals(inputs.keySet(), provenance.inputs().keySet());
        assertNotNull(inputs.get("counts").acceptedSchema());
        assertNull(inputs.get("geometry").acceptedSchema());
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
                    {"import.json", "importVersion", ImportRecord.class.getName()},
                    {"proposal.json", "reviewVersion", ReviewProposal.class.getName()},
                    {"decision.json", "decisionVersion", ReviewDecision.class.getName()},
                    {"review-index.json", "indexVersion", ReviewIndex.class.getName()},
                    {"diff.json", "diffVersion", ReviewDiff.class.getName()}}) {
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
    public void initialImportHasNoAcceptedBaselineOrHeaders() throws Exception {
        ReviewProposal proposal = read("proposal-initial.json", ReviewProposal.class);
        assertNull(proposal.baseManifestRevision());
        assertNull(proposal.changes().get(2024).baselineObject());
        assertNull(proposal.schemaChanges().get("counts").acceptedHeaders());
        assertEquals(List.of(ReviewProposal.Reason.INITIAL_IMPORT), proposal.reasons());
    }

    @Test
    public void diffCannotContainAnEmptyChange() throws Exception {
        ObjectNode tree = (ObjectNode) JSON.readTree(bytes("diff.json"));
        ObjectNode change = (ObjectNode) tree.get("changes").get("2024").get(0);
        change.putNull("before");
        change.putNull("after");
        try {
            JSON.treeToValue(tree, ReviewDiff.class);
            fail("Accepted a change with no before or after");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("A change requires before or after"));
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
