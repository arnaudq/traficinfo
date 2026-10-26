package org.traficinfo.parsers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.m3m.M3MComptageParser;
import org.traficinfo.parsers.m3m.M3MComptage;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Run manually with no arguments for the two MMM snapshots in ~/Downloads. */
public final class ComptageDiffProgram {
  private ComptageDiffProgram() {
  }

  /** Optionally supply before/after CSV paths and an output JSON path. */
  public static void main(String[] args) throws IOException {
    if (args.length != 0 && args.length != 2 && args.length != 3) {
      throw new IllegalArgumentException("Usage: ComptageDiffProgram [before.csv after.csv [diff.json]]");
    }
    Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
    Path before = args.length >= 2 ? Path.of(args[0])
        : downloads.resolve("MMM_MMM_Comptage_20260302.csv");
    Path after = args.length >= 2 ? Path.of(args[1])
        : downloads.resolve("MMM_MMM_Comptage_20260430.csv");
    List<ComptageDiffer.Diff> differences = ComptageDiffer.diff(parse(before), parse(after),
        M3MComptage::getCodeCanal);
    System.out.println("Before: " + before);
    System.out.println("After: " + after);
    printSummary(differences, System.out);
    Path output = args.length == 3 ? Path.of(args[2]) : downloads.resolve("diff.json");
    try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
      writeDiff(differences, writer);
    }
    System.out.println("Diff: " + output);
  }

  /** Writes year groups containing counts and changes, ordered by year. */
  static void writeDiff(List<ComptageDiffer.Diff> differences, Writer writer) throws IOException {
    Map<Integer, Map<String, Object>> years = new TreeMap<>();
    groupByYear(differences).forEach((year, changes) -> {
      YearCounts counts = countChanges(changes);
      Map<String, Object> group = new LinkedHashMap<>();
      group.put("added", counts.added);
      group.put("modified", counts.modified);
      group.put("deleted", counts.deleted);
      List<Map<String, Object>> records = new ArrayList<>();
      for (ComptageDiffer.Diff difference : changes) {
        Map<String, Object> record = new LinkedHashMap<>();
        if (difference.before() != null) {
          record.put("before", difference.before());
        }
        if (difference.after() != null) {
          record.put("after", difference.after());
        }
        if (difference.before() != null && difference.after() != null) {
          record.put("changedFields", difference.changedFields());
        }
        records.add(record);
      }
      group.put("changes", records);
      years.put(year, group);
    });
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(writer, years);
  }

  private static Map<Integer, List<ComptageDiffer.Diff>> groupByYear(List<ComptageDiffer.Diff> differences) {
    Map<Integer, List<ComptageDiffer.Diff>> years = new TreeMap<>();
    for (ComptageDiffer.Diff difference : differences) {
      Comptage comptage = difference.before() == null ? difference.after() : difference.before();
      years.computeIfAbsent(comptage.getAnnee(), ignored -> new ArrayList<>()).add(difference);
    }
    return years;
  }

  private static YearCounts countChanges(List<ComptageDiffer.Diff> differences) {
    YearCounts counts = new YearCounts();
    for (ComptageDiffer.Diff difference : differences) {
      if (difference.before() == null) {
        counts.added++;
      } else if (difference.after() == null) {
        counts.deleted++;
      } else {
        counts.modified++;
      }
    }
    return counts;
  }

  private static List<M3MComptage> parse(Path file) throws IOException {
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return new M3MComptageParser().parseComptages(reader);
    }
  }

  static void printSummary(List<ComptageDiffer.Diff> differences, PrintStream out) {
    int added = 0;
    int removed = 0;
    int modified = 0;
    int v85Modified = 0;
    int v85Deleted = 0;
    Map<Integer, YearCounts> byYear = new TreeMap<>();
    groupByYear(differences).forEach((year, changes) -> byYear.put(year, countChanges(changes)));
    for (ComptageDiffer.Diff difference : differences) {
      Comptage before = difference.before();
      Comptage after = difference.after();
      if (before == null) {
        added++;
      } else {
        if (after == null) {
          removed++;
        } else {
          modified++;
        }
        // MMM maps a blank V85 to zero. Removed records also lose their V85.
        if (before.getV85Speed() > 0 && (after == null || after.getV85Speed() <= 0)) {
          v85Deleted++;
        } else if (after != null && after.getV85Speed() > 0
            && before.getV85Speed() != after.getV85Speed()) {
          v85Modified++;
        }
      }
    }
    out.printf("Comptage added: %d%nComptage removed: %d%nComptage modified: %d%n",
        added, removed, modified);
    out.printf("V85 modified: %d%nV85 deleted: %d%nV85 modified or deleted: %d%n",
        v85Modified, v85Deleted, v85Modified + v85Deleted);
    out.println("Counts by year:");
    out.printf("%4s %8s %8s %8s%n", "Year", "Added", "Modified", "Deleted");
    byYear.forEach((year, counts) -> out.printf("%4d %8d %8d %8d%n",
        year, counts.added, counts.modified, counts.deleted));
  }

  private static final class YearCounts {
    private int added;
    private int modified;
    private int deleted;
  }
}
