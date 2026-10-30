package org.traficinfo.parsers.nim;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;
import org.geojson.LngLatAlt;
import org.traficinfo.parsers.GeoJsonUtils;
import org.traficinfo.parsers.nim.NIMComptageParser.Location;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Manual audit of location variants, without stopping at the first conflict or changing counts. */
public final class NIMLocationAuditProgram {
  private static final List<String> INPUTS = List.of(
      "NIM_Comptages-2020-2021.csv", "NIM_Comptages-2022.csv", "NIM_Comptages.csv");

  private NIMLocationAuditProgram() {}

  public static void main(String[] args) throws IOException {
    if (args.length > 2) throw new IllegalArgumentException(
        "Usage: NIMLocationAuditProgram [input-directory [report.csv]]");
    Path directory = args.length > 0 ? Path.of(args[0])
        : Path.of(System.getProperty("user.home"), "Downloads", "NIM");
    Path output = args.length > 1 ? Path.of(args[1]) : Path.of("nim-location-audit.csv");
    for (String name : INPUTS) {
      if (directory.resolve(name).toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
        throw new IllegalArgumentException("Report must not overwrite an input");
      }
    }
    Audit audit = new Audit();
    for (String name : INPUTS) {
      try (Reader reader = Files.newBufferedReader(directory.resolve(name), StandardCharsets.UTF_8)) {
        long before = audit.rows;
        audit.read(reader, name);
        System.out.printf("%s: %,d rows%n", name, audit.rows - before);
      }
    }
    List<Change> changes = audit.changes();
    try (CSVWriter writer = new CSVWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
      writer.writeNext(new String[]{"camera_id", "category", "distance_metres", "metadata_changed",
          "same_date_count", "first_shared_date", "last_shared_date", "before_first_date", "before_last_date",
          "after_first_date", "after_last_date", "before_location", "after_location", "before_files", "after_files",
          "shared_dates"});
      for (Change change : changes) writer.writeNext(change.csv());
      writer.flush();
      if (writer.checkError()) throw new IOException("Failed to write report: " + output);
    }
    System.out.printf("Total: %,d rows; %,d ignored 1970 rows; %d distinct cameras%n",
        audit.rows, audit.ignored1970, audit.cameras.size());
    Set<String> affected = new TreeSet<>();
    Set<String> rejected = new TreeSet<>();
    for (Category category : Category.values()) {
      Set<String> cameras = new TreeSet<>();
      Set<String> sameDay = new TreeSet<>();
      for (Change change : changes) {
        if (change.category() != category) continue;
        cameras.add(change.camera());
        affected.add(change.camera());
        if (!change.sharedDates().isEmpty()) {
          sameDay.add(change.camera());
          if (change.conflicts()) rejected.add(change.camera());
        }
      }
      System.out.printf("%s: %d cameras (%d with same-date variants); IDs=%s%n",
          category, cameras.size(), sameDay.size(), cameras);
    }
    System.out.printf("Any location change: %d distinct cameras%n", affected.size());
    System.out.printf("Same-date coordinate conflicts in pairwise comparison: %d distinct cameras; IDs=%s%n",
        rejected.size(), rejected);
    for (String camera : rejected) {
      TreeSet<LocalDate> dates = new TreeSet<>();
      for (Change change : changes) {
        if (change.camera().equals(camera) && change.conflicts()) {
          dates.addAll(change.sharedDates());
        }
      }
      System.out.printf("Camera %s: %d conflicting dates: %s%n", camera, dates.size(), dates);
    }
    System.out.println("Categories may overlap per camera. Relocations are candidates, not confirmed moves.");
    System.out.println("Report: " + output.toAbsolutePath());
  }

  enum Category { WITHIN_TOLERANCE, RELOCATION_CANDIDATE, METADATA_OR_COORDINATE_PRESENCE_CHANGE }

  static final class Observation {
    final Location location;
    final TreeSet<LocalDate> dates = new TreeSet<>();
    final Set<String> files = new TreeSet<>();

    Observation(Location location) { this.location = location; }
    LocalDate first() { return dates.first(); }
    LocalDate last() { return dates.last(); }
  }

