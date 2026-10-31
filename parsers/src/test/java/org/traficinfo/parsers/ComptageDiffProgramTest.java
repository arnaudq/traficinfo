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
  public void filtersBothInputsByThirdArgumentAndWritesToFourthArgument() throws Exception {
    Path before = folder.newFile("before.csv").toPath();
    Path after = folder.newFile("after.csv").toPath();
    Path output = folder.getRoot().toPath().resolve("diff.json");
    String header = "code_centrale,annee,code_canal,type_compteur,v85ma_tcj\n";
    Files.writeString(before, header + "A,2020,0,compteur feux,50\nB,2022,0,compteur feux,40\n"
        + "C,2024,0,compteur feux,20\nD,2024,0,compteur feux,30\n");
    Files.writeString(after, header + "A,2020,0,compteur feux,60\nB,2022,0,compteur feux,60\n"
        + "C,2024,0,compteur feux,40\nE,2024,0,compteur feux,50\n");
    ComptageDiffProgram.main(new String[]{before.toString(), after.toString(), "2024, 2020", output.toString()});
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
