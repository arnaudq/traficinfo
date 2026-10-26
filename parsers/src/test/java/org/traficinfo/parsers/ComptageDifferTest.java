package org.traficinfo.parsers;

import org.junit.Test;
import org.traficinfo.parsers.m3m.M3MComptage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class ComptageDifferTest {
  @Test
  public void omitsUnchangedRecords() {
    assertTrue(ComptageDiffer.diff(List.of(), List.of()).isEmpty());
    assertTrue(ComptageDiffer.diff(List.of(comptage("A", 2025, 50)),
        List.of(comptage("A", 2025, 50))).isEmpty());
  }

  @Test
  public void returnsBeforeAndAfterForModificationsAndOneSideForAdditionsAndRemovals() {
    Comptage removed = comptage("removed", 2025, 40);
    Comptage before = comptage("modified", 2025, 50);
    Comptage after = comptage("modified", 2025, 55);
    Comptage added = comptage("added", 2025, 60);
    List<ComptageDiffer.Diff> differences = ComptageDiffer.diff(
        List.of(removed, before), List.of(after, added));

    assertEquals(List.of(new ComptageDiffer.Diff(removed, null),
        new ComptageDiffer.Diff(before, after), new ComptageDiffer.Diff(null, added)), differences);
    assertSame(before, differences.get(1).before());
    assertSame(after, differences.get(1).after());
    assertEquals(List.of(new ComptageDiffer.Diff(removed, null)),
        ComptageDiffer.diff(List.of(removed), List.of()));
    assertEquals(List.of(new ComptageDiffer.Diff(null, added)),
        ComptageDiffer.diff(List.of(), List.of(added)));
  }

  @Test
  public void distinguishesYearsForTheSameCentralId() {
    Comptage oldYear = comptage("A", 2024, 50);
    Comptage newYear = comptage("A", 2025, 50);
    assertEquals(List.of(new ComptageDiffer.Diff(oldYear, null),
        new ComptageDiffer.Diff(null, newYear)),
        ComptageDiffer.diff(List.of(oldYear), List.of(newYear)));
    assertTrue(ComptageDiffer.diff(List.of(oldYear, newYear),
        List.of(comptage("A", 2024, 50), comptage("A", 2025, 50))).isEmpty());
  }

  @Test
  public void matchesEachChannelIndependentlyForTheSameCentralIdAndYear() {
    M3MComptage beforeZero = comptage("A", 2025, 50);
    beforeZero.setCodeCanal("0");
    M3MComptage afterZero = comptage("A", 2025, 60);
    afterZero.setCodeCanal("0");
    M3MComptage beforeOne = comptage("A", 2025, 40);
    beforeOne.setCodeCanal("1");
    M3MComptage afterOne = comptage("A", 2025, 40);
    afterOne.setCodeCanal("1");

    assertEquals(List.of(new ComptageDiffer.Diff(beforeZero, afterZero)),
        ComptageDiffer.diff(List.of(beforeZero, beforeOne), List.of(afterOne, afterZero),
            M3MComptage::getCodeCanal));
    assertEquals(List.of(new ComptageDiffer.Diff(beforeZero, null),
        new ComptageDiffer.Diff(null, afterOne)),
        ComptageDiffer.diff(List.of(beforeZero), List.of(afterOne), M3MComptage::getCodeCanal));
  }

  @Test
  public void usesSharedDirectionAsDefaultDiscriminator() {
    M3MComptage before = comptage("A", 2025, 50);
    before.setCodeCanal("Z");
    M3MComptage after = comptage("A", 2025, 50);
    after.setCodeCanal("0");
    assertEquals(List.of(new ComptageDiffer.Diff(before, null),
        new ComptageDiffer.Diff(null, after)), ComptageDiffer.diff(List.of(before), List.of(after)));
  }

  @Test
  public void detectsChangesOutsideV85IncludingSourceSpecificFields() {
    M3MComptage before = comptage("A", 2025, 50);
    M3MComptage after = comptage("A", 2025, 50);
    after.setTypeComptage("comptage bidirectionnel");
    assertEquals(List.of(new ComptageDiffer.Diff(before, after)),
        ComptageDiffer.diff(List.of(before), List.of(after)));
  }

  @Test
  public void keepsFirstDuplicateOnEachSide() {
    Comptage before = comptage("A", 2025, 50);
    Comptage after = comptage("A", 2025, 60);
    List<ComptageDiffer.Diff> differences = ComptageDiffer.diff(
        List.of(before, comptage("A", 2025, 60)),
        List.of(after, comptage("A", 2025, 50)));
    assertEquals(List.of(new ComptageDiffer.Diff(before, after)), differences);
    assertSame(before, differences.get(0).before());
    assertSame(after, differences.get(0).after());

    // Later duplicates must not turn an unchanged first pair into a modification.
    assertTrue(ComptageDiffer.diff(List.of(before, after),
        List.of(comptage("A", 2025, 50), after)).isEmpty());
  }

  @Test
  public void reportsV85ChangesAndDeletionsIncludingRemovedRecords() {
    List<ComptageDiffer.Diff> differences = ComptageDiffer.diff(
        List.of(comptage("changed", 2025, 50), comptage("blanked", 2025, 50),
            comptage("removed", 2025, 50), comptage("removedWithoutV85", 2025, 0),
            comptage("filled", 2025, 0), comptage("otherChange", 2025, 50)),
        List.of(comptage("changed", 2025, 60), comptage("blanked", 2025, 0),
            comptage("filled", 2025, 40), comptage("otherChange", 2025, 50),
            comptage("added", 2025, 70)));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ComptageDiffProgram.printSummary(differences, new PrintStream(bytes));
    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Comptage added: 1"));
    assertTrue(output.contains("Comptage removed: 2"));
    assertTrue(output.contains("Comptage modified: 3"));
    assertTrue(output.contains("V85 modified: 2"));
    assertTrue(output.contains("V85 deleted: 2"));
    assertTrue(output.contains("V85 modified or deleted: 4"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsDiffWithoutEitherSide() {
    new ComptageDiffer.Diff(null, null);
  }

  @Test
  public void reportsCountsByYearInAscendingOrder() {
    List<ComptageDiffer.Diff> differences = ComptageDiffer.diff(
        List.of(comptage("A", 2026, 50), comptage("B", 2024, 50),
            comptage("C", 2024, 50), comptage("A", 2025, 50)),
        List.of(comptage("A", 2026, 60), comptage("B", 2024, 60),
            comptage("A", 2025, 50), comptage("D", 2026, 50), comptage("E", 2026, 50)));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ComptageDiffProgram.printSummary(differences, new PrintStream(bytes));
    String output = bytes.toString(StandardCharsets.UTF_8);
    String yearly = output.substring(output.indexOf("Counts by year:"));
    String normalized = yearly.replaceAll("[ \\t]+", " ");
    assertTrue(normalized.contains("Year Added Modified Deleted"));
    assertTrue(normalized.contains("2024 0 1 1"));
    assertTrue(normalized.contains("2026 2 1 0"));
    assertFalse(normalized.contains("2025"));
    assertTrue(normalized.indexOf("2024") < normalized.indexOf("2026"));
  }

  @Test
  public void exportsJsonWithOnlyThePresentSides() throws Exception {
    Comptage before = comptage("A", 2025, 50);
    Comptage after = comptage("A", 2025, 60);
    StringWriter writer = new StringWriter();
    ComptageDiffProgram.writeDiff(List.of(new ComptageDiffer.Diff(null, after),
        new ComptageDiffer.Diff(before, null), new ComptageDiffer.Diff(before, after)), writer);
    com.fasterxml.jackson.databind.JsonNode years = new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(writer.toString());
    assertEquals(1, years.size());
    com.fasterxml.jackson.databind.JsonNode group = years.get("2025");
    assertEquals(1, group.get("added").asInt());
    assertEquals(1, group.get("modified").asInt());
    assertEquals(1, group.get("deleted").asInt());
    com.fasterxml.jackson.databind.JsonNode records = group.get("changes");
    assertEquals(3, records.size());
    assertFalse(records.get(0).has("before"));
    assertEquals(60, records.get(0).get("after").get("v85Speed").asInt());
    assertFalse(records.get(1).has("after"));
    assertEquals("A", records.get(1).get("before").get("codeCentrale").asText());
    assertEquals(50, records.get(2).get("before").get("v85Speed").asInt());
    assertEquals(60, records.get(2).get("after").get("v85Speed").asInt());
    assertEquals("v85Speed", records.get(2).get("changedFields").asText());
    assertFalse(records.get(0).has("changedFields"));
    assertFalse(records.get(1).has("changedFields"));
  }

  @Test
  public void groupsJsonByYearWithMatchingCounts() throws Exception {
    List<ComptageDiffer.Diff> differences = List.of(
        new ComptageDiffer.Diff(null, comptage("A", 2026, 50)),
        new ComptageDiffer.Diff(comptage("B", 2024, 50), null),
        new ComptageDiffer.Diff(comptage("C", 2026, 50), comptage("C", 2026, 60)));
    StringWriter writer = new StringWriter();
    ComptageDiffProgram.writeDiff(differences, writer);
    com.fasterxml.jackson.databind.JsonNode years = new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(writer.toString());
    assertEquals("2024", years.fieldNames().next());
    assertEquals(2, years.size());
    assertEquals(1, years.get("2024").get("deleted").asInt());
    assertEquals(0, years.get("2024").get("added").asInt());
    assertEquals(1, years.get("2024").get("changes").size());
    assertEquals(1, years.get("2026").get("added").asInt());
    assertEquals(1, years.get("2026").get("modified").asInt());
    assertEquals(2, years.get("2026").get("changes").size());
    StringWriter empty = new StringWriter();
    ComptageDiffProgram.writeDiff(List.of(), empty);
    assertEquals("{}", empty.toString().replaceAll("\\s", ""));
  }

  @Test
  public void marksAdditionsModificationsAndRemovalsAcrossFields() {
    M3MComptage before = comptage("A", 2025, 50);
    before.setCityName("Old city");
    before.setMeanSpeed(40);
    before.setStreetName("Old street");
    M3MComptage after = comptage("A", 2025, 0);
    after.setCityName("New city");
    after.setAltStreetName("New alternative");
    after.setStreetName(" ");
    after.setMeanSpeed(45);
    after.setMaximumSpeed(30);
    after.setMoyenneJourAnnuelle(200);
    assertEquals("+altStreetName,cityName,+maximumSpeed,meanSpeed,"
        + "+moyenneJourAnnuelle,-streetName,-v85Speed",
        new ComptageDiffer.Diff(before, after).changedFields());
    assertEquals("-altStreetName,cityName,-maximumSpeed,meanSpeed,"
        + "-moyenneJourAnnuelle,+streetName,+v85Speed",
        new ComptageDiffer.Diff(after, before).changedFields());
  }

  @Test
  public void ignoresEquivalentMissingValuesAndReportsDirectionTypeChanges() {
    M3MComptage before = comptage("A", 2025, 0);
    M3MComptage after = comptage("A", 2025, 0);
    after.setCityName("");
    assertEquals("", new ComptageDiffer.Diff(before, after).changedFields());
    after.setCodeCanal("Z");
    assertEquals("+codeCanal,directionType", new ComptageDiffer.Diff(before, after).changedFields());
    assertNull(new ComptageDiffer.Diff(null, after).changedFields());
    assertNull(new ComptageDiffer.Diff(before, null).changedFields());
  }

  private static M3MComptage comptage(String centralId, int year, int v85) {
    M3MComptage comptage = new M3MComptage();
    comptage.setCodeCentrale(centralId);
    comptage.setAnnee(year);
    comptage.setV85Speed(v85);
    return comptage;
  }
}
