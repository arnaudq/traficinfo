package org.traficinfo.parsers;

import org.junit.Test;
import org.traficinfo.parsers.mrn.MRNComptage;
import org.traficinfo.parsers.mrn.MRNComptageParser;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class ComptageMeasurementProgramTest {
  @Test
  public void detectsEachMrnVolumeIndependently() throws Exception {
    String[] fields = {"TV_TMJA", "TV_TMJO", "TV_HPM", "TV_HPS",
        "UVP_TMJA", "UVP_TMJO", "UVP_HPM", "UVP_HPS"};
    for (String field : fields) {
      for (String volume : new String[]{"", "0", "-1", "1"}) {
        String csv = "Type de Campagne;Id_SIG;Année;" + field + "\n"
            + "Comptage permanent en section;A;2025;" + volume + "\n";
        var count = new MRNComptageParser().parseComptages(new StringReader(csv)).get(0);
        assertEquals(field + "=" + volume, "1".equals(volume),
            ComptageMeasurementProgram.hasSignificantCount(count));
      }
    }
  }

  @Test
  public void ignoresSpeedPercentagesAndHoursButAcceptsFormattedPeaks() {
    MRNComptage count = new MRNComptage();
    count.setMeanSpeed(50);
    count.setV85Speed(60);
    count.setMaximumSpeed(70);
    count.setPlPctTmja(20);
    count.setTvTmjaPctEvo(10);
    for (String peak : new String[]{"", "  ", "0", "0 (08h-09h)", "-1", "08h-09h"}) {
      count.setMoyenneHeureDePointeMatin(peak);
      assertFalse(peak, ComptageMeasurementProgram.hasSignificantCount(count));
    }
    count.setMoyenneHeureDePointeMatin("12 (08h-09h)");
    assertTrue(ComptageMeasurementProgram.hasSignificantCount(count));
    count.setMoyenneHeureDePointeMatin(null);
    count.setMoyenneHeureDePointeSoir("1");
    assertTrue(ComptageMeasurementProgram.hasSignificantCount(count));
  }

  @Test
  public void reportsEveryTypeAndCountsRecordsRatherThanSites() {
    MRNComptage measured = new MRNComptage();
    measured.setType(Comptage.Type.VEHICULES);
    measured.setTvTmjo(10);
    MRNComptage missing = new MRNComptage();
    missing.setType(Comptage.Type.VEHICULES);
    MRNComptage bicycle = new MRNComptage();
    bicycle.setType(Comptage.Type.BICYCLES);
    var bytes = new ByteArrayOutputStream();
    ComptageMeasurementProgram.printSummary(List.of(measured, measured, missing, bicycle), new PrintStream(bytes));
    String report = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(report, report.matches("(?s).*VEHICULES\\s+3\\s+2\\s+1\\s+33.33%\\s+3.*"));
    assertTrue(report, report.matches("(?s).*BICYCLES\\s+1\\s+0\\s+1\\s+100.00%\\s+1.*"));
    assertTrue(report, report.matches("(?s).*TOTAL\\s+4\\s+2\\s+2\\s+50.00%\\s+4.*"));
    bytes.reset();
    ComptageMeasurementProgram.printSummary(List.of(), new PrintStream(bytes));
    assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("NaN"));
  }

  @Test
  public void resolvesSourceIdsAndEnumNames() {
    assertEquals(GeoJsonParser.DataSource.MRN, ComptageMeasurementProgram.dataSource("mrn"));
    assertEquals(GeoJsonParser.DataSource.M3MV2, ComptageMeasurementProgram.dataSource("MMM"));
    assertEquals(GeoJsonParser.DataSource.M3MV2, ComptageMeasurementProgram.dataSource("M3MV2"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsUnknownSource() {
    ComptageMeasurementProgram.dataSource("invalid");
  }
}
