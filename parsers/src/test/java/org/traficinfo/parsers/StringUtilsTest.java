package org.traficinfo.parsers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StringUtilsTest {

  @Test
  public void capitalizesWordsSeparatedBySpacesAndHyphens() {
    assertEquals("Saint Clement-De-Riviere",
        StringUtils.toCamelCase("SAINT CLEMENT-DE-RIVIERE"));
    assertEquals("Saint-Clément De Rivière",
        StringUtils.toCamelCase("sAiNt-cLÉmEnT dE rIvIèRe"));
    assertEquals("Béziers", StringUtils.toCamelCase("BÉZIERS"));
  }

  @Test
  public void preservesSeparators() {
    assertEquals("  Saint--Clément  De\tRivière ",
        StringUtils.toCamelCase("  SAINT--CLÉMENT  DE\tRIVIÈRE "));
  }

  @Test
  public void handlesMissingOrEmptyInput() {
    assertNull(StringUtils.toCamelCase(null));
    assertEquals("", StringUtils.toCamelCase(""));
    assertEquals(" - ", StringUtils.toCamelCase(" - "));
  }
}