  record Change(String camera, Observation before, Observation after, Category category,
                Double distance, boolean metadataChanged, TreeSet<LocalDate> sharedDates) {
    boolean conflicts() { return !before.location.matches(after.location); }

    String[] csv() {
      return new String[]{camera, category.name(), distance == null ? "" : String.format(Locale.ROOT, "%.3f", distance),
          Boolean.toString(metadataChanged), Integer.toString(sharedDates.size()),
          sharedDates.isEmpty() ? "" : sharedDates.first().toString(),
          sharedDates.isEmpty() ? "" : sharedDates.last().toString(),
          before.first().toString(), before.last().toString(), after.first().toString(), after.last().toString(),
          before.location.toString(), after.location.toString(), String.join(";", before.files), String.join(";", after.files),
          String.join(";", sharedDates.stream().map(LocalDate::toString).toList())};
    }
  }

  static final class Audit {
    final Map<String, Map<Location, Observation>> cameras = new TreeMap<>();
    long rows;
    long ignored1970;

    void read(Reader source, String file) throws IOException {
      PushbackReader reader = new PushbackReader(source, 1);
      int first = reader.read();
      if (first != -1 && first != '\uFEFF') reader.unread(first);
      CSVReader csv = new CSVReaderBuilder(reader)
          .withCSVParser(new CSVParserBuilder().withSeparator(';').build()).build();
      long rowNumber = 1;
      try {
        String[] header = csv.readNext();
        if (header == null) throw new IOException("Missing NIM CSV header: " + file);
        Map<String, Integer> columns = NIMComptageParser.columns(header);
        String[] row;
        while ((row = csv.readNext()) != null) {
          rowNumber++;
          if (row.length == 1 && row[0].isBlank()) continue;
          rows++;
          if (row.length != header.length) throw new IOException("Invalid column count");
          String camera = NIMComptageParser.value(row, columns, "idcamera");
          if (camera == null || !camera.matches("[0-9]+")) throw new IllegalArgumentException("Invalid id_camera");
          LocalDate date = LocalDate.parse(NIMComptageParser.value(row, columns, "date"));
          if (date.getYear() == 1970) { ignored1970++; continue; }
          Location location = NIMComptageParser.location(row, columns);
          Observation observation = cameras.computeIfAbsent(camera, ignored -> new HashMap<>())
              .computeIfAbsent(location, Observation::new);
          observation.dates.add(date);
          observation.files.add(file);
        }
      } catch (CsvValidationException | IllegalArgumentException | java.time.DateTimeException | NullPointerException invalid) {
        throw new IOException(file + " row " + rowNumber + ": " + invalid.getMessage(), invalid);
      } catch (IOException invalid) {
        throw new IOException(file + " row " + rowNumber + ": " + invalid.getMessage(), invalid);
      }
    }

    List<Change> changes() {
      List<Change> changes = new ArrayList<>();
      for (var camera : cameras.entrySet()) {
        List<Observation> variants = new ArrayList<>(camera.getValue().values());
        variants.sort(java.util.Comparator.comparing(Observation::first)
            .thenComparing(value -> value.location.toString()));
        for (int i = 0; i < variants.size(); i++) {
          for (int j = i + 1; j < variants.size(); j++) {
            Observation before = variants.get(i);
            Observation after = variants.get(j);
            Location a = before.location;
            Location b = after.location;
            Double distance = a.latitude() == null || b.latitude() == null ? null
                : GeoJsonUtils.distanceMeters(new LngLatAlt(a.longitude(), a.latitude()),
                    new LngLatAlt(b.longitude(), b.latitude()));
            boolean metadataChanged = !Objects.equals(a.insee(), b.insee()) || !Objects.equals(a.city(), b.city())
                || !Objects.equals(a.position(), b.position());
            Category category = distance != null && distance > Location.COORDINATE_TOLERANCE_METRES
                ? Category.RELOCATION_CANDIDATE
                : metadataChanged || distance == null ? Category.METADATA_OR_COORDINATE_PRESENCE_CHANGE
                : Category.WITHIN_TOLERANCE;
            TreeSet<LocalDate> shared = new TreeSet<>(before.dates);
            shared.retainAll(after.dates);
            changes.add(new Change(camera.getKey(), before, after, category, distance, metadataChanged, shared));
          }
        }
      }
      return changes;
    }
  }
}
