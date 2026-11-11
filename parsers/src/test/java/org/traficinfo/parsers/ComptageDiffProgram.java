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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
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
import java.util.Properties;

/** Compare snapshots for any registered data source; no arguments use the configured MRN snapshots. */
public final class ComptageDiffProgram {
  private static final List<String> SUMMARY_FIELDS = List.of("meanSpeed", "moyenneHeureDePointeMatin",
      "moyenneHeureDePointeSoir", "moyenneJourAnnuelle", "v85Speed");

  private ComptageDiffProgram() {
  }

  /** Supply a data source ID, CSV paths, optional years and optional output path. */
  public static void main(String[] args) throws IOException {
    if (args.length != 0 && args.length != 3 && args.length != 4 && args.length != 5) {
      throw new IllegalArgumentException(
          "Usage: ComptageDiffProgram [datasource before.csv after.csv [2024,2025 [diff.json]]]");
    }
    GeoJsonParser.DataSource source = GeoJsonParser.DataSource.fromId(args.length == 0 ? "MRN" : args[0]);
    Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
    Path before = args.length >= 3 ? expandHome(args[1])
        : downloads.resolve("MRN_Comptage_20261022.csv");
    Path after = args.length >= 3 ? expandHome(args[2])
        : downloads.resolve("MRN_Comptage.csv");
    Set<Integer> years = args.length >= 4 ? parseYears(args[3]) : Set.of();
    List<ComptageDiffer.Diff> differences = ComptageDiffer.diff(source,
        filterYears(parse(source, before), years), filterYears(parse(source, after), years));
    System.out.println("Data source: " + source.getId());
    System.out.println("Before: " + before);
    System.out.println("After: " + after);
    if (!years.isEmpty()) {
      System.out.println("Years: " + years);
    }
    printSummary(differences, System.out);
    Path output = args.length == 5 ? expandHome(args[4]) : downloads.resolve("diff.json");
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

  private static List<? extends Comptage> parse(GeoJsonParser.DataSource source, Path file) throws IOException {
    return source.getParser().parseComptages(file.toFile(), new Properties());
  }

  private static Path expandHome(String argument) {
    return argument.startsWith("~/")
        ? Path.of(System.getProperty("user.home"), argument.substring(2)) : Path.of(argument);
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

  static <T extends Comptage> List<T> filterYears(List<T> records, Set<Integer> years) {
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
