/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.parsers.onisr;

import org.junit.Test;
import org.traficinfo.parsers.AccidentParticipant.InjurySeverity;
import org.traficinfo.parsers.AccidentParticipant.Role;
import org.traficinfo.parsers.AccidentParticipant.Sex;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.Assert.*;

public class ONISRAccidentParticipantParserTest {
    private static final String HEADER = "Num_Acc;id_usager;id_vehicule;catu;grav;an_nais;sexe\n";
    private static final String ROW = "202500000001;001 234;005\u00a0678;1;2;2000;2\n";
    private static final Set<String> SELECTED = Set.of("202500000001");
    private final ONISRAccidentParticipantParser parser = new ONISRAccidentParticipantParser();

    @Test
    public void parsesOnlySelectedAccidentsAndKeepsSexAndParticipantRelationships() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/ONISRUsagersSample.csv"), StandardCharsets.UTF_8)) {
            var result = parser.parseParticipants(reader, Set.of("202500000001", "202500000002"), 2025);
            assertEquals(2, result.size());
            assertEquals(2, result.get("202500000001").size());
            var driver = result.get("202500000001").get(0);
            assertEquals("204248638", driver.getParticipantId());
            assertEquals("155975242", driver.getVehicleId());
            assertEquals(Role.DRIVER, driver.getRole());
            assertEquals(InjurySeverity.HOSPITALIZED, driver.getInjurySeverity());
            assertEquals(Integer.valueOf(2004), driver.getBirthYear());
            assertEquals(Sex.MALE, driver.getSex());
            assertEquals(Sex.FEMALE, result.get("202500000001").get(1).getSex());
            var pedestrian = result.get("202500000002").get(0);
            assertEquals(Role.PEDESTRIAN, pedestrian.getRole());
            assertEquals(InjurySeverity.LIGHTLY_INJURED, pedestrian.getInjurySeverity());
            assertEquals(Sex.UNKNOWN, pedestrian.getSex());
            assertNull(pedestrian.getBirthYear());
            assertEquals("155975243", pedestrian.getVehicleId());
        }
    }

    @Test
    public void skipsUnselectedRecordsBeforeWidthOrFieldValidationAndSupportsAnEmptySelection() throws Exception {
        String csv = HEADER + "202400000001;broken\n" + ROW;
        var result = parser.parseParticipants(new StringReader(csv), SELECTED, 2025);
        assertEquals(1, result.size());
        assertEquals("001234", result.get("202500000001").get(0).getParticipantId());
        assertTrue(parser.parseParticipants(new StringReader(csv), Set.of(), 2025).isEmpty());
    }

    @Test
    public void missingAndUnrecognizedCodesAreUnknownAndMissingBirthYearIsNull() throws Exception {
        for (String codes : new String[]{"0;0;0;0", "-1;-1;-1;-1", ".;N/A;;.", "9;9;2000;9"}) {
            var result = parser.parseParticipants(new StringReader(HEADER + "202500000001;123;;" + codes + "\n"), SELECTED, 2025);
            var participant = result.get("202500000001").get(0);
            assertEquals(Role.UNKNOWN, participant.getRole());
            assertEquals(InjurySeverity.UNKNOWN, participant.getInjurySeverity());
            assertEquals(Sex.UNKNOWN, participant.getSex());
            assertNull(participant.getVehicleId());
        }
    }

    @Test
    public void rejectsSelectedDuplicateIdsMalformedFieldsInvalidYearsAndHeaders() throws Exception {
        for (String csv : new String[]{"", HEADER.replace("sexe", "wrong") + ROW, HEADER.replace("sexe", "grav") + ROW,
                HEADER + ROW + ROW.replace("001 234", "001\u202f234"), HEADER + ROW.replace(";2000;", ";2026;"),
                HEADER + ROW.replace(";1;2;", ";bad;2;"), HEADER + ROW.replace("001 234", ""),
                HEADER + ROW.replace("005\u00a0678", "bad"), HEADER + ROW.stripTrailing() + ";extra\n"}) {
            try {
                parser.parseParticipants(new StringReader(csv), SELECTED, 2025);
                fail("Expected invalid selected usager to fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("usager"));
            }
        }
    }

    @Test
    public void acceptsBomAndLeavesCallerReaderOpen() throws Exception {
        StringReader reader = new StringReader("\uFEFF" + HEADER + ROW);
        assertEquals(1, parser.parseParticipants(reader, SELECTED, 2025).size());
        assertEquals(-1, reader.read());
        assertTrue(parser.parseParticipants(new StringReader(HEADER), SELECTED, 2025).isEmpty());
    }
}
