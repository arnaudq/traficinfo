package org.traficinfo.dsfunctions;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AccidentsConfigTest {
    @Test public void loadsBundledSourcesAndRejectsAmbiguousOrInvalidPublicationTargets() throws Exception {
        assertEquals(java.util.List.of("ONISR_DPT30", "ONISR_DPT34", "ONISR_DPT35", "ONISR_DPT76"),
                AccidentsConfig.bundled().stream().map(AccidentsConfig.Source::id).toList());
        for (String input : new String[]{"null", "{}", "[null]", "[] {}",
                "[{\"id\":\"A\",\"sourceBucket\":\"bucket\"},{\"id\":\"A\",\"sourceBucket\":\"other\"}]",
                "[{\"id\":\"../A\",\"sourceBucket\":\"bucket\"}]",
                "[{\"id\":\"A\",\"sourceBucket\":\"bad/bucket\"}]"}) {
            try {
                AccidentsConfig.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
                fail("Expected invalid configuration to fail: " + input);
            } catch (IOException expected) { }
        }
        var sources = AccidentsConfig.read(new ByteArrayInputStream(
                "[{\"id\":\"CITY_A\",\"sourceBucket\":\"private-accidents\"}]".getBytes(StandardCharsets.UTF_8)));
        assertEquals("CITY_A", sources.get(0).id());
        assertEquals("private-accidents", sources.get(0).sourceBucket());
    }
}
