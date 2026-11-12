package org.traficinfo.parsers.mrn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.geojson.FeatureCollection;
import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.OSMInfoProvider;
import org.traficinfo.parsers.OSMWayInfo;
import org.traficinfo.parsers.ParserBaseTest;
import org.geojson.Point;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

public class MRNComptageParserTest extends ParserBaseTest {
  private final MRNComptageParser parser = new MRNComptageParser();
  private static final String HEADER = "Type de Campagne;Détails de la campagne;Id_SIG;Année;"
      + "Rue;Commune;Nombre de Sens;TV_TMJA;TV_HPM;TV_HPM (heure);TV_HPS;TV_HPS (heure);"
      + "X_84;Y_84;geopoint\n";

  private String csv(String row) {
    return HEADER + row.replace("YEAR", Integer.toString(Year.now().getValue())) + "\n";
  }

  @Test
  public void mapsUnmodifiedSourceRowsAndPreservesSourceMeasures() throws Exception {
    List<MRNComptage> counts;
    try (Reader reader = getReaderFromResource("MRNSample.csv")) {
      counts = parser.parseComptages(reader);
    }
    assertEquals(7, counts.size());
    MRNComptage first = counts.get(0);
    assertEquals("CA_000000064_3", first.getSiteId());
    assertEquals("MRN_CA_000000064_3", first.getCodeCentrale());
    assertEquals(first.getCodeCentrale(), counts.get(1).getCodeCentrale());
    assertEquals(2026, first.getAnnee());
    assertEquals(2025, counts.get(1).getAnnee());
    assertEquals(11448, first.getMoyenneJourAnnuelle());
    assertEquals("D921 - Saint-Pierre-lès-Elbeuf", first.getDisplayName());
    assertNull(first.getRoadReference());
    assertEquals("76640", first.getCommuneInsee());
    assertEquals(Integer.valueOf(12599), first.getTvTmjo());
    assertEquals(Integer.valueOf(11888), first.getUvpTmja());
    assertEquals(Integer.valueOf(4), first.getPlPctTmja());
    assertEquals("923 (08h-09h)", first.getMoyenneHeureDePointeMatin());
    assertEquals("1160 (17h-18h)", first.getMoyenneHeureDePointeSoir());
    assertEquals("[08h-09h]", first.getTvHpmHour());
    assertEquals(Comptage.DirectionType.BIDIRECTIONAL, first.getDirectionType());
    Point point = first.getGeometry();
    assertEquals(1.05133534, point.getCoordinates().getLongitude(), 0);
    assertEquals(49.29198122, point.getCoordinates().getLatitude(), 0);
    assertEquals(Comptage.Type.VEHICULES, counts.get(2).getType());
    assertEquals(Comptage.DirectionType.UNIDIRECTIONAL, counts.get(2).getDirectionType()); // "1 sens"
    assertEquals("585 (08H00-09H00)", counts.get(2).getMoyenneHeureDePointeMatin());
    assertEquals(Comptage.Type.BICYCLES, counts.get(3).getType());
    assertEquals(Comptage.DirectionType.UNIDIRECTIONAL, counts.get(3).getDirectionType()); // "Sens unique"
    assertEquals("Piste Cyclable Unidirectionnelle", counts.get(3).getCampaignDetails());
    for (int i = 4; i < 6; i++) {
      assertEquals(Comptage.Type.UNKNOWN, counts.get(i).getType());
    }
    assertEquals(Comptage.Type.PIETONS_VELOS, counts.get(6).getType());
    assertNull(counts.get(4).getTvTmja());
    assertEquals(0, counts.get(4).getMoyenneJourAnnuelle());
    for (MRNComptage count : counts) {
      assertNull(count.getOsmWayId());
      assertEquals(-1, count.getMaximumSpeed());
      assertEquals(0, count.getMeanSpeed());
      assertEquals(0, count.getV85Speed());
    }
  }

