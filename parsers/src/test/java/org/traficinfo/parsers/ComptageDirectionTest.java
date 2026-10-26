package org.traficinfo.parsers;

import org.junit.Test;
import org.traficinfo.parsers.dpt30.DPT30Comptage;
import org.traficinfo.parsers.dpt30.DPT30ComptageParser;
import org.traficinfo.parsers.dpt34.DPT34Comptage;
import org.traficinfo.parsers.dpt34.DPT34ComptageParser;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.traficinfo.parsers.m3m.M3MComptageParser;
import org.traficinfo.parsers.mrn.MRNComptage;
import org.traficinfo.parsers.mrn.MRNComptageParser;

import java.io.Reader;
import java.util.List;

import static org.junit.Assert.*;
import static org.traficinfo.parsers.Comptage.DirectionType.*;

public class ComptageDirectionTest extends ParserBaseTest {
  @Test
  public void derivesM3MDirectionFromChannelRatherThanStationType() {
    M3MComptage count = new M3MComptage();
    assertEquals(UNKNOWN, count.getDirectionType());
    count.setCodeCanal(" ");
    assertEquals(UNKNOWN, count.getDirectionType());
    count.setCodeCanal("Z");
    assertEquals(BIDIRECTIONAL, count.getDirectionType());
    count.setTypeComptage("comptage bidirectionnel");
    for (String channel : List.of("0", "1", "v", "w", "6")) {
      count.setCodeCanal(channel);
      assertEquals(UNIDIRECTIONAL, count.getDirectionType());
    }
  }

  @Test
  public void derivesDpt34DirectionFromSensRoute() {
    DPT34Comptage count = new DPT34Comptage();
    assertEquals(UNKNOWN, count.getDirectionType());
    count.setSensRoute(1);
    assertEquals(UNIDIRECTIONAL, count.getDirectionType());
    count.setSensRoute(2);
    assertEquals(UNIDIRECTIONAL, count.getDirectionType());
    count.setSensRoute(3);
    assertEquals(BIDIRECTIONAL, count.getDirectionType());
    count.setSensRoute(99);
    assertEquals(UNKNOWN, count.getDirectionType());
  }

  @Test
  public void derivesMrnDirectionFromNombreDeSens() {
    MRNComptage count = new MRNComptage();
    assertEquals(UNKNOWN, count.getDirectionType());
    count.setDirectionCount("2 sens");
    assertEquals(BIDIRECTIONAL, count.getDirectionType());
    for (String direction : List.of("1 sens", "Sens unique")) {
      count.setDirectionCount(direction);
      assertEquals(UNIDIRECTIONAL, count.getDirectionType());
    }
    count.setDirectionCount(" ");
    assertEquals(UNKNOWN, count.getDirectionType());
    count.setDirectionCount("unexpected");
    assertEquals(UNKNOWN, count.getDirectionType());
  }

  @Test
  public void retainsGardCombinedDirectionsAssumption() {
    assertEquals(BIDIRECTIONAL, new DPT30Comptage().getDirectionType());
  }

  @Test
  public void parsesDirectionTypesFromEachSource() throws Exception {
    assertParsedDirections(new M3MComptageParser(), "M3MSample.csv", UNIDIRECTIONAL);
    assertParsedDirections(new DPT30ComptageParser(), "DPT30Sample.csv",
        BIDIRECTIONAL, BIDIRECTIONAL, BIDIRECTIONAL);
    assertParsedDirections(new DPT34ComptageParser(), "DPT34Sample.csv",
        BIDIRECTIONAL, BIDIRECTIONAL, BIDIRECTIONAL, BIDIRECTIONAL, BIDIRECTIONAL);
    assertParsedDirections(new MRNComptageParser(), "MRNSample.csv",
        BIDIRECTIONAL, BIDIRECTIONAL, UNIDIRECTIONAL, UNIDIRECTIONAL, UNKNOWN);
  }

  private void assertParsedDirections(ComptageParser parser, String resource,
                                      Comptage.DirectionType... expected) throws Exception {
    try (Reader reader = getReaderFromResource(resource)) {
      List<? extends Comptage> counts = parser.parseComptages(reader);
      assertEquals(List.of(expected), counts.stream().map(Comptage::getDirectionType).toList());
    }
  }
}
