package org.traficinfo.parser.m3m;

import org.traficinfo.parser.Comptage;
import org.traficinfo.parser.ParserBaseTest;

import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class M3MComptageParserTest extends ParserBaseTest {

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
    assertEquals(Long.valueOf(370743659L), c.getOsmWayId());
    assertEquals(2024, c.getAnnee());
    assertEquals(577, c.getMoyenneJourAnnuelle());
    assertEquals(0, c.getVitessePratiquee());
    assertEquals(-1, c.getMaximumSpeed());
    assertEquals("Montpellier", c.getCityName());
    assertEquals("100457", c.getCodeCentrale());
    assertEquals("Boulevard de Strasbourg - Montpellier", c.getDisplayName());
    assertEquals("Palavas_Comte Melgueil", c.getLibelleGroupage());
    assertEquals("72 (8h00 - 9h00)", c.getMoyenneHeureDePointeMatin());
    assertEquals("43 (17h00 - 18h00)", c.getMoyenneHeureDePointeSoir());
    assertEquals(Comptage.Type.VEHICULES, c.getType());
    assertEquals(false, c.isBidirectionnelle());
  }
}
