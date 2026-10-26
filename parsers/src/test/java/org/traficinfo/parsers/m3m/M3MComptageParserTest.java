package org.traficinfo.parsers.m3m;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.ParserBaseTest;

import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class M3MComptageParserTest extends ParserBaseTest {

  @Test
  public void testEmptyCsvValuesUseBeanDefaults() throws Exception {
    String csv = "code_centrale,type_compteur,vtma_tcj,v85ma_tcj,hp_johvs_matin,hp_johvs_soir\n"
        + "100457,compteur feux,,,,\n";

    List<? extends Comptage> comptages = new M3MComptageParser()
        .parseComptages(new StringReader(csv));

    assertEquals(1, comptages.size());
    Comptage comptage = comptages.get(0);
    assertEquals(0, comptage.getMeanSpeed());
    assertEquals(0, comptage.getV85Speed());
    assertEquals("", comptage.getMoyenneHeureDePointeMatin());
    assertEquals("", comptage.getMoyenneHeureDePointeSoir());
    assertEquals(-1, comptage.getMaximumSpeed());
  }

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
    assertNull(c.getGeometry());
    assertEquals(Long.valueOf(370743659L), c.getOsmWayId());
    assertEquals(2024, c.getAnnee());
    assertEquals(577, c.getMoyenneJourAnnuelle());
    assertEquals(0, c.getV85Speed());
    assertEquals(0, c.getMeanSpeed());
    assertEquals(-1, c.getMaximumSpeed());
    assertEquals("Montpellier", c.getCityName());
    assertEquals("100457", c.getCodeCentrale());
    assertEquals("Boulevard de Strasbourg - Montpellier", c.getDisplayName());
    assertEquals("72 (8h00 - 9h00)", c.getMoyenneHeureDePointeMatin());
    assertEquals("43 (17h00 - 18h00)", c.getMoyenneHeureDePointeSoir());
    assertEquals(Comptage.Type.VEHICULES, c.getType());
    assertEquals(Comptage.DirectionType.UNIDIRECTIONAL, c.getDirectionType());
  }
}
