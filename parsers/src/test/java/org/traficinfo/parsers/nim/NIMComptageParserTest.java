package org.traficinfo.parsers.nim;

import org.geojson.Point;
import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.ParserBaseTest;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

public class NIMComptageParserTest extends ParserBaseTest {
  private static final String HEADER = "id_camera;code INSEE;commune;date;tranche;position;coordonnées géographique;nbPassage\n";
  private final NIMComptageParser parser = new NIMComptageParser(testRegistry());

  @Test
  public void parsesDocumentedFieldsFromTinyApiSampleWithoutInventingDailyAverages() throws Exception {
    List<NIMComptage> result;
    try (Reader reader = getReaderFromResource("NIMSample.csv")) { result = parser.parseComptages(reader); }
    assertEquals(4, result.size());
    NIMComptage camera = result.stream().filter(value -> value.getCameraId().equals("52")).findFirst().orElseThrow();
    assertEquals("NIM_52_S02", camera.getCodeCentrale());
    assertEquals(2022, camera.getAnnee());
    assertEquals("30356", camera.getCodeInsee());
    assertEquals("Rodilhan", camera.getCityName());
    assertEquals("ROD Rte de Manduel 1 (2819b1) - Rodilhan", camera.getDisplayName());
    assertEquals("2022-01-31", camera.getFirstDate());
    assertEquals("2022-04-08", camera.getLastDate());
    assertEquals(2, camera.getObservedDays());
    assertEquals(2, camera.getObservedHours());
    assertEquals(2, camera.getIncompleteDays());
    assertEquals(93, camera.getTotalPassages());
    assertNull(camera.getDailyAverage());
    assertEquals(0, camera.getMoyenneJourAnnuelle());
    assertEquals(Comptage.DirectionType.BIDIRECTIONAL, camera.getDirectionType());
    assertEquals(Comptage.Type.VEHICULES, camera.getType());
    Point point = (Point) camera.getGeometry();
    assertEquals(4.44252, point.getCoordinates().getLongitude(), 0);
    assertEquals(43.82906, point.getCoordinates().getLatitude(), 0);
    assertNull(camera.getRoadReference());
    assertNull(camera.getOsmWayId());
    assertNull(camera.getMoyenneHeureDePointeMatin());
    assertNull(camera.getMoyenneHeureDePointeSoir());
    assertEquals(0, camera.getMeanSpeed());
    assertEquals(0, camera.getV85Speed());
    assertEquals(-1, camera.getMaximumSpeed());
  }

  @Test
  public void averagesCompleteDailyTotalsAndPreservesIncompleteCoverage() throws Exception {
    String csv = HEADER + day("52", "2022-01-01", 10) + day("52", "2022-01-02", 20)
        + row("52", "2022-01-03", 0, "999");
    NIMComptage count = parse(csv).get(0);
    assertEquals(Integer.valueOf(360), count.getDailyAverage());
    assertEquals(3, count.getObservedDays());
    assertEquals(2, count.getCompleteDays());
    assertEquals(1, count.getIncompleteDays());
    assertEquals(49, count.getObservedHours());
    assertEquals(1719, count.getTotalPassages());
    assertEquals(720, count.getCompleteDayPassages());
  }

  @Test
  public void groupsCamerasAndYearsAndIsIndependentOfRowOrder() throws Exception {
    String csv = HEADER + day("52", "2022-01-01", 10) + day("52", "2023-01-01", 20)
        + day("53", "2023-01-01", 30);
    List<NIMComptage> expected = parse(csv);
    List<String> rows = new ArrayList<>(List.of(csv.substring(HEADER.length()).split("\n")));
    Collections.shuffle(rows, new java.util.Random(42));
    List<NIMComptage> actual = parse(HEADER + String.join("\n", rows) + "\n");
    assertEquals(expected, actual);
    assertEquals(expected.hashCode(), actual.hashCode());
    assertEquals(3, actual.size());
    assertEquals(2022, actual.get(0).getAnnee());
    assertEquals(2023, actual.get(1).getAnnee());
    assertEquals("NIM_53_S01", actual.get(2).getCodeCentrale());
  }