  @Test
  public void handlesBomQuotedFieldsOverridesAndCoordinateFallback() throws Exception {
    String csv = "\uFEFF" + csv("Comptage ponctuel en section;; A_01 ;YEAR;\"D 13A; traversée\";"
        + " Rouen ;2 sens;12;7;;8;[17h-18h];;;49.2, 1.1");
    MRNComptage count = parser.parseComptages(new StringReader(csv)).get(0);
    assertEquals("D 13A; traversée - Rouen", count.getDisplayName());
    assertNull(count.getRoadReference());
    assertEquals("7", count.getMoyenneHeureDePointeMatin());
    assertEquals("8 (17h-18h)", count.getMoyenneHeureDePointeSoir());
    assertEquals(1.1, count.getGeometry().getCoordinates().getLongitude(), 0);
    assertEquals(49.2, count.getGeometry().getCoordinates().getLatitude(), 0);
    Properties names = new Properties();
    names.setProperty("MRN_A_01", "Override");
    Path file = Files.createTempFile("mrn-", ".csv");
    try {
      Files.writeString(file, csv, StandardCharsets.UTF_8);
      assertEquals("Override", parser.parseComptages(file.toFile(), names).get(0).getDisplayName());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void preservesBlanksAndDoesNotInventRoadReferencesOrCounts() throws Exception {
    MRNComptage missing = parser.parseComptages(new StringReader(csv(
        "Comptage permanent en section;;A;YEAR;D7 / D8;;;;;;;;;;"))).get(0);
    assertNull(missing.getTvTmja());
    assertNull(missing.getGeometry());
    assertNull(missing.getRoadReference());
    assertNull(missing.getDirectionCount());
    assertNull(missing.getMoyenneHeureDePointeMatin());
    assertEquals("D7 / D8", missing.getDisplayName());
    MRNComptage zero = parser.parseComptages(new StringReader(csv(
        "Comptage permanent en section;;B;YEAR;;;;0;0;;0;;;;"))).get(0);
    assertEquals(Integer.valueOf(0), zero.getTvTmja());
    assertEquals("MRN_B", zero.getDisplayName());
    assertNull(zero.getMoyenneHeureDePointeMatin());
  }

  @Test
  public void retainsUnmappedPopulationsAsUnknown() throws Exception {
    String rows = "";
    for (String details : List.of("Vélos", "Piétons / Vélos", "Piétons / Vélos / EDP",
        "Piétons", "Vélos / EDP")) {
      rows += "Comptage ponctuel de modes doux;" + details
          + ";A;2025;;;2 sens;;;;;;;;\n";
    }
    rows += "Unknown campaign;;B;2025;;;;;;;;;;;\n";
    List<MRNComptage> counts = parser.parseComptages(new StringReader(HEADER + rows));
    assertEquals(6, counts.size());
    for (int i = 3; i < counts.size(); i++) {
      assertEquals(Comptage.Type.UNKNOWN, counts.get(i).getType());
    }
    assertEquals(Comptage.Type.BICYCLES, counts.get(0).getType());
    assertEquals(Comptage.Type.PIETONS_VELOS, counts.get(1).getType());
    assertEquals(Comptage.Type.PIETONS_VELOS, counts.get(2).getType());
  }

  @Test
  public void mapsCampaignLabelsAndDefaultsMissingOrUnmappedLabelsToUnknown() throws Exception {
    for (String campaign : List.of("Comptage permanent en section", "Comptage ponctuel en section",
        "Comptage permanent de vélos", "Comptage ponctuel directionnel sur carrefour",
        "Unknown campaign", "", "   ")) {
      MRNComptage count = parser.parseComptages(new StringReader(csv(
          campaign + ";;A;YEAR;;;2 sens;120;;;;;1.1;49.2;"))).get(0);
      Comptage.Type expected = campaign.endsWith("en section") ? Comptage.Type.VEHICULES
          : campaign.equals("Comptage permanent de vélos") ? Comptage.Type.BICYCLES
          : Comptage.Type.UNKNOWN;
      assertEquals(campaign, expected, count.getType());
    }
  }

  @Test
  public void registersGeoJsonOutputWithHistoryPeaksAndOsmEnrichment() throws Exception {
    int year = Year.now().getValue();
    String csv = HEADER
        + "Comptage ponctuel en section;;A;" + year
        + ";D921;Rouen;2 sens;120;15;[08h-09h];20;[17h-18h];1.1;49.2;\n"
        + "Comptage ponctuel en section;;A;" + (year - 1)
        + ";D921;Rouen;2 sens;100;;;;;1.1;49.2;\n"
        + "Comptage permanent de vélos;;B;" + year
        + ";Piste;Rouen;Sens unique;30;;;;;1.2;49.3;\n"
        + "Comptage ponctuel directionnel sur carrefour;;C;" + year
        + ";Carrefour;Rouen;;120;;;;;1.3;49.4;\n";
    OSMInfoProvider osm = new OSMInfoProvider() {
      public Long getWayId(String id, Point point, String road, String streetName) {
        assertNotNull(point);
        assertNull(road);
        return "MRN_A".equals(id) ? 123L : null;
      }
      public OSMWayInfo getWayInfo(long id) {
        OSMWayInfo info = new OSMWayInfo();
        info.setTags(Map.of("maxspeed", "30"));
        return info;
      }
    };
    StringWriter writer = new StringWriter();
    GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.MRN, new StringReader(csv), writer, osm);
    FeatureCollection features = new ObjectMapper().readValue(writer.toString(), FeatureCollection.class);
    assertEquals(1, features.getFeatures().size());
    var first = features.getFeatures().get(0);
    assertEquals("MRN_A", first.getProperty("code_centrale"));
    assertEquals(120, (int) first.getProperty("moyenne_jour_annuelle"));
    assertEquals("15 (08h-09h)", first.getProperty("moyenne_pointe_matin"));
    assertEquals("30", first.getProperty("maximum_speed"));
    assertEquals("120 (" + year + ") 100 (" + (year - 1) + ") ",
        first.getProperty("moyenne_annuelle_history"));
    StringWriter bicycles = new StringWriter();
    GeoJsonParser.writeGeoJson(GeoJsonParser.DataSource.MRN,
        parser.parseComptages(new StringReader(csv)), new FeatureCollection(), bicycles, osm,
        Comptage.Type.BICYCLES);
    FeatureCollection bicycleFeatures = new ObjectMapper().readValue(bicycles.toString(), FeatureCollection.class);
    assertEquals(1, bicycleFeatures.getFeatures().size());
    assertEquals("MRN_B", bicycleFeatures.getFeatures().get(0).getProperty("code_centrale"));
  }

  @Test
  public void acceptsEmptyCsvAndSkipsUnknownDirections() throws Exception {
    assertTrue(parser.parseComptages(new StringReader("")).isEmpty());
    assertTrue(parser.parseComptages(new StringReader(csv(
        "Comptage permanent en section;;A;YEAR;;;unexpected;10;;;;;;;"))).isEmpty());
  }

  @Test(expected = IOException.class)
  public void rejectsMissingSiteId() throws Exception {
    parser.parseComptages(new StringReader(csv(
        "Comptage permanent en section;;;YEAR;;;2 sens;10;;;;;;;")));
  }

  @Test(expected = IOException.class)
  public void rejectsInvalidYear() throws Exception {
    parser.parseComptages(new StringReader(csv(
        "Comptage permanent en section;;A;0;;;2 sens;10;;;;;;;")));
  }

  @Test(expected = IOException.class)
  public void rejectsInvalidCoordinates() throws Exception {
    parser.parseComptages(new StringReader(csv(
        "Comptage permanent en section;;A;YEAR;;;2 sens;10;;;;;1.1;91;")));
  }

  @Test(expected = IOException.class)
  public void rejectsInvalidGeopoint() throws Exception {
    parser.parseComptages(new StringReader(csv(
        "Comptage permanent en section;;A;YEAR;;;2 sens;10;;;;;;;bad")));
  }

  @Test(expected = IOException.class)
  public void rejectsMalformedNumbers() throws Exception {
    parser.parseComptages(new StringReader(csv(
        "Comptage permanent en section;;A;YEAR;;;2 sens;bad;;;;;;;")));
  }
}
