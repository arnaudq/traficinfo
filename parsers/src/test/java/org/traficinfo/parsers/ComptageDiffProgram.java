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
import java.util.Set;
import java.util.TreeSet;

/** Run manually with no arguments for the two MMM snapshots in ~/Downloads. */
public final class ComptageDiffProgram {
  private static final List<String> SUMMARY_FIELDS = List.of("meanSpeed", "moyenneHeureDePointeMatin",
      "moyenneHeureDePointeSoir", "moyenneJourAnnuelle", "v85Speed");

  private ComptageDiffProgram() {
  }

  /** Optionally supply CSV paths, comma-separated years, and an output JSON path. */
  public static void main(String[] args) throws IOException {
    if (args.length != 0 && args.length != 2 && args.length != 3 && args.length != 4) {
      throw new IllegalArgumentException("Usage: ComptageDiffProgram [before.csv after.csv [2024,2025 [diff.json]]]");
    }
    Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
    Path before = args.length >= 2 ? Path.of(args[0])
        : downloads.resolve("MMM_MMM_Comptage_20221031.csv");
    Path after = args.length >= 2 ? Path.of(args[1])
        : downloads.resolve("MMM_MMM_Comptage_20240312.csv");
    Set<Integer> years = args.length >= 3 ? parseYears(args[2]) : Set.of(2011,2012,2013,2014,2015,2016,2017,2018,2019,2020,2021);
    List<ComptageDiffer.Diff> differences = ComptageDiffer.diff(
        filterYears(parse(before), years), filterYears(parse(after), years),
        M3MComptage::getCodeCanal);
    System.out.println("Before: " + before);
    System.out.println("After: " + after);
    if (!years.isEmpty()) {
      System.out.println("Years: " + years);
    }
    printSummary(differences, System.out);
    Path output = args.length == 4 ? Path.of(args[3]) : downloads.resolve("diff.json");
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
      group.put("fieldSummary", fieldSummary(changes));
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

  static Set<Integer> parseYears(String argument) {
    Set<Integer> years = new TreeSet<>();
    for (String token : argument.split(",", -1)) {
      try {
        int year = Integer.parseInt(token.trim());
        if (year <= 0) {
          throw new NumberFormatException();
        }
        years.add(year);
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("Expected comma-separated positive years, got: " + argument, exception);
      }
    }
    return years;
  }

  static List<M3MComptage> filterYears(List<M3MComptage> records, Set<Integer> years) {
    return years.isEmpty() ? records : records.stream()
        .filter(record -> years.contains(record.getAnnee())).toList();
  }

  static Map<String, YearCounts> fieldSummary(List<ComptageDiffer.Diff> differences) {
    Map<String, YearCounts> summary = new LinkedHashMap<>();
    SUMMARY_FIELDS.forEach(field -> summary.put(field, new YearCounts()));
    for (ComptageDiffer.Diff difference : differences) {
      difference.fieldChanges().forEach((field, change) -> {
        YearCounts counts = summary.get(field);
        if (counts != null) {
          switch (change) {
            case ADDED -> counts.added++;
            case MODIFIED -> counts.modified++;
            case DELETED -> counts.deleted++;
          }
        }
      });
    }
    return summary;
  }

  static void printSummary(List<ComptageDiffer.Diff> differences, PrintStream out) {
    YearCounts total = countChanges(differences);
    out.printf("Comptage added: %d%nComptage removed: %d%nComptage modified: %d%n",
        total.added, total.deleted, total.modified);
    out.println("Field summary:");
    printFieldSummary(differences, out);
    Map<Integer, List<ComptageDiffer.Diff>> years = groupByYear(differences);
    out.println("Counts by year:");
    out.printf("%4s %8s %8s %8s%n", "Year", "Added", "Modified", "Deleted");
    years.forEach((year, changes) -> {
      YearCounts counts = countChanges(changes);
      out.printf("%4d %8d %8d %8d%n", year, counts.added, counts.modified, counts.deleted);
    });
    years.forEach((year, changes) -> {
      out.printf("Field summary for %d:%n", year);
      printFieldSummary(changes, out);
    });
  }

  private static void printFieldSummary(List<ComptageDiffer.Diff> differences, PrintStream out) {
    out.printf("%-30s %8s %8s %8s%n", "Field", "Added", "Modified", "Deleted");
    fieldSummary(differences).forEach((field, counts) -> out.printf("%-30s %8d %8d %8d%n",
        field, counts.added, counts.modified, counts.deleted));
  }

  static final class YearCounts {
    public int added;
    public int modified;
    public int deleted;
  }
}
