package org.traficinfo.parsers.ren;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.geojson.Point;
import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.ComptageDiffer;
import org.traficinfo.parsers.GeoJsonParser;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

public class RENComptageParserTest {
  private final RENComptageParser parser = new RENComptageParser();
  private final ObjectMapper json = new ObjectMapper();

  private String sample() throws IOException {
    try (var input = getClass().getResourceAsStream("/RENSample.csv")) {
      return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test public void preservesDirectionsYearsDecimalMeasuresAndMeasurementDuration() throws Exception {
    var counts = parser.parseComptages(new StringReader(sample()));
    assertEquals(10, counts.size());
    RENComptage first = counts.get(0);
    assertEquals("REN_V9O35.3", first.getCodeCentrale());
    assertEquals(2017, first.getAnnee());
    assertEquals("V9O35.3", first.getStationCode());
    assertEquals("V9O35.3 - Nord-Sud / Ouest-Est", first.getDisplayName());
    assertEquals(Comptage.DirectionType.UNIDIRECTIONAL, first.getDirectionType());
    assertEquals(Comptage.DirectionType.BIDIRECTIONAL, counts.get(2).getDirectionType());
    assertEquals(Comptage.DirectionType.UNKNOWN, counts.get(6).getDirectionType());
    assertEquals(first.getCodeCentrale(), counts.get(2).getCodeCentrale());
    assertNotEquals(first.getSens(), counts.get(2).getSens());
    assertEquals(first.getCodeCentrale(), counts.get(5).getCodeCentrale());
    assertEquals(Double.valueOf(0.1), first.getNbSemaines());
    assertEquals(Double.valueOf(17.5), first.getVMoyenneTv());
    assertEquals(18, first.getMeanSpeed());
    assertEquals(Double.valueOf(11435.4), counts.get(6).getTmjaTv());
    assertEquals(11435, counts.get(6).getMoyenneJourAnnuelle());
    assertEquals(Double.valueOf(670.1), counts.get(6).getTmjaPl());
    assertEquals(4167, counts.get(7).getMoyenneJourAnnuelle());
    assertEquals(new Point(-1.6607344675337985, 48.10839916957304), first.getGeometry());
    assertNull(counts.get(8).getGeometry());
    assertNull(counts.get(9).getV85Tv());
    assertEquals(0, counts.get(9).getV85Speed());
    assertNull(first.getOsmWayId());
    assertNull(first.getCityName());
    assertNull(first.getRoadReference());
    assertEquals(-1, first.getMaximumSpeed());
    assertEquals(Comptage.Type.VEHICULES, first.getType());
  }

  @Test public void preservesMissingAndZeroAndMapsPeakHourFlowsWithoutInventingMeasures() throws Exception {
    var count = parser.parseComptages(new StringReader(changed(java.util.Map.of(
        "tmja_tv", "", "v85_tv", "", "debit_hpm_tv", "", "tmja_pl", "0",
        "debit_hps_tv", "12.3", "sens", "Nouvelle catégorie")))).get(0);
    assertNull(count.getTmjaTv());
    assertEquals(0, count.getMoyenneJourAnnuelle());
    assertEquals(Double.valueOf(0), count.getTmjaPl());
    assertNull(count.getV85Tv());
    assertNull(count.getMoyenneHeureDePointeMatin());
    assertEquals("12.3", count.getMoyenneHeureDePointeSoir());
    assertEquals(Comptage.DirectionType.UNKNOWN, count.getDirectionType());
  }

  @Test public void supportsFilesBomOverridesAndCallerOwnedReaders() throws Exception {
    var path = Files.createTempFile("ren-", ".csv");
    var names = new Properties();
    names.put("REN_V9O35.3", "Comptage rennais");
    try {
      Files.writeString(path, "\uFEFF" + sample());
      assertEquals("Comptage rennais", parser.parseComptages(path.toFile(), names).get(0).getDisplayName());
    } finally { Files.deleteIfExists(path); }
    var reader = new StringReader(sample());
    parser.parseComptages(reader);
    assertEquals(-1, reader.read());
  }

  @Test public void rejectsMalformedDuplicateAndInvalidCoordinates() throws Exception {
    for (String input : List.of("", "{}", "[]", "code_rm;annee;sens\nA;2025;Sens cumulés\n")) reject(input);
    for (String field : List.of("code_rm", "annee", "sens")) reject(changed(java.util.Map.of(field, "")));
    for (String field : List.of("annee", "tmja_tv", "nb_semaines", "v85_tv")) {
      reject(changed(java.util.Map.of(field, "-1")));
      reject(changed(java.util.Map.of(field, "NaN")));
    }
    var rows = rows();
    rows.add(rows.get(1).clone());
    reject(csv(rows));
    for (String coords : List.of("350000, 6800000", "48.1", "NaN, -1.6", ", -1.6", "48.1,", "48.1, 1, 2")) {
      reject(changed(java.util.Map.of("geo_point_2d", coords)));
    }
    assertTrue(parser.parseComptages(new StringReader(csv(java.util.Collections.singletonList(rows().get(0))))).isEmpty());
  }

  @Test public void measurementComparisonSeparatesDirectionsAndMapGroupsByStation() throws Exception {
    assertSame(GeoJsonParser.DataSource.REN, GeoJsonParser.DataSource.fromId("ren"));
    var counts = parser.parseComptages(new StringReader(sample()));
    var changed = parser.parseComptages(new StringReader(sample()));
    changed.get(0).setMoyenneJourAnnuelle(100);
    assertEquals(1, ComptageDiffer.diff(GeoJsonParser.DataSource.REN, counts, changed).size());
    // Both changed measurements are unidirectional, but their source sens values differ.
    changed.get(4).setMoyenneJourAnnuelle(400);
    var differences = ComptageDiffer.diff(GeoJsonParser.DataSource.REN, counts, changed);
    assertEquals(2, differences.size());
    assertSame(counts.get(0), differences.get(0).before());
    assertSame(counts.get(4), differences.get(1).before());
    // Keep fixture years relevant to the shared rolling output window.
    counts.forEach(c -> c.setAnnee(c.getAnnee() == 2016 ? java.time.Year.now().getValue() - 1 : java.time.Year.now().getValue()));
    var out = new StringWriter();
    GeoJsonParser.writeGeoJson(GeoJsonParser.DataSource.REN, counts, new org.geojson.FeatureCollection(), out, wayId -> null);
    var features = json.readTree(out.toString()).get("features");
    assertEquals(4, features.size());
    var ids = new java.util.HashSet<String>();
    for (var f : features) {
      assertEquals("REN", f.at("/properties/data_source").asText());
      String id = f.at("/properties/code_centrale").asText();
      assertTrue(ids.add(id));
      if (id.equals("REN_V9O35.3")) {
        assertEquals(359, f.at("/properties/moyenne_jour_annuelle").asInt());
        assertEquals("30", f.at("/properties/v85_speed").asText());
        assertEquals("23", f.at("/properties/mean_speed").asText());
        assertTrue(f.at("/properties/moyenne_annuelle_history").asText().startsWith("359 ("));
      }
    }
    assertEquals(java.util.Set.of("REN_V9O35.3", "REN_V1U35.3", "REN_V1U35.2", "REN_MB235.6"), ids);
  }

  @Test public void sharedSelectionUsesLatestYearAndDirectionSpeedsWithoutRequiringACombinedRow() throws Exception {
    var counts = parser.parseComptages(new StringReader(sample())).stream()
        .filter(c -> "V9O35.3".equals(c.getStationCode())).toList();
    int year = java.time.Year.now().getValue();
    counts.forEach(c -> c.setAnnee(c.getAnnee() == 2016 ? year - 1 : year));
    counts.get(0).setV85Speed(70);
    counts.get(0).setMeanSpeed(45);
    var out = new StringWriter();
    GeoJsonParser.writeGeoJson(GeoJsonParser.DataSource.REN, counts,
        new org.geojson.FeatureCollection(), out, way -> null);
    var features = json.readTree(out.toString()).get("features");
    assertEquals(1, features.size());
    assertEquals(359, features.get(0).at("/properties/moyenne_jour_annuelle").asInt());
    assertEquals("70", features.get(0).at("/properties/v85_speed").asText());
    assertEquals("45", features.get(0).at("/properties/mean_speed").asText());
    var directional = counts.stream().filter(c -> !"Sens cumulés".equals(c.getSens())).toList();
    out = new StringWriter();
    GeoJsonParser.writeGeoJson(GeoJsonParser.DataSource.REN, directional,
        new org.geojson.FeatureCollection(), out, way -> null);
    features = json.readTree(out.toString()).get("features");
    assertEquals(1, features.size());
    assertEquals("REN_V9O35.3", features.get(0).at("/properties/code_centrale").asText());
    assertEquals(year, features.get(0).at("/properties/annee").asInt());
    assertEquals(353, features.get(0).at("/properties/moyenne_jour_annuelle").asInt());
  }

  private java.util.List<String[]> rows() throws Exception {
    try (var reader = new com.opencsv.CSVReaderBuilder(new StringReader(sample()))
        .withCSVParser(new com.opencsv.CSVParserBuilder().withSeparator(';').build()).build()) {
      return new java.util.ArrayList<>(reader.readAll());
    }
  }

  private String csv(java.util.List<String[]> rows) throws Exception {
    var out = new StringWriter();
    try (var writer = new com.opencsv.CSVWriterBuilder(out).withSeparator(';').build()) { writer.writeAll(rows); }
    return out.toString();
  }

  private String changed(java.util.Map<String, String> fields) throws Exception {
    var rows = rows();
    for (var field : fields.entrySet()) {
      rows.get(1)[java.util.Arrays.asList(rows.get(0)).indexOf(field.getKey())] = field.getValue();
    }
    return csv(rows);
  }

  private void reject(String input) throws Exception {
    try { parser.parseComptages(new StringReader(input)); fail("Expected invalid REN input"); }
    catch (IOException expected) { }
  }
}
