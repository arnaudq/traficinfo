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

package org.traficinfo.parsers.nim;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;

import static org.junit.Assert.*;
import static org.traficinfo.parsers.nim.NIMLocationAuditProgram.Category.*;

public class NIMLocationAuditProgramTest {
  private static final String HEADER = "id_camera;code INSEE;commune;date;tranche;position;coordonnées géographique;nbPassage\n";

  @Test
  public void distinguishesToleranceRelocationAndLabelChangesAcrossUnorderedFiles() throws Exception {
    var audit = new NIMLocationAuditProgram.Audit();
    audit.read(new StringReader("\uFEFF" + HEADER
        + row("1", "2021-08-06", "Site", "43.8001, 4.4")
        + row("2", "2021-08-06", "New site", "43.75425, 4.43518")
        + row("3", "2022-01-01", "Renamed", "43.8, 4.4")), "new.csv");
    audit.read(new StringReader(HEADER
        + row("1", "2021-08-06", "Site", "43.8, 4.4")
        + row("1", "2021-08-06", "Site", "43.8, 4.4")
        + row("2", "2021-08-06", "Old site", "43.757583, 4.432637")
        + row("2", "2021-08-05", "Old site", "43.757583, 4.432637")
        + row("3", "2021-01-01", "Original", "43.8, 4.4")), "old.csv");
    var changes = audit.changes();
    assertEquals(3, changes.size());
    assertEquals(WITHIN_TOLERANCE, changes.get(0).category());
    assertFalse(changes.get(0).conflicts());
    assertEquals(1, changes.get(0).sharedDates().size());
    assertEquals(RELOCATION_CANDIDATE, changes.get(1).category());
    assertTrue(changes.get(1).conflicts());
    assertEquals(423, changes.get(1).distance(), 2);
    assertEquals(LocalDate.parse("2021-08-05"), changes.get(1).before().first());
    assertTrue(changes.get(1).sharedDates().contains(LocalDate.parse("2021-08-06")));
    assertEquals(METADATA_OR_COORDINATE_PRESENCE_CHANGE, changes.get(2).category());
    assertTrue(changes.get(2).sharedDates().isEmpty());
    assertTrue(changes.get(2).metadataChanged());
    assertFalse(changes.get(2).conflicts());
  }

  @Test
  public void ignores1970AndFlagsMissingVersusPresentCoordinates() throws Exception {
    var audit = new NIMLocationAuditProgram.Audit();
    audit.read(new StringReader(HEADER + row("99", "1970-01-01", "Ignored", "invalid")
        + row("1", "2021-01-01", "Site", "") + row("1", "2021-01-01", "Site", "43.8, 4.4")), "sample.csv");
    assertEquals(3, audit.rows);
    assertEquals(1, audit.ignored1970);
    assertEquals(1, audit.cameras.size());
    assertEquals(METADATA_OR_COORDINATE_PRESENCE_CHANGE, audit.changes().get(0).category());
    assertNull(audit.changes().get(0).distance());
  }

  @Test
  public void reportsSourceFileAndRowForMalformedCoordinates() throws Exception {
    try {
      new NIMLocationAuditProgram.Audit().read(new StringReader(HEADER
          + row("1", "2021-01-01", "Site", "NaN, 4")), "broken.csv");
      fail("Expected malformed input to fail");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("broken.csv row 2"));
    }
  }

  private static String row(String camera, String date, String position, String coordinates) {
    return camera + ";30125;Garons;" + date + ";00:00 - 01:00;" + position + ";" + coordinates + ";1\n";
  }
}