  @Test
  public void deduplicatesIdenticalHourlyRowsAndRejectsConflictingCounts() throws Exception {
    String csv = HEADER + day("52", "2022-01-01", 10);
    NIMComptage count = parse(csv + row("52", "2022-01-01", 0, "10")).get(0);
    assertEquals(24, count.getObservedHours());
    assertEquals(240, count.getTotalPassages());
    try {
      new NIMComptageParser(org.slf4j.LoggerFactory.getLogger(NIMComptageParser.class),
          NIMComptageParser.ConflictPolicy.REJECT, testRegistry())
          .parseComptages(new StringReader(csv + row("52", "2022-01-01", 0, "11")));
      fail("Expected explicit REJECT policy to reject conflicting counts");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("Conflicting counts"));
    }
  }

  @Test
  public void excludesConflictedHoursWithoutRestoringThemWhenAnotherDuplicateArrives() throws Exception {
    String rows = day("52", "2022-01-01", 10) + row("52", "2022-01-01", 0, "11")
        + row("52", "2022-01-01", 0, "10") + row("52", "2022-01-01", 0, "12");
    NIMComptageParser diagnostic = new NIMComptageParser(testRegistry());
    NIMComptage count = diagnostic.parseComptages(new StringReader(HEADER + rows)).get(0);
    assertEquals(1, count.getConflictingHours());
    assertEquals(23, count.getObservedHours());
    assertEquals(230, count.getTotalPassages());
    assertEquals(1, count.getIncompleteDays());
    assertNull(count.getDailyAverage());
    List<String> shuffled = new ArrayList<>(List.of(rows.split("\n")));
    Collections.shuffle(shuffled, new java.util.Random(42));
    assertEquals(count, diagnostic.parseComptages(new StringReader(HEADER + String.join("\n", shuffled))).get(0));
  }

  @Test
  public void distinguishesZeroFromMissingAndDoesNotExtrapolatePartialFiles() throws Exception {
    NIMComptage zero = parse(HEADER + day("52", "2022-01-01", 0)).get(0);
    assertEquals(Integer.valueOf(0), zero.getDailyAverage());
    assertEquals(1, zero.getCompleteDays());
    String missing = HEADER + day("52", "2022-01-01", 10).replace(";10\n", ";\n");
    NIMComptage unknown = parse(missing).get(0);
    assertNull(unknown.getDailyAverage());
    assertEquals(0, unknown.getObservedHours());
    assertEquals(1, unknown.getIncompleteDays());
    NIMComptage partial = parse(HEADER + row("52", "2022-01-01", 0, "10")).get(0);
    assertNull(partial.getDailyAverage());
    assertEquals(10, partial.getTotalPassages());
  }

  @Test
  public void acceptsDocumentedMachineNamesAndBomAndLeavesCallerReaderOpen() throws Exception {
    String csv = (HEADER + day("52", "2022-01-01", 10))
        .replace("code INSEE", "code_insee").replace("coordonnées géographique", "coordonnees_geographique")
        .replace("nbPassage", "nbpassage");
    final boolean[] closed = {false};
    StringReader reader = new StringReader("\uFEFF" + csv) {
      @Override public void close() { closed[0] = true; super.close(); }
    };
    assertEquals(240, parser.parseComptages(reader).get(0).getMoyenneJourAnnuelle());
    assertFalse(closed[0]);
  }

  @Test
  public void readsUtf8FilesAndHonoursDisplayOverridesAndQuotedSemicolons() throws Exception {
    String csv = (HEADER + day("52", "2022-01-01", 10)).replace("Rue exemple", "\"Rue; de l’École\"");
    Path file = Files.createTempFile("nim-parser-", ".csv");
    try {
      Files.writeString(file, csv, StandardCharsets.UTF_8);
      assertEquals("Rue; de l’École - Nîmes", parser.parseComptages(file.toFile(), new Properties()).get(0).getDisplayName());
      Properties names = new Properties();
      names.setProperty("NIM_52_S01", "Custom name");
      assertEquals("Custom name", parser.parseComptages(file.toFile(), names).get(0).getDisplayName());
    } finally { Files.deleteIfExists(file); }
  }

  @Test
  public void splitsRelocatedCameraIntoSeparateSitesRegardlessOfRowOrder() throws Exception {
    String oldDay = day("52", "2022-01-01", 10);
    String newDay = day("52", "2022-02-01", 20).replace("Rue exemple", "Rue renommée").replace("43.8, 4.4", "43.9, 4.5");
    List<NIMComptage> counts = parse(HEADER + newDay + oldDay);
    assertEquals(2, counts.size());
    assertEquals("NIM_52_S01", counts.get(0).getCodeCentrale());
    assertEquals(240, counts.get(0).getTotalPassages());
    assertEquals("NIM_52_S03", counts.get(1).getCodeCentrale());
    assertEquals("Rue renommée - Nîmes", counts.get(1).getDisplayName());
    assertEquals(Double.valueOf(43.9), counts.get(1).getLatitude());
    assertEquals(480, counts.get(1).getTotalPassages());
    assertEquals(counts, parse(HEADER + oldDay + newDay));
  }

  @Test
  public void toleratesSameDayCoordinateDifferencesFromSourceSample() throws Exception {
    String first = "816;30317;Sernhac;2020-02-10;00:00 - 01:00;SER Ch. des Aires 1 FIXE (2210);43.91028, 4.55076;5\n";
    String second = "816;30317;Sernhac;2020-02-10;01:00 - 02:00;SER Ch. des Aires 1 FIXE (2210);43.9105807039654, 4.5507249331597;15\n";
    for (String rows : List.of(first + second, second + first)) {
      NIMComptage count = parse(HEADER + rows).get(0);
      assertEquals(2, count.getObservedHours());
      assertEquals(20, count.getTotalPassages());
    }
  }

  @Test
  public void appliesFiftyMetreToleranceToGeographicDistance() throws Exception {
    String first = row("52", "2022-01-01", 0, "10");
    String second = row("52", "2022-01-01", 1, "20");
    // About 49 m north or 48 m east; the diagonal exceeds 50 m.
    for (String coordinates : List.of("43.80044, 4.4", "43.8, 4.4006")) {
      assertEquals(30, parse(HEADER + first + second.replace("43.8, 4.4", coordinates)).get(0).getTotalPassages());
    }
    for (String coordinates : List.of("43.80046, 4.4", "43.8, 4.40064", "43.80044, 4.4006")) {
      assertInvalid(HEADER + first + second.replace("43.8, 4.4", coordinates), "Unknown NIM sites");
    }
  }

  @Test
  public void coordinateToleranceIgnoresLabelsButRequiresCoordinates() throws Exception {
    String first = row("52", "2022-01-01", 0, "10");
    String nearby = row("52", "2022-01-01", 1, "20").replace("43.8, 4.4", "43.8001, 4.4");
    for (String changed : List.of(nearby.replace("30189", "30317"), nearby.replace("NÎMES", "Sernhac"),
        nearby.replace("Rue exemple", "Other"))) {
      assertEquals(30, parse(HEADER + first + changed).get(0).getTotalPassages());
    }
    assertInvalid(HEADER + first + nearby.replace("43.8001, 4.4", ""), "Unknown NIM sites");
    assertInvalid(HEADER + first.replace("43.8, 4.4", "") + nearby, "Unknown NIM sites");
  }

  @Test
  public void acceptsMidnightEndingsAndRejectsNonHourlyIntervals() throws Exception {
    String csv = HEADER + day("52", "2022-01-01", 10);
    assertEquals(parse(csv), parse(csv.replace("23:00 - 24:00", "23:00 - 00:00")));
    for (String interval : List.of("24:00 - 25:00", "08:30 - 09:30", "08:00 - 10:00", "invalid")) {
      assertInvalid(csv.replace("00:00 - 01:00", interval), "tranche");
    }
  }

  @Test
  public void validatesHeadersDatesCountsCoordinatesAndRowWidths() throws Exception {
    String csv = HEADER + row("52", "2022-01-01", 0, "10");
    assertInvalid("", "header");
    assertInvalid(csv.replace("id_camera;", "unknown;"), "id_camera");
    assertInvalid(csv.replace("commune;", "date;"), "Duplicate");
    assertInvalid(csv.replace("2022-01-01", "2022-02-30"), "row 2");
    assertInvalid(HEADER + row("", "2022-01-01", 0, "10"), "id_camera");
    for (String value : List.of("-1", "abc", "1.5", "2147483648")) {
      assertInvalid(HEADER + row("52", "2022-01-01", 0, value), "row 2");
    }
    for (String point : List.of("91, 4", "43, 181", "NaN, 4", "43", "43, 4, 1")) {
      assertInvalid(csv.replace("43.8, 4.4", point), "row 2");
    }
    assertInvalid(csv + "52;too;short\n", "column count");
  }

  @Test
  public void coordinatesAreRequiredButOtherLocationColumnsCanBeAbsent() throws Exception {
    String minimal = "id_camera;date;tranche;nbpassage;coordonnees_geographique\n52;2022-01-01;00:00 - 01:00;10;43.8, 4.4\n";
    NIMComptage count = parse(minimal).get(0);
    assertEquals("NIM_52_S01", count.getDisplayName());
    assertNotNull(count.getGeometry());
    assertNull(count.getCityName());
    assertInvalid(minimal.replace(";coordonnees_geographique", "").replace(";43.8, 4.4", ""), "Unknown NIM sites");
    assertInvalid(HEADER + day("52", "2022-01-01", Integer.MAX_VALUE), "integer range");
  }

  @Test
  public void integratesWithGeoJsonParserWithoutEnablingCloudPublication() throws Exception {
    NIMSiteRegistry.Site site = NIMSiteRegistry.bundled().sites().get(0);
    String csv = HEADER + day(site.cameraId(), Year.now().getValue() + "-01-01", 10)
        .replace("43.8, 4.4", site.latitude() + ", " + site.longitude());
    StringWriter output = new StringWriter();
    GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.NIM, new StringReader(csv), output);
    var features = new com.fasterxml.jackson.databind.ObjectMapper()
        .readValue(output.toString(), org.geojson.FeatureCollection.class).getFeatures();
    assertEquals(1, features.size());
    assertEquals("NIM", features.get(0).getProperty("data_source"));
    assertEquals(site.codeCentrale(), features.get(0).getProperty("code_centrale"));
    assertEquals(240, ((Number) features.get(0).getProperty("moyenne_jour_annuelle")).intValue());
  }

  @Test
  public void logsCoverageWithoutIndividualIncompleteDays() throws Exception {
    List<String> logs = new ArrayList<>();
    org.slf4j.Logger logger = (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(
        org.slf4j.Logger.class.getClassLoader(), new Class<?>[]{org.slf4j.Logger.class},
        (proxy, method, args) -> {
          if (args != null && args.length > 0 && args[0] instanceof String format) {
            Object[] values = args.length == 2 && args[1] instanceof Object[] array
                ? array : java.util.Arrays.copyOfRange(args, 1, args.length);
            logs.add(org.slf4j.helpers.MessageFormatter.arrayFormat(format, values).getMessage());
          }
          return method.getReturnType() == boolean.class ? true : null;
        });
    String first = day("52", "2022-01-01", 10);
    String partial = row("52", "2022-01-03", 0, "10")
        + row("52", "2022-01-03", 0, "11") + row("52", "2022-01-03", 0, "12");
    NIMComptage count = new NIMComptageParser(logger, NIMComptageParser.ConflictPolicy.EXCLUDE_HOUR, testRegistry())
        .parseComptages(new StringReader(HEADER + partial + first)).get(0);
    assertEquals(1, count.getIncompleteDays());
    assertEquals(1, count.getDaysWithoutRecords());
    String output = String.join("\n", logs);
    assertFalse(output, output.contains("NIM incomplete day:"));
    assertFalse(output, output.contains("missingHours="));
    assertTrue(output, output.contains("NIM camera coverage: camera=NIM_52, observedDays=2, observedHours=24, averageObservedHoursPerDay=12.00"));
    assertTrue(output, output.contains("NIM conflicting hour excluded: camera=NIM_52_S01, date=2022-01-03"));
    assertTrue(output, output.contains("values=[10, 11, 12]"));
    assertTrue(output, output.contains("from=2022-01-02, to=2022-01-02, daysWithoutRecords=1"));
    assertTrue(output, output.contains("NIM summary: camerasEncountered=1, camerasRetained=1, siteYears=1, observedSiteDays=2, completeDays=1, incompleteDays=1, daysWithoutRecords=1"));
  }

  @Test
  public void ignores1970RowsBeforeValidatingHourlyValues() throws Exception {
    String ignored = row("99", "1970-01-01", 0, "invalid");
    List<NIMComptage> counts = parse(HEADER + ignored + day("52", "2022-01-01", 10));
    assertEquals(1, counts.size());
    assertEquals("52", counts.get(0).getCameraId());
    assertEquals(2022, counts.get(0).getAnnee());
    assertTrue(parse(HEADER + ignored).isEmpty());
  }

  private static NIMSiteRegistry testRegistry() {
    try {
      return new NIMSiteRegistry(List.of(
          site("52", "01", 43.8, 4.4), site("52", "02", 43.82906, 4.44252), site("52", "03", 43.9, 4.5),
          site("53", "01", 43.8, 4.4), site("54", "01", 43.790886274995025, 4.380295085959558),
          site("810", "01", 43.9063761562996, 4.46848228615511),
          site("786", "01", 43.7930223860447, 4.3833647939309), site("816", "01", 43.91028, 4.55076)));
    } catch (IOException invalid) { throw new AssertionError(invalid); }
  }

  private static NIMSiteRegistry.Site site(String camera, String suffix, double latitude, double longitude) {
    return new NIMSiteRegistry.Site("NIM_" + camera + "_S" + suffix, camera, latitude, longitude, false,
        "2020-01-01", "2024-12-31", List.of(), List.of(), List.of());
  }

  private List<NIMComptage> parse(String csv) throws IOException { return parser.parseComptages(new StringReader(csv)); }

  private void assertInvalid(String csv, String message) {
    try { parse(csv); fail("Expected invalid NIM CSV"); }
    catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
  }

  private static String day(String camera, String date, int count) {
    StringBuilder rows = new StringBuilder();
    for (int hour = 0; hour < 24; hour++) rows.append(row(camera, date, hour, Integer.toString(count)));
    return rows.toString();
  }

  private static String row(String camera, String date, int hour, String count) {
    return camera + ";30189;NÎMES;" + date + ";" + String.format(java.util.Locale.ROOT, "%02d:00 - %02d:00", hour, hour + 1)
        + ";Rue exemple;43.8, 4.4;" + count + "\n";
  }
}
