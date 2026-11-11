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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class NIMSiteRegistryTest {
  private static final String HEADER = "id_camera;code INSEE;commune;date;tranche;position;coordonnées géographique;nbPassage\n";

  @Test
  public void discoversSitesDeterministicallyAndKeepsReturningCameraAtItsOriginalSite() throws Exception {
    String a = row("2", "2021-01-01", 0, "Original", "43.8, 4.4", 10);
    String renamed = row("2", "2021-02-01", 0, "Renamed", "43.8001, 4.4", 20);
    String b = row("2", "2021-03-01", 0, "Elsewhere", "44, 4.4", 30);
    String returned = row("2", "2021-04-01", 0, "Original", "43.8, 4.4", 40);
    var registry = discover(empty(), a + renamed + b + returned);
    assertEquals(2, registry.sites().size());
    var first = registry.find("2", 43.8, 4.4);
    assertEquals("NIM_2_S01", first.codeCentrale());
    assertEquals(43.8, first.latitude(), 0);
    assertEquals("2021-01-01", first.firstSeen());
    assertEquals("2021-04-01", first.lastSeen());
    assertEquals(List.of("Original", "Renamed"), first.positions());
    assertTrue(first.cameraMoved());
    assertTrue(registry.find("2", 44.0, 4.4).cameraMoved());
    assertEquals("NIM_2_S02", registry.find("2", 44.0, 4.4).codeCentrale());
    assertArrayEquals(NIMSiteRegistry.serialize(registry),
        NIMSiteRegistry.serialize(discover(empty(), returned + b + renamed + a)));
    assertArrayEquals(NIMSiteRegistry.serialize(registry),
        NIMSiteRegistry.serialize(discover(registry, a + renamed + b + returned)));
  }

  @Test
  public void updateKeepsIdsAndReferenceCoordinatesWhenOlderHistoryOrNewCamerasArrive() throws Exception {
    var initial = discover(empty(), row("2", "2021-03-01", 0, "First imported", "43.8, 4.4", 1));
    var updated = discover(initial, row("2", "2020-01-01", 0, "Older site", "44, 4.4", 1)
        + row("900", "2022-01-01", 0, "New camera", "43.8, 4.4", 1)
        + row("2", "2023-01-01", 0, "Correction", "43.8001, 4.4", 1));
    assertEquals("NIM_2_S01", updated.find("2", 43.8, 4.4).codeCentrale());
    assertEquals(43.8, updated.find("2", 43.8001, 4.4).latitude(), 0);
    assertEquals("NIM_2_S02", updated.find("2", 44.0, 4.4).codeCentrale());
    assertEquals("NIM_900_S01", updated.find("900", 43.8, 4.4).codeCentrale());
    assertFalse(updated.find("900", 43.8, 4.4).cameraMoved());
  }

  @Test
  public void splitSitesNeverCompleteEachOthersDaysOrConflictOnTheirCounts() throws Exception {
    StringBuilder csv = new StringBuilder();
    for (int hour = 0; hour < 24; hour++) csv.append(row("2", "2021-01-01", hour,
        "Site", hour < 12 ? "43.8, 4.4" : "44, 4.4", hour < 12 ? 10 : 20));
    // Same camera, date and hour at different sites remain separate measurements.
    csv.append(row("2", "2021-01-01", 0, "Site", "44, 4.4", 50));
    csv.append(row("2", "2022-01-01", 0, "Site", "43.8, 4.4", 5));
    var registry = discover(empty(), csv.toString());
    var parser = new NIMComptageParser(registry);
    var counts = parser.parseComptages(new StringReader(HEADER + csv));
    assertEquals(3, counts.size());
    assertEquals("NIM_2_S01", counts.get(0).getCodeCentrale());
    assertEquals(120, counts.get(0).getTotalPassages());
    assertEquals("NIM_2_S01", counts.get(1).getCodeCentrale());
    assertEquals(2022, counts.get(1).getAnnee());
    assertEquals("NIM_2_S02", counts.get(2).getCodeCentrale());
    assertEquals(290, counts.get(2).getTotalPassages());
    for (var count : counts) {
      assertEquals(0, count.getConflictingHours());
      assertEquals(0, count.getCompleteDays());
      assertNull(count.getDailyAverage());
    }
    var shuffled = new ArrayList<>(List.of(csv.toString().split("\n")));
    Collections.shuffle(shuffled, new java.util.Random(42));
    assertEquals(counts, parser.parseComptages(new StringReader(HEADER + String.join("\n", shuffled))));
  }

  @Test
  public void reportsAllUnknownCamerasAndLocationsWithoutMutatingRegistry() throws Exception {
    String known = row("2", "2021-01-01", 0, "Site", "43.8, 4.4", 1);
    var registry = discover(empty(), known);
    byte[] original = NIMSiteRegistry.serialize(registry);
    try {
      new NIMComptageParser(registry).parseComptages(new StringReader(HEADER + known
          + row("2", "2021-01-02", 0, "Moved", "44, 4.4", 1)
          + row("900", "2021-01-01", 0, "New", "43.8, 4.4", 1)));
      fail("Unknown sites must stop parsing");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("2 camera/location variants"));
      assertTrue(expected.getMessage().contains("camera=2"));
      assertTrue(expected.getMessage().contains("camera=900"));
      assertTrue(expected.getMessage().contains("NIMSiteRegistryUpdater"));
    }
    assertArrayEquals(original, NIMSiteRegistry.serialize(registry));
  }

  @Test
  public void fixedAnchorsPreventChainedCoordinateDriftAndRejectAmbiguousAssignments() throws Exception {
    var registry = discover(empty(), row("2", "2021-01-01", 0, "A", "43.8, 4.4", 1)
        + row("2", "2021-02-01", 0, "B", "43.8004, 4.4", 1));
    assertEquals(1, registry.sites().size());
    try {
      discover(registry, row("2", "2021-03-01", 0, "C", "43.8008, 4.4", 1)
          + row("2", "2021-04-01", 0, "Middle", "43.8004, 4.4", 1));
      fail("The middle point matches both fixed anchors");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("Ambiguous"));
    }
  }

  @Test
  public void updatesExistingFileIdempotentlyAndPreservesItOnInvalidInput() throws Exception {
    Path directory = Files.createTempDirectory("nim-sites-test-");
    Path input = directory.resolve("source.csv");
    Path file = directory.resolve("nim-sites.json");
    try {
      Files.writeString(input, "\uFEFF" + HEADER + row("2", "2021-01-01", 0, "Site", "43.8, 4.4", 1));
      NIMSiteRegistryUpdater.update(input, file);
      byte[] initial = Files.readAllBytes(file);
      assertEquals(1, NIMSiteRegistry.read(file).sites().size());
      NIMSiteRegistryUpdater.update(input, file);
      assertArrayEquals(initial, Files.readAllBytes(file));
      Files.writeString(input, HEADER + row("2", "2021-01-01", 0, "Site", "NaN, 4.4", 1));
      try { NIMSiteRegistryUpdater.update(input, file); fail("Invalid CSV"); }
      catch (IOException expected) { assertTrue(expected.getMessage().contains("row 2")); }
      assertArrayEquals(initial, Files.readAllBytes(file));
      Files.writeString(input, HEADER + row("900", "2021-01-01", 0, "Missing", "", 1));
      try { NIMSiteRegistryUpdater.update(input, file); fail("Missing coordinates"); }
      catch (IOException expected) { assertTrue(expected.getMessage().contains("Missing coordinates")); }
      assertArrayEquals(initial, Files.readAllBytes(file));
    } finally {
      Files.deleteIfExists(input); Files.deleteIfExists(file); Files.deleteIfExists(directory);
    }
  }

  @Test
  public void invalidRegistryCannotBeOverwrittenAnd1970RowsAreIgnored() throws Exception {
    Path directory = Files.createTempDirectory("nim-sites-invalid-");
    Path input = directory.resolve("source.csv");
    Path file = directory.resolve("nim-sites.json");
    try {
      Files.writeString(input, HEADER + row("2", "1970-01-01", 0, "Ignored", "invalid", 1));
      assertTrue(NIMSiteRegistryUpdater.update(input, file).sites().isEmpty());
      Files.writeString(file, "[{}]");
      try { NIMSiteRegistryUpdater.update(input, file); fail("Invalid registry"); }
      catch (IOException expected) { assertEquals("[{}]", Files.readString(file)); }
    } finally {
      Files.deleteIfExists(input); Files.deleteIfExists(file); Files.deleteIfExists(directory);
    }
  }

  @Test
  public void missingCoordinatesUseTheSoleSiteDiscoveredElsewhereInTheCsv() throws Exception {
    String missing = row("68", "2022-03-31", 0, "Earlier label", "", 10);
    String known = row("68", "2022-04-04", 0, "Later label", "43.8, 4.4", 20);
    var registry = discover(empty(), missing + known);
    assertEquals(1, registry.sites().size());
    assertEquals("2022-03-31", registry.sites().get(0).firstSeen());
    assertEquals(List.of("Earlier label", "Later label"), registry.sites().get(0).positions());
    assertArrayEquals(NIMSiteRegistry.serialize(registry), NIMSiteRegistry.serialize(discover(empty(), known + missing)));
    var counts = new NIMComptageParser(registry).parseComptages(new StringReader(HEADER + missing + known));
    assertEquals(1, counts.size());
    assertEquals(30, counts.get(0).getTotalPassages());
    assertEquals(Double.valueOf(43.8), counts.get(0).getLatitude());
    assertEquals("NIM_68_S01", counts.get(0).getCodeCentrale());
    assertEquals("2022-03-31", discover(registry, missing).sites().get(0).firstSeen());
  }

  @Test
  public void missingCoordinatesRemainAmbiguousWhenTheCsvDiscoversAnotherSite() throws Exception {
    var initial = discover(empty(), row("68", "2022-01-01", 0, "Original", "43.8, 4.4", 10));
    byte[] before = NIMSiteRegistry.serialize(initial);
    try {
      discover(initial, row("68", "2022-02-01", 0, "Unknown location", "", 10)
          + row("68", "2022-03-01", 0, "New site", "44, 4.4", 10));
      fail("Missing coordinates cannot be inferred after a second site is discovered");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("Ambiguous NIM site"));
    }
    assertArrayEquals(before, NIMSiteRegistry.serialize(initial));
  }

  private static NIMSiteRegistry empty() throws IOException { return new NIMSiteRegistry(List.of()); }
  private static NIMSiteRegistry discover(NIMSiteRegistry registry, String rows) throws IOException {
    return NIMSiteRegistryUpdater.discover(registry, new StringReader(HEADER + rows));
  }
  private static String row(String camera, String date, int hour, String label, String coordinates, int count) {
    return camera + ";30125;Garons;" + date + ";" + String.format(java.util.Locale.ROOT, "%02d:00 - %02d:00", hour, hour + 1)
        + ";" + label + ";" + coordinates + ";" + count + "\n";
  }
}
