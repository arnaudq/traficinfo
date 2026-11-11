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

package org.traficinfo.parsers.dpt30;

import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.Point;
import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.ParserBaseTest;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Year;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

public class DPT30ComptageParserTest extends ParserBaseTest {

  private final DPT30ComptageParser parser = new DPT30ComptageParser();

  @Test
  public void parsesSourceRowsAndGroupsYearsWithPrefixedId() throws Exception {
    List<DPT30Comptage> rows;
    try (Reader reader = getReaderFromResource("DPT30Sample.csv")) {
      rows = parser.parseComptages(reader);
    }
    assertEquals(3, rows.size());
    DPT30Comptage first = rows.get(0);
    assertEquals("1_0_3_0_2021", first.getIdTmja());
    assertEquals("1_0_3_0", first.getSiteId());
    assertEquals("DPT30_1_0_3_0", first.getCodeCentrale());
    assertEquals(first.getCodeCentrale(), rows.get(1).getCodeCentrale());
    assertEquals(2021, first.getAnnee());
    assertEquals(2025, rows.get(1).getAnnee());
    assertEquals(5780, first.getMoyenneJourAnnuelle());
    assertEquals(6075, rows.get(1).getMoyenneJourAnnuelle());
    assertEquals(Integer.valueOf(619), first.getTmjaPoidsLourds());
    assertEquals(Double.valueOf(10.71), first.getPourcentageTmjaPoidsLourds());
    assertEquals("D6086", first.getRouteId());
    assertEquals("D6086", first.getRoadReference());
    assertEquals("Pont-Saint-Esprit", first.getCityName());
    assertEquals("D6086 - Pont-Saint-Esprit", first.getDisplayName());
    Point point = (Point) first.getGeometry();
    assertEquals(4.620821744881, point.getCoordinates().getLongitude(), 0.0);
    assertEquals(44.2791399386299, point.getCoordinates().getLatitude(), 0.0);
    assertEquals("Permanent", first.getTypeCompteur());
    assertEquals("Tournant", rows.get(2).getTypeCompteur());
    assertNull(rows.get(2).getTmjaPoidsLourds());
    assertNull(rows.get(2).getPourcentageTmjaPoidsLourds());
    for (DPT30Comptage row : rows) {
      assertEquals(Comptage.Type.VEHICULES, row.getType());
      assertEquals(Comptage.DirectionType.BIDIRECTIONAL, row.getDirectionType());
      assertNull(row.getOsmWayId());
      assertNull(row.getMoyenneHeureDePointeMatin());
      assertNull(row.getMoyenneHeureDePointeSoir());
      assertEquals(0, row.getMeanSpeed());
      assertEquals(0, row.getV85Speed());
      assertEquals(-1, row.getMaximumSpeed());
    }
  }

  @Test
  public void handlesQuotedCommasUtf8FilesAndDisplayOverrides() throws Exception {
    File file = File.createTempFile("dpt30-", ".csv");
    try {
      Files.writeString(file.toPath(),
          "\uFEFFid_tmja,annee,numero_route,communes,tmja_tous_vehicules\n"
              + "001_0_3_0_2025,2025,D1,\"NÎMES, UZÈS\",10\n", StandardCharsets.UTF_8);
      DPT30Comptage row = parser.parseComptages(file, new Properties()).get(0);
      assertEquals("DPT30_001_0_3_0", row.getCodeCentrale());
      assertEquals("D1 - Nîmes, Uzès", row.getDisplayName());
      Properties names = new Properties();
      names.setProperty(row.getCodeCentrale(), "Custom counter name");
      assertEquals("Custom counter name", parser.parseComptages(file, names).get(0).getDisplayName());
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void preservesMissingNumbersAndDistinguishesRealZero() throws Exception {
    String csv = "id_tmja,annee,latitude,longitude,tmja_tous_vehicules,tmja_poids_lourds,"
        + "pourcentage_tmja_poids_lourds\n"
        + "A_2025,2025,,,,,\n"
        + "B_2025,2025,0,0,0,0,0\n"
        + "C_2025,2025,43,,10,,\n"
        + "D_2025,2025,,4,10,,\n";
    List<DPT30Comptage> rows = parser.parseComptages(new StringReader(csv));
    assertNull(rows.get(0).getMjaTvTcj());
    assertEquals(0, rows.get(0).getMoyenneJourAnnuelle());
    assertNull(rows.get(0).getGeometry());
    assertEquals("DPT30_A", rows.get(0).getDisplayName());
    assertEquals(Integer.valueOf(0), rows.get(1).getMjaTvTcj());
    assertEquals(Integer.valueOf(0), rows.get(1).getTmjaPoidsLourds());
    assertEquals(Double.valueOf(0), rows.get(1).getPourcentageTmjaPoidsLourds());
    Point zero = (Point) rows.get(1).getGeometry();
    assertEquals(0.0, zero.getCoordinates().getLongitude(), 0.0);
    assertEquals(0.0, zero.getCoordinates().getLatitude(), 0.0);
    assertNull(rows.get(2).getGeometry());
    assertNull(rows.get(3).getGeometry());
  }

  @Test
  public void rejectsMissingOrMismatchedRecordIds() throws Exception {
    for (String row : new String[]{",2025", "A,2025", "A_2024,2025", "_2025,2025",
        "A_0,", "A_2025,"}) {
      try {
        parser.parseComptages(new StringReader("id_tmja,annee\n" + row + "\n"));
        fail("Expected invalid id_tmja/year to be rejected: " + row);
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("id_tmja"));
      }
    }
  }

  @Test
  public void acceptsEmptyCsv() throws Exception {
    assertTrue(parser.parseComptages(new StringReader("")).isEmpty());
  }

  @Test
  public void generatesOneGeoJsonFeaturePerSiteWithAnnualHistory() throws Exception {
    int year = Year.now().getValue();
    String csv = "id_tmja,annee,numero_route,pr_abs,communes,latitude,longitude,tmja_tous_vehicules\n"
        + "1_0_3_0_" + (year - 1) + "," + (year - 1) + ",D1,0+2,NÎMES,43,4,400\n"
        + "1_0_3_0_" + year + "," + year + ",D1,0+2,NÎMES,43.5,4.5,500\n";
    StringWriter output = new StringWriter();
    GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.DPT30, new StringReader(csv), output);
    FeatureCollection result = new com.fasterxml.jackson.databind.ObjectMapper()
        .readValue(output.toString(), FeatureCollection.class);
    assertEquals(1, result.getFeatures().size());
    Feature feature = result.getFeatures().get(0);
    assertEquals("DPT30_1_0_3_0", feature.getProperty("code_centrale"));
    assertEquals("D1 - Nîmes", feature.getProperty("description"));
    assertEquals(500, (int) feature.getProperty("moyenne_jour_annuelle"));
    assertEquals("500 ⇄", feature.getProperty("name"));
    assertEquals("500 (" + year + ") 400 (" + (year - 1) + ") ",
        feature.getProperty("moyenne_annuelle_history"));
    Point point = (Point) feature.getGeometry();
    assertEquals(4.5, point.getCoordinates().getLongitude(), 0.0);
    assertEquals(43.5, point.getCoordinates().getLatitude(), 0.0);
  }
}
