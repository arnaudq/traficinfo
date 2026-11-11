/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.traficinfo.parsers.m3m.M3MComptage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class ComptageDiffProgramTest {
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void selectsSourceThenFiltersYearsAndWritesToFifthArgument() throws Exception {
    Path before = folder.newFile("before.csv").toPath();
    Path after = folder.newFile("after.csv").toPath();
    Path output = folder.getRoot().toPath().resolve("diff.json");
    String header = "code_centrale,annee,code_canal,type_compteur,v85ma_tcj\n";
    Files.writeString(before, header + "A,2020,0,compteur feux,50\nB,2022,0,compteur feux,40\n"
        + "C,2024,0,compteur feux,20\nD,2024,0,compteur feux,30\n");
    Files.writeString(after, header + "A,2020,0,compteur feux,60\nB,2022,0,compteur feux,60\n"
        + "C,2024,0,compteur feux,40\nE,2024,0,compteur feux,50\n");
    ComptageDiffProgram.main(new String[]{"MMM", before.toString(), after.toString(), "2024, 2020", output.toString()});
    JsonNode years = new ObjectMapper().readTree(output.toFile());
    assertEquals(2, years.size());
    assertTrue(years.has("2020"));
    assertFalse(years.has("2022"));
    assertEquals(1, years.get("2024").get("added").asInt());
    assertEquals(1, years.get("2024").get("modified").asInt());
    assertEquals(1, years.get("2024").get("deleted").asInt());
    assertEquals(1, years.get("2024").get("fieldSummary").get("v85Speed").get("added").asInt());
    assertEquals(Set.of(2020, 2024), ComptageDiffProgram.parseYears("2024,2020,2024"));
    assertEquals(1, ComptageDiffProgram.filterYears(List.of(record(0)), Set.of()).size());
  }

  @Test
  public void selectsEachCsvParserFromTheSourceId() throws Exception {
    for (String[] sample : List.of(new String[]{"MMM", "M3MSample.csv"},
        new String[]{"MRN", "MRNSample.csv"}, new String[]{"DPT30", "DPT30Sample.csv"},
        new String[]{"DPT34", "DPT34Sample.csv"})) {
      Path input = folder.newFile(sample[0] + ".csv").toPath();
      try (var stream = getClass().getResourceAsStream("/" + sample[1])) {
        assertNotNull(stream);
        Files.write(input, stream.readAllBytes());
      }
      Path output = folder.getRoot().toPath().resolve(sample[0] + ".json");
      ComptageDiffProgram.main(new String[]{sample[0], input.toString(), input.toString(),
          "2024,2025,2026", output.toString()});
      assertEquals(0, new ObjectMapper().readTree(output.toFile()).size());
    }
  }

  @Test
  public void sourceIdsAreCaseInsensitiveAndAcceptEnumAliases() {
    for (GeoJsonParser.DataSource source : GeoJsonParser.DataSource.values()) {
      assertSame(source, GeoJsonParser.DataSource.fromId(source.getId().toLowerCase(java.util.Locale.ROOT)));
      assertSame(source, GeoJsonParser.DataSource.fromId(source.name()));
    }
    try {
      GeoJsonParser.DataSource.fromId("invalid");
      fail("Accepted unknown source");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Unknown data source"));
    }
  }

  @Test
  public void sourceAwareDifferComparesEveryRegisteredModel() throws Exception {
    for (GeoJsonParser.DataSource source : GeoJsonParser.DataSource.values()) {
      Comptage before = source.getDsComptageClass().getDeclaredConstructor().newInstance();
      Comptage after = source.getDsComptageClass().getDeclaredConstructor().newInstance();
      before.setCodeCentrale("A");
      after.setCodeCentrale("A");
      before.setAnnee(2025);
      after.setAnnee(2025);
      assertTrue(ComptageDiffer.diff(source, List.of(before), List.of(after)).isEmpty());
      after.setV85Speed(60);
      assertEquals(List.of(new ComptageDiffer.Diff(before, after)),
          ComptageDiffer.diff(source, List.of(before), List.of(after)));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void sourceAwareDifferRejectsRecordsFromAnotherSource() {
    ComptageDiffer.diff(GeoJsonParser.DataSource.MRN, List.of(record(1)), List.of());
  }

  @Test
  public void comparesMrnMeasurementsUsingItsSemicolonSchema() throws Exception {
    String header = "Type de Campagne;Détails de la campagne;Id_SIG;Année;Rue;Commune;"
        + "Nombre de Sens;TV_TMJA;X_84;Y_84\n";
    Path before = folder.newFile("mrn-before.csv").toPath();
    Path after = folder.newFile("mrn-after.csv").toPath();
    Path output = folder.getRoot().toPath().resolve("mrn-diff.json");
    Files.writeString(before, header + "Comptage permanent en section;;A;2025;Rue;Rouen;2 sens;100;1.1;49.4\n");
    Files.writeString(after, header + "Comptage permanent en section;;A;2025;Rue;Rouen;2 sens;200;1.1;49.4\n");
    ComptageDiffProgram.main(new String[]{"mrn", before.toString(), after.toString(), "2025", output.toString()});
    JsonNode year = new ObjectMapper().readTree(output.toFile()).get("2025");
    assertEquals(1, year.get("modified").asInt());
    assertEquals(100, year.get("changes").get(0).get("before").get("moyenneJourAnnuelle").asInt());
    assertEquals(200, year.get("changes").get(0).get("after").get("moyenneJourAnnuelle").asInt());
    assertEquals(1, year.get("fieldSummary").get("moyenneJourAnnuelle").get("modified").asInt());
  }

  @Test
  public void comparesNimHourlyCsvAsAnnualRecords() throws Exception {
    var site = org.traficinfo.parsers.nim.NIMSiteRegistry.bundled().sites().get(0);
    StringBuilder csv = new StringBuilder(
        "id_camera;code INSEE;commune;date;tranche;position;coordonnées géographique;nbPassage\n");
    for (int hour = 0; hour < 24; hour++) {
      csv.append(site.cameraId()).append(";30000;Nimes;2025-01-01;")
          .append(String.format(java.util.Locale.ROOT, "%02d:00 - %02d:00", hour, (hour + 1) % 24))
          .append(";Site;").append(site.latitude()).append(", ").append(site.longitude()).append(";10\n");
    }
    Path before = folder.newFile("nim-before.csv").toPath();
    Path after = folder.newFile("nim-after.csv").toPath();
    Path output = folder.getRoot().toPath().resolve("nim-diff.json");
    Files.writeString(before, csv);
    Files.writeString(after, csv.toString().replace(";10\n", ";20\n"));
    ComptageDiffProgram.main(new String[]{"NIM", before.toString(), after.toString(), "2025", output.toString()});
    JsonNode year = new ObjectMapper().readTree(output.toFile()).get("2025");
    assertEquals(1, year.get("modified").asInt());
    assertEquals(240, year.get("changes").get(0).get("before").get("moyenneJourAnnuelle").asInt());
    assertEquals(480, year.get("changes").get(0).get("after").get("moyenneJourAnnuelle").asInt());
  }

  @Test
  public void rejectsMalformedYearLists() {
    for (String value : List.of("", "2024,", "2024,,2025", "2024-2025", "0", "-1", "abc")) {
      try {
        ComptageDiffProgram.parseYears(value);
        fail("Accepted invalid years: " + value);
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("years"));
      }
    }
  }

  @Test
  public void summarizesEveryFieldForAddedModifiedAndDeletedValues() throws Exception {
    M3MComptage empty = record(0);
    M3MComptage first = record(1);
    M3MComptage second = record(2);
    List<ComptageDiffer.Diff> differences = List.of(
        new ComptageDiffer.Diff(null, first), new ComptageDiffer.Diff(first, null),
        new ComptageDiffer.Diff(empty, first), new ComptageDiffer.Diff(first, empty),
        new ComptageDiffer.Diff(first, second), new ComptageDiffer.Diff(empty, empty));
    Map<String, ComptageDiffProgram.YearCounts> summary = ComptageDiffProgram.fieldSummary(differences);
    assertEquals(5, summary.size());
    for (ComptageDiffProgram.YearCounts counts : summary.values()) {
      assertEquals(2, counts.added);
      assertEquals(1, counts.modified);
      assertEquals(2, counts.deleted);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ComptageDiffProgram.printSummary(differences, new PrintStream(bytes));
    String printed = bytes.toString(StandardCharsets.UTF_8).replaceAll("[ \\t]+", " ");
    for (String field : summary.keySet()) {
      assertTrue(printed.contains(field + " 2 1 2"));
    }
    assertTrue(printed.contains("Field summary for 2024:"));
    StringWriter writer = new StringWriter();
    ComptageDiffProgram.writeDiff(differences, writer);
    JsonNode fields = new ObjectMapper().readTree(writer.toString()).get("2024").get("fieldSummary");
    assertEquals(5, fields.size());
    assertEquals(2, fields.get("meanSpeed").get("added").asInt());
    assertEquals(1, fields.get("moyenneHeureDePointeMatin").get("modified").asInt());
  }

  @Test
  public void ignoresAltStreetNameWithoutHidingOtherChanges() {
    M3MComptage before = record(1);
    M3MComptage after = record(1);
    after.setAltStreetName("Updated source label");
    assertTrue(ComptageDiffer.diff(List.of(before), List.of(after)).isEmpty());
    assertEquals("", new ComptageDiffer.Diff(before, after).changedFields());
    after.setV85Speed(60);
    List<ComptageDiffer.Diff> differences = ComptageDiffer.diff(List.of(before), List.of(after));
    assertEquals(1, differences.size());
    assertEquals("v85Speed", differences.get(0).changedFields());
    assertEquals("Updated source label", ((M3MComptage) differences.get(0).after()).getAltStreetName());
  }

  private static M3MComptage record(int value) {
    M3MComptage count = new M3MComptage();
    count.setCodeCentrale("A");
    count.setAnnee(2024);
    count.setMeanSpeed(value);
    count.setV85Speed(value);
    count.setMoyenneJourAnnuelle(value);
    count.setMoyenneHeureDePointeMatin(value == 0 ? "" : value + " (08h-09h)");
    count.setMoyenneHeureDePointeSoir(value == 0 ? null : value + " (17h-18h)");
    return count;
  }
}
