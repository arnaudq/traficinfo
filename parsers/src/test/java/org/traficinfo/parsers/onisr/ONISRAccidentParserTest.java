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

import org.geojson.Point;
import org.junit.Test;
import org.traficinfo.parsers.Accident;
import org.traficinfo.parsers.GeographicRecord;
import org.traficinfo.parsers.dpt30.DPT30Comptage;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ONISRAccidentParserTest {
  private static final String HEADER = "Num_Acc;jour;mois;an;hrmn;lum;dep;com;agg;int;atm;col;adr;lat;long\n";
  private static final String ROW = "202500000001;17;08;2025;09:30;1;34;34172;2;1;1;6;Rue de l'École;43,6;3,8\n";
  private static final String OLD_HEADER = "Num_Acc,an,mois,jour,hrmn,lum,agg,int,atm,col,com,adr,gps,lat,long,dep\n";

  @Test
  public void parsesModernCharacteristicsAndFiltersDepartments() throws Exception {
    ONISRAccidentParser parser = new ONISRAccidentParser(Set.of("30", "34", "76"));
    List<ONISRAccident> rows;
    try (var reader = new InputStreamReader(getClass().getResourceAsStream("/ONISRCaractSample.csv"), StandardCharsets.UTF_8)) {
      rows = parser.parseAccidents(reader);
    }
    assertEquals(3, rows.size());
    Accident first = rows.get(0);
    assertEquals("202500000112", first.getAccidentId());
    assertEquals(LocalDate.of(2025, 8, 17), first.getDate());
    assertEquals(LocalTime.of(9, 30), first.getTime());
    assertEquals("34", first.getRegionalId());
    assertEquals("34172", first.getMunicipalityId());
    assertEquals("ERMENGARDE (IMPASSE)", first.getAddress());
    Point point = (Point) first.getGeometry();
    assertEquals(3.88108, point.getCoordinates().getLongitude(), 0.00000001);
    assertEquals(43.6346, point.getCoordinates().getLatitude(), 0.00000001);
    assertNull(first.getRoadReference());
    assertNull(first.getOsmWayId());
    first.setOsmWayId(123L);
    assertEquals(Long.valueOf(123), first.getOsmWayId());
    assertEquals(Integer.valueOf(1), rows.get(0).getLighting());
    assertEquals(Integer.valueOf(2), rows.get(0).getBuiltUpArea());
    assertEquals(Integer.valueOf(1), rows.get(0).getIntersection());
    assertEquals(Integer.valueOf(1), rows.get(0).getAtmosphericConditions());
    assertEquals(Integer.valueOf(6), rows.get(0).getCollisionType());
    assertEquals("43,63460000", rows.get(0).getSourceFields().get("lat"));
    assertEquals(LocalTime.MIDNIGHT, rows.get(1).getTime());
    assertEquals("Rue de l'École; près du pont", rows.get(1).getAddress());
    assertNull(rows.get(2).getGeometry());
    assertNull(rows.get(2).getTime());
    assertNull(rows.get(2).getAddress());
    assertNull(rows.get(2).getLighting());
    assertNull(rows.get(2).getAtmosphericConditions());
  }

  @Test
  public void acceptsBomUnknownColumnsAndCallerOwnedReaders() throws Exception {
    StringReader reader = new StringReader("\uFEFF" + HEADER.stripTrailing() + ";future\n"
        + ROW.stripTrailing() + ";retained\n");
    ONISRAccident row = new ONISRAccidentParser().parseAccidents(reader).get(0);
    assertEquals("retained", row.getSourceFields().get("future"));
    assertEquals(-1, reader.read()); // An open StringReader remains readable at EOF.
    try {
      row.getSourceFields().put("new", "value");
      fail("Raw source fields must be immutable");
    } catch (UnsupportedOperationException expected) {
      // Preserve the parser's source snapshot.
    }
  }

  @Test
  public void parsesHistoricalYearTimeDepartmentsCommunesAndCoordinates() throws Exception {
    String csv = OLD_HEADER
        + "200500000001,5,1,20,5,3,1,1,1,6,367,Rue de l'École,M,4950800,0109800,760\n"
        + "201800000001,18,1,24,1505,1,1,4,1,1,005,route,M,5055737,0294992,590\n"
        + "201800000002,18,1,24,0,1,1,4,1,1,189,,M,0000000,0000000,300\n"
        + "201800000003,18,1,24,1200,1,1,4,1,1,130,,M,4190800,0881600,201\n";
    List<ONISRAccident> rows = new ONISRAccidentParser().parseAccidents(new StringReader(csv));
    assertEquals(LocalDate.of(2005, 1, 20), rows.get(0).getDate());
    assertEquals(LocalTime.of(0, 5), rows.get(0).getTime());
    assertEquals("76", rows.get(0).getRegionalId());
    assertEquals("76367", rows.get(0).getMunicipalityId());
    assertEquals(49.508, rows.get(0).getLatitude(), 0.000001);
    assertEquals(1.098, rows.get(0).getLongitude(), 0.000001);
    assertEquals("760", rows.get(0).getSourceFields().get("dep"));
    assertEquals(50.55737, rows.get(1).getLatitude(), 0.000001);
    assertNull(rows.get(2).getGeometry());
    assertEquals(LocalTime.MIDNIGHT, rows.get(2).getTime());
    assertEquals("2A130", rows.get(3).getMunicipalityId());
    assertEquals(1, new ONISRAccidentParser(Set.of("30")).parseAccidents(new StringReader(csv)).size());
  }

  @Test
  public void historicalOverseasCoordinatesUseTheirHemisphere() throws Exception {
    String csv = OLD_HEADER
        + "201800000001,18,1,24,1200,1,1,1,1,1,224,,A,1465208,6102822,972\n"
        + "201800000002,18,1,24,1200,1,1,1,1,1,407,,R,2092287,5534416,974\n";
    List<ONISRAccident> rows = new ONISRAccidentParser().parseAccidents(new StringReader(csv));
    assertEquals(-61.02822, rows.get(0).getLongitude(), 0.000001);
    assertEquals("97224", rows.get(0).getMunicipalityId());
    assertEquals(-20.92287, rows.get(1).getLatitude(), 0.000001);
  }

  @Test
  public void readsPublishedFileEncodings() throws Exception {
    File file = File.createTempFile("onisr-", ".csv");
    try {
      Files.writeString(file.toPath(), "\uFEFF" + HEADER + ROW, StandardCharsets.UTF_8);
      assertEquals("Rue de l'École", new ONISRAccidentParser().parseAccidents(file).get(0).getAddress());
      Files.writeString(file.toPath(), OLD_HEADER
          + "201800000001,18,1,24,1200,1,1,1,1,1,172,Rue de l'École,M,4360000,0380000,340\n",
          StandardCharsets.ISO_8859_1);
      assertEquals("Rue de l'École", new ONISRAccidentParser().parseAccidents(file).get(0).getAddress());
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void retainsAccidentsWithMissingMalformedOrOutOfRangeLocations() throws Exception {
    for (String coordinates : List.of(";", "0;0", "NaN;3", "43;Infinity", "91;3", "43;181", ".;3", "bad;3")) {
      String row = ROW.substring(0, ROW.lastIndexOf("43,6;3,8")) + coordinates + "\n";
      assertNull(coordinates, new ONISRAccidentParser().parseAccidents(new StringReader(HEADER + row)).get(0).getGeometry());
    }
    String greenwich = ROW.replace("43,6;3,8", "49;0");
    assertNotNull(new ONISRAccidentParser().parseAccidents(new StringReader(HEADER + greenwich)).get(0).getGeometry());
  }

  @Test
  public void rejectsInvalidIdentityDatesTimesHeadersAndRows() throws Exception {
    for (String csv : List.of("", HEADER.replace("Num_Acc", "wrong") + ROW,
        HEADER.replace("lum", "an") + ROW, HEADER + ROW + ROW,
        HEADER + ROW.replace("202500000001", "202400000001"), HEADER + ROW.replace(";17;08;", ";31;02;"),
        HEADER + ROW.replace("09:30", "24:00"), HEADER + ROW.replace(";1;34;", ";bad;34;"),
        HEADER + ROW.stripTrailing() + ";extra\n")) {
      try {
        new ONISRAccidentParser().parseAccidents(new StringReader(csv));
        fail("Expected malformed CSV to fail: " + csv);
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("ONISR"));
      }
    }
    assertTrue(new ONISRAccidentParser().parseAccidents(new StringReader(HEADER)).isEmpty());
  }

  @Test
  public void filtersBeforeParsingUnselectedAccidentsAndSharesGeographyWithCounts() throws Exception {
    assertTrue(new ONISRAccidentParser(Set.of("76")).parseAccidents(new StringReader(
        HEADER + ROW.replace("202500000001", "invalid").replace("09:30", "invalid"))).isEmpty());
    GeographicRecord count = new DPT30Comptage();
    GeographicRecord accident = new ONISRAccidentParser().parseAccidents(new StringReader(HEADER + ROW)).get(0);
    for (GeographicRecord record : List.of(count, accident)) {
      record.setOsmWayId(42L);
      assertEquals(Long.valueOf(42), record.getOsmWayId());
    }
  }
}
