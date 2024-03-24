package org.traficinfo.m3m.parser;

import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class M3MComptageParserTest {

  @Test
  public void testParse() throws Exception {

    M3MComptageParser parser = new M3MComptageParser();

    List<? extends Comptage> comptages = parser.parseComptages(new StringReader(""));
    assertEquals(0, comptages.size());

    try (Reader reader = getReaderFromResource("M3MSample.csv")) {
      comptages = parser.parseComptages(reader);
    }
    assertEquals(1, comptages.size());
    Comptage c = comptages.get(0);
    assertEquals(2024, c.getAnnee());
    assertEquals(577, c.getMoyenneJourAnnuelle());
    assertEquals(0, c.getVitessePratiquee());
    assertEquals("Montpellier", c.getCityName());
    assertEquals("100457", c.getCodeCentrale());
    assertEquals("Boulevard de Strasbourg - Montpellier", c.getDisplayName());
    assertEquals("Palavas_Comte Melgueil", c.getLibelleGroupage());
    assertEquals("72 (8h00 - 9h00)", c.getMoyenneHeureDePointeMatin());
    assertEquals("43 (17h00 - 18h00)", c.getMoyenneHeureDePointeSoir());
    assertEquals(Comptage.Type.VEHICULES, c.getType());
    assertEquals(false, c.isBidirectionnelle());
  }

  private Reader getReaderFromResource(String filename) throws IOException {
    InputStream inputStream = this.getClass().getResourceAsStream("/" + filename);
    if (inputStream == null) {
      throw new IOException("failed to load " + filename);
    }
    return new InputStreamReader(inputStream, StandardCharsets.UTF_8);
  }
}
