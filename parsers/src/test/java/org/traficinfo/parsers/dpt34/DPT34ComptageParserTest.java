package org.traficinfo.parsers.dpt34;

import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.ParserBaseTest;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

public class DPT34ComptageParserTest extends ParserBaseTest {

  private final DPT34ComptageParser parser = new DPT34ComptageParser();

  @Test
  public void parsesSourceRowsAndGroupsYearsWithPrefixedId() throws Exception {
    List<DPT34Comptage> rows;
    try (Reader reader = getReaderFromResource("DPT34Sample.csv")) {
      rows = parser.parseComptages(reader);
    }
    assertEquals(5, rows.size());
    assertEquals("Nissan-Lez-Enserune", rows.get(4).getCityName());
    DPT34Comptage first = rows.get(0);
    assertEquals("Per.034.2005.00.3", first.getSiteId());
    assertEquals("DPT34_Per.034.2005.00.3", first.getCodeCentrale());
    assertEquals(first.getCodeCentrale(), rows.get(1).getCodeCentrale());
    assertEquals(2014, first.getAnnee());
    assertEquals(2025, rows.get(1).getAnnee());
    assertEquals(3684, first.getMoyenneJourAnnuelle());
    assertEquals(4349, rows.get(1).getMoyenneJourAnnuelle());
    assertEquals("Olonzac", first.getCityName());
    assertEquals("34D0011", first.getRouteId());
    assertEquals("OLONZAC D11 - Olonzac", first.getDisplayName());
    assertEquals("OUPIA D52 <> DPT. AUDE D610", first.getAltStreetName());
    assertEquals(Double.valueOf(43.26700822), first.getLatitude());
    assertEquals(Double.valueOf(2.74608187), first.getLongitude());
    assertEquals(Double.valueOf(43.267008), rows.get(1).getLatitude());
    assertEquals("Per", first.getTypeCompteur());
    assertEquals("Tou", rows.get(2).getTypeCompteur());
    assertEquals("CuP", rows.get(3).getTypeCompteur());
    for (DPT34Comptage row : rows) {
      assertEquals(Comptage.Type.VEHICULES, row.getType());
      assertTrue(row.isBidirectionnelle());
      assertNull(row.getOsmWayId());
      assertNull(row.getMoyenneHeureDePointeMatin());
      assertNull(row.getMoyenneHeureDePointeSoir());
      assertEquals(0, row.getMeanSpeed());
      assertEquals(0, row.getV85Speed());
      assertEquals(-1, row.getMaximumSpeed());
    }
    assertNull(rows.get(4).getMjaTvTcj());
    assertEquals(0, rows.get(4).getMoyenneJourAnnuelle());
  }

  @Test
  public void handlesBomQuotedSemicolonsAndDisplayOverrides() throws Exception {
    String csv = "\uFEFFIdentifiant du compteur;Libellé;Localisation;Commune;Année;Sens de la route;mja_tv_tcj\n"
        + "Per.034.0113.01.3;Section;\"D11; traversée\";BÉZIERS;2025;3;42\n";
    assertEquals("D11; traversée - Béziers",
        parser.parseComptages(new StringReader(csv)).get(0).getDisplayName());
    Properties names = new Properties();
    names.setProperty("DPT34_Per.034.0113.01.3", "Custom counter name");
    assertEquals("Custom counter name",
        parser.parseComptages(new StringReader(csv), names).get(0).getDisplayName());
  }

  @Test
  public void preservesMissingNumbersAndDistinguishesRealZero() throws Exception {
    String csv = "Identifiant du compteur;Libellé;Localisation;Commune;Année;Sens de la route;"
        + "Latitude;Longitude;mja_tv_tcj\n"
        + "Tou.034.0001.00.1;Section;;COMMUNE;2025;1;;;\n"
        + "Tou.034.0002.00.2;Other section;;;2025;2;43.5;3.5;0\n";
    List<DPT34Comptage> rows = parser.parseComptages(new StringReader(csv));
    DPT34Comptage missing = rows.get(0);
    assertEquals("Section - Commune", missing.getDisplayName());
    assertFalse(missing.isBidirectionnelle());
    assertNull(missing.getLatitude());
    assertNull(missing.getLongitude());
    assertNull(missing.getMjaTvTcj());
    DPT34Comptage zero = rows.get(1);
    assertEquals("Other section", zero.getDisplayName());
    assertFalse(zero.isBidirectionnelle());
    assertEquals(Integer.valueOf(0), zero.getMjaTvTcj());
  }

  @Test
  public void readsUtf8FilesAndUsesTheSameOverridePath() throws Exception {
    File file = File.createTempFile("dpt34-", ".csv");
    try {
      Files.writeString(file.toPath(),
          "Identifiant du compteur;Localisation;Commune;Année;Sens de la route;mja_tv_tcj\n"
              + "Per.034.0001.00.3;Route départementale;BÉZIERS;2025;3;10\n",
          StandardCharsets.UTF_8);
      assertEquals("Route départementale - Béziers",
          parser.parseComptages(file, new Properties()).get(0).getDisplayName());
      Properties names = new Properties();
      names.setProperty("DPT34_Per.034.0001.00.3", "Overridden");
      assertEquals("Overridden", parser.parseComptages(file, names).get(0).getDisplayName());
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  @Test
  public void acceptsEmptyCsv() throws Exception {
    assertTrue(parser.parseComptages(new StringReader("")).isEmpty());
  }

  @Test(expected = IOException.class)
  public void rejectsMissingCounterId() throws Exception {
    parser.parseComptages(new StringReader("Identifiant du compteur;Année;mja_tv_tcj\n;2025;12\n"));
  }
}
