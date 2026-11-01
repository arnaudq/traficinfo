package org.traficinfo.parsers;

import org.traficinfo.parsers.mrn.MRNComptage;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Manual count-coverage audit of parsed records, before map filtering or merging. */
public final class ComptageMeasurementProgram {
  private static final Pattern PEAK_VOLUME = Pattern.compile(
      "^\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*(?:\\(.*\\))?\\s*$");

  private ComptageMeasurementProgram() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 0 && args.length != 2) {
      throw new IllegalArgumentException("Usage: ComptageMeasurementProgram [datasource file]");
    }
    GeoJsonParser.DataSource source = dataSource(args.length == 0 ? "MRN" : args[0]);
    Path file = expandHome(args.length == 0 ? "~/Downloads/MRN_Comptage.csv" : args[1]);
    List<? extends Comptage> records = source.getParser().parseComptages(file.toFile(), new Properties());
    System.out.println("Data source: " + source.getId());
    System.out.println("File: " + file.toAbsolutePath());
    printSummary(records, System.out);
  }

  static GeoJsonParser.DataSource dataSource(String argument) {
    return Arrays.stream(GeoJsonParser.DataSource.values())
        .filter(source -> source.getId().equalsIgnoreCase(argument)
            || source.name().equalsIgnoreCase(argument))
        .findFirst().orElseThrow(() -> new IllegalArgumentException(
            "Unknown data source: " + argument + "; expected MMM, DPT34, DPT30, MRN or NIM"));
  }

  private static Path expandHome(String argument) {
    return argument.startsWith("~/")
        ? Path.of(System.getProperty("user.home"), argument.substring(2)) : Path.of(argument);
  }

  static boolean hasSignificantCount(Comptage count) {
    if (count.getMoyenneJourAnnuelle() > 0
        || positivePeak(count.getMoyenneHeureDePointeMatin())
        || positivePeak(count.getMoyenneHeureDePointeSoir())) {
      return true;
    }
    if (count instanceof MRNComptage mrn) {
      return positive(mrn.getTvTmjo()) || positive(mrn.getTvHpm()) || positive(mrn.getTvHps())
          || positive(mrn.getUvpTmja()) || positive(mrn.getUvpTmjo())
          || positive(mrn.getUvpHpm()) || positive(mrn.getUvpHps());
    }
    return false;
  }

  private static boolean positive(Integer volume) {
    return volume != null && volume > 0;
  }

  private static boolean positivePeak(String value) {
    if (value == null) return false;
    var match = PEAK_VOLUME.matcher(value);
    return match.matches() && Double.parseDouble(match.group(1)) > 0;
  }

  static void printSummary(List<? extends Comptage> records, PrintStream out) {
    out.println("Significant count: positive daily average or peak volume; MRN includes TV/UVP TMJA, TMJO, HPM, HPS.");
    out.println("Speeds, percentages and peak-hour labels alone do not count as volumes.");
    out.println("Parsed records across all years; no deduplication or map filters. Parser-skipped rows are excluded.");
    out.printf("%-15s %9s %10s %10s %10s %14s%n",
        "Type", "Records", "With count", "No count", "No count %", "No annual avg");
    var groups = new EnumMap<Comptage.Type, long[]>(Comptage.Type.class);
    for (Comptage.Type type : Comptage.Type.values()) groups.put(type, new long[3]);
    long[] total = new long[3];
    for (Comptage count : records) {
      long[] group = groups.get(count.getType() == null ? Comptage.Type.UNKNOWN : count.getType());
      group[0]++;
      if (!hasSignificantCount(count)) group[1]++;
      if (count.getMoyenneJourAnnuelle() <= 0) group[2]++;
    }
    groups.forEach((type, counts) -> {
      printRow(out, type.name(), counts);
      for (int i = 0; i < total.length; i++) total[i] += counts[i];
    });
    printRow(out, "TOTAL", total);
  }

  private static void printRow(PrintStream out, String label, long[] counts) {
    out.printf(Locale.ROOT, "%-15s %9d %10d %10d %9.2f%% %14d%n", label,
        counts[0], counts[0] - counts[1], counts[1],
        counts[0] == 0 ? 0.0 : 100.0 * counts[1] / counts[0], counts[2]);
  }
}
