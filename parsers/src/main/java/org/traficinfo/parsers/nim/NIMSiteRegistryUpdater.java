package org.traficinfo.parsers.nim;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.traficinfo.parsers.nim.NIMComptageParser.Location;
import org.traficinfo.parsers.nim.NIMSiteRegistry.Site;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Explicitly discovers sites from a local CSV and updates the reviewed registry, never during parsing. */
public final class NIMSiteRegistryUpdater {
  private NIMSiteRegistryUpdater() {}

  /** Supply a CSV and optionally the registry path; a missing registry starts an empty one. */
  public static void main(String[] args) throws IOException {
    if (args.length < 1 || args.length > 2) throw new IllegalArgumentException(
        "Usage: NIMSiteRegistryUpdater input.csv [parsers/src/main/resources/nim-sites.json]");
    Path input = Path.of(args[0]);
    Path registry = args.length > 1 ? Path.of(args[1]) : Path.of("parsers/src/main/resources/nim-sites.json");
    update(input, registry);
  }

  public static NIMSiteRegistry update(Path input, Path path) throws IOException {
    Path target = path.toAbsolutePath().normalize();
    if (input.toAbsolutePath().normalize().equals(target) || (Files.exists(target) && Files.isSameFile(input, target))) {
      throw new IOException("Input CSV and site registry must be different files");
    }
    NIMSiteRegistry previous = Files.exists(target) ? NIMSiteRegistry.read(target) : new NIMSiteRegistry(List.of());
    NIMSiteRegistry next;
    try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
      next = discover(previous, reader);
    }
    // Replace only after all input, assignments and serialization have succeeded.
    byte[] bytes = NIMSiteRegistry.serialize(next);
    Path temporary = Files.createTempFile(target.getParent(), ".nim-sites-", ".json");
    try {
      Files.write(temporary, bytes);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
    System.out.printf("Registry: %s%nSites: %d (%d added); cameras: %d; cameras with multiple sites: %s%n",
        target, next.sites().size(), next.sites().size() - previous.sites().size(),
        next.sites().stream().map(Site::cameraId).distinct().count(),
        new TreeSet<>(next.sites().stream().filter(Site::cameraMoved).map(Site::cameraId).toList()));
    return next;
  }

  private record Key(String camera, Location location) {}
  private static final class Observed {
    final Key key;
    LocalDate first;
    LocalDate last;
    Observed(Key key, LocalDate date) { this.key = key; first = date; last = date; }
    void add(LocalDate date) {
      if (date.isBefore(first)) first = date;
      if (date.isAfter(last)) last = date;
    }
  }

  static NIMSiteRegistry discover(NIMSiteRegistry previous, Reader source) throws IOException {
    Map<Key, Observed> observations = readLocations(source);
    List<Observed> ordered = new ArrayList<>(observations.values());
    ordered.sort(Comparator.comparing((Observed value) -> value.key.camera)
        .thenComparing(value -> value.first)
        .thenComparing(value -> value.key.location.latitude())
        .thenComparing(value -> value.key.location.longitude())
        .thenComparing(value -> value.key.location.toString()));
    List<Site> sites = new ArrayList<>(previous.sites());
    NIMSiteRegistry registry = previous;
    for (Observed observed : ordered) {
      String camera = observed.key.camera;
      Location location = observed.key.location;
      Site site = registry.find(camera, location.latitude(), location.longitude());
      if (site == null) {
        int number = sites.stream().filter(value -> value.cameraId().equals(camera))
            .mapToInt(value -> Integer.parseInt(value.codeCentrale().substring(value.codeCentrale().lastIndexOf("_S") + 2)))
            .max().orElse(0) + 1;
        site = new Site("NIM_" + camera + "_S" + String.format(Locale.ROOT, "%02d", number), camera,
            location.latitude(), location.longitude(), false, observed.first.toString(), observed.last.toString(),
            List.of(), List.of(), List.of());
        sites.add(site);
      }
      Site updated = new Site(site.codeCentrale(), camera, site.latitude(), site.longitude(), site.cameraMoved(),
          LocalDate.parse(site.firstSeen()).isBefore(observed.first) ? site.firstSeen() : observed.first.toString(),
          LocalDate.parse(site.lastSeen()).isAfter(observed.last) ? site.lastSeen() : observed.last.toString(),
          labels(site.positions(), location.position()), labels(site.inseeCodes(), location.insee()), labels(site.communes(), location.city()));
      String id = site.codeCentrale();
      sites.removeIf(value -> value.codeCentrale().equals(id));
      sites.add(updated);
      registry = new NIMSiteRegistry(sites);
    }
    // Later reference sites must not make any previously observed point ambiguous.
    for (Observed observed : ordered) {
      registry.find(observed.key.camera, observed.key.location.latitude(), observed.key.location.longitude());
    }
    return registry;
  }

  private static List<String> labels(List<String> previous, String value) {
    TreeSet<String> labels = new TreeSet<>(previous);
    if (value != null) labels.add(value);
    return List.copyOf(labels);
  }

  private static Map<Key, Observed> readLocations(Reader source) throws IOException {
    PushbackReader reader = new PushbackReader(source, 1);
    int first = reader.read();
    if (first != -1 && first != '\uFEFF') reader.unread(first);
    CSVReader csv = new CSVReaderBuilder(reader)
        .withCSVParser(new CSVParserBuilder().withSeparator(';').build()).build();
    Map<Key, Observed> observations = new HashMap<>();
    long rowNumber = 1;
    try {
      String[] header = csv.readNext();
      if (header == null) throw new IOException("Missing NIM CSV header");
      Map<String, Integer> columns = NIMComptageParser.columns(header);
      String[] row;
      while ((row = csv.readNext()) != null) {
        rowNumber++;
        if (row.length == 1 && row[0].isBlank()) continue;
        if (row.length != header.length) throw new IOException("Invalid NIM CSV column count");
        String camera = NIMComptageParser.value(row, columns, "idcamera");
        if (camera == null || !camera.matches("[0-9]+")) throw new IllegalArgumentException("Invalid id_camera");
        String rawDate = NIMComptageParser.value(row, columns, "date");
        if (rawDate == null) throw new IllegalArgumentException("Missing date");
        LocalDate date = LocalDate.parse(rawDate);
        if (date.getYear() == 1970) continue;
        Location location = NIMComptageParser.location(row, columns);
        if (location.latitude() == null) throw new IOException("Missing coordinates for camera " + camera);
        Key key = new Key(camera, location);
        observations.computeIfAbsent(key, ignored -> new Observed(key, date)).add(date);
      }
    } catch (CsvValidationException | IllegalArgumentException | java.time.DateTimeException invalid) {
      throw new IOException("NIM CSV row " + rowNumber + ": " + invalid.getMessage(), invalid);
    } catch (IOException invalid) {
      throw new IOException("NIM CSV row " + rowNumber + ": " + invalid.getMessage(), invalid);
    }
    return observations;
  }
}
