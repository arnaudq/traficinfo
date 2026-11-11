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

package org.traficinfo.parsers.dpt76;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.geojson.Point;
import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.ParserBaseTest;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

public class DPT76ComptageParserTest extends ParserBaseTest {
  private final DPT76ComptageParser parser = new DPT76ComptageParser();
  private final ObjectMapper mapper = new ObjectMapper();

  private String sample() throws IOException {
    try (Reader reader = getReaderFromResource("DPT76_Comptage_2022.geojson")) {
      return mapper.readTree(reader).toString();
    }
  }

  @Test
  public void parsesSourcePropertiesAndPreservesDistinctLocationsSharingStationCode() throws Exception {
    List<DPT76Comptage> records = parser.parseComptages(new StringReader(sample()));
    assertEquals(3, records.size());
    DPT76Comptage first = records.get(0);
    assertEquals(Long.valueOf(1), first.getFid());
    assertEquals("DPT76_1", first.getCodeCentrale());
    assertEquals(Long.valueOf(5401), first.getObjectId());
    assertEquals(Double.valueOf(1235), first.getStationCode());
    assertEquals(first.getStationCode(), records.get(1).getStationCode());
    assertNotEquals(first.getCodeCentrale(), records.get(1).getCodeCentrale());
    assertEquals(2022, first.getAnnee());
    assertEquals(14793, first.getMoyenneJourAnnuelle());
    assertEquals(Integer.valueOf(7387), first.getTmjaSens1());
    assertEquals(Integer.valueOf(7406), first.getTmjaSens2());
    assertEquals(Double.valueOf(5), first.getPourcentagePl());
    assertEquals(Double.valueOf(5), first.getPourcentagePlSens1());
    assertEquals(Double.valueOf(5), first.getPourcentagePlSens2());
    assertEquals("76 D0925", first.getRouteId());
    assertEquals("D0925", first.getRoadReference());
    assertEquals("BC", first.getTypeCompteur());
    assertEquals("76 D0925 - PR 104 + 200", first.getDisplayName());
    assertEquals(Double.valueOf(98691), first.getCumuld());
    assertEquals(Comptage.DirectionType.BIDIRECTIONAL, first.getDirectionType());
    assertEquals(Comptage.Type.VEHICULES, first.getType());
    assertEquals(-1, first.getMaximumSpeed());
    assertEquals(0, first.getV85Speed());
    assertEquals(0, first.getMeanSpeed());
    assertNull(first.getOsmWayId());
    assertNull(first.getCityName());
    assertNull(first.getMoyenneHeureDePointeMatin());
    Point point = (Point) first.getGeometry();
    assertEquals(1.13819767843498, point.getCoordinates().getLongitude(), 0);
    assertEquals(49.9304738120914, point.getCoordinates().getLatitude(), 0);
    assertNull(records.get(2).getGeometry());
  }

  @Test
  public void supportsUtf8BomFilesAndDisplayOverridesWithoutClosingCallerReader() throws Exception {
    Path file = Files.createTempFile("dpt76-", ".geojson");
    Properties names = new Properties();
    names.setProperty("DPT76_1", "Comptage côtier");
    try {
      Files.writeString(file, "\uFEFF" + sample());
      assertEquals("Comptage côtier", parser.parseComptages(file.toFile(), names).get(0).getDisplayName());
    } finally {
      Files.deleteIfExists(file);
    }
    StringReader reader = new StringReader(sample());
    parser.parseComptages(reader);
    assertEquals(-1, reader.read());
  }

  @Test
  public void keepsMissingValuesDistinctFromZeroAndDoesNotUseProjectedAttributesAsGeometry() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(sample());
    ObjectNode p = (ObjectNode) root.get("features").get(0).get("properties");
    for (String field : new String[]{"Trafic_Mo_", "Code_stat_", "objectid", "Pourcenta_"}) p.putNull(field);
    p.put("Tmja_sens_", 0);
    ((ObjectNode) root.get("features").get(0)).putNull("geometry");
    DPT76Comptage record = parser.parseComptages(new StringReader(root.toString())).get(0);
    assertNull(record.getMjaTvTcj());
    assertEquals(0, record.getMoyenneJourAnnuelle());
    assertNull(record.getStationCode());
    assertNull(record.getObjectId());
    assertNull(record.getPourcentagePl());
    assertEquals(Integer.valueOf(0), record.getTmjaSens1());
    assertNull(record.getGeometry());
  }

  @Test
  public void validatesCollectionIdentifiersYearsCountsAndGeometry() throws Exception {
    for (String input : new String[]{"", "{}", "{\"error\":{\"message\":\"Failed query\"}}", "[]", sample() + " {}"}) {
      reject(input);
    }
    for (String field : new String[]{"FID", "Annee_de_", "Trafic_Mo_"}) {
      ObjectNode root = (ObjectNode) mapper.readTree(sample());
      ((ObjectNode) root.get("features").get(0).get("properties")).put(field, "invalid");
      reject(root.toString());
    }
    for (String field : new String[]{"FID", "Annee_de_"}) {
      ObjectNode root = (ObjectNode) mapper.readTree(sample());
      ((ObjectNode) root.get("features").get(0).get("properties")).remove(field);
      reject(root.toString());
    }
    ObjectNode root = (ObjectNode) mapper.readTree(sample());
    ((ObjectNode) root.get("features").get(1).get("properties")).put("FID", 1);
    reject(root.toString());
    root = (ObjectNode) mapper.readTree(sample());
    root.put("exceededTransferLimit", true);
    reject(root.toString());
    root = (ObjectNode) mapper.readTree(sample());
    ((ObjectNode) root.get("features").get(0).get("geometry")).put("type", "LineString");
    reject(root.toString());
    root = (ObjectNode) mapper.readTree(sample());
    ((ObjectNode) root.get("features").get(0).get("geometry")).set("coordinates", mapper.readTree("[566222,6982892]"));
    reject(root.toString());
    assertTrue(parser.parseComptages(new StringReader("{\"type\":\"FeatureCollection\",\"features\":[]}")).isEmpty());
  }

  private void reject(String input) throws Exception {
    try {
      parser.parseComptages(new StringReader(input));
      fail("Expected IOException");
    } catch (IOException expected) {
      // Invalid input must fail rather than publish a partial interpretation.
    }
  }

  @Test
  public void registersParserAndGeneratesSeparateGeoJsonPoints() throws Exception {
    assertEquals(GeoJsonParser.DataSource.DPT76, GeoJsonParser.DataSource.fromId("dpt76"));
    StringWriter output = new StringWriter();
    GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.DPT76, new StringReader(sample()), output);
    JsonNode features = mapper.readTree(output.toString()).get("features");
    assertEquals(2, features.size());
    assertEquals("DPT76_1", features.get(0).get("properties").get("code_centrale").asText());
    assertEquals("DPT76", features.get(0).get("properties").get("data_source").asText());
    assertEquals(14793, features.get(0).get("properties").get("moyenne_jour_annuelle").asInt());
    assertEquals("14793 ⇄", features.get(0).get("properties").get("name").asText());
    assertEquals("DPT76_236", features.get(1).get("properties").get("code_centrale").asText());
  }
}
