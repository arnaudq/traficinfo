package org.traficinfo.parsers.nim;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.geojson.LngLatAlt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.ComptageParser;
import org.traficinfo.parsers.GeoJsonUtils;
import org.traficinfo.parsers.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Streams hourly source rows into yearly camera counts. */
public final class NIMComptageParser implements ComptageParser {
  private static final Logger LOGGER = LoggerFactory.getLogger(NIMComptageParser.class);
  public static final String COUNTER_PREFIX = "NIM_";
  public enum ConflictPolicy { REJECT, EXCLUDE_HOUR }

  private final Logger logger;
  private final ConflictPolicy conflictPolicy;

  public NIMComptageParser() {
    this(ConflictPolicy.EXCLUDE_HOUR);
  }

  public NIMComptageParser(ConflictPolicy conflictPolicy) {
    this(LOGGER, conflictPolicy);
  }

  NIMComptageParser(Logger logger) {
    this(logger, ConflictPolicy.EXCLUDE_HOUR);
  }

  NIMComptageParser(Logger logger, ConflictPolicy conflictPolicy) {
    this.logger = java.util.Objects.requireNonNull(logger);
    this.conflictPolicy = java.util.Objects.requireNonNull(conflictPolicy);
  }

  private static final Pattern CAMERA_ID = Pattern.compile("[0-9]+");
  private static final Pattern HOUR = Pattern.compile("(\\d{2}):00\\s*-\\s*(\\d{2}):00");
  private static final int COMPLETE_DAY_MASK = (1 << 24) - 1;

  @Override
  public List<NIMComptage> parseComptages(Reader reader) throws IOException {
    return parseComptages(reader, new Properties());
  }

  @Override
  public List<NIMComptage> parseComptages(Reader source, Properties displayNames) throws IOException {
    PushbackReader reader = new PushbackReader(source, 1);
    int first = reader.read();
    if (first != -1 && first != '\uFEFF') reader.unread(first);
    // Do not close the caller's reader. Only daily aggregates, not raw CSV rows, are retained.
    CSVReader csv = new CSVReaderBuilder(reader)
        .withCSVParser(new CSVParserBuilder().withSeparator(';').build()).build();
    Map<CameraYear, YearCounts> years = new TreeMap<>();
    long inputRows = 0;
    long ignored1970Rows = 0;
    java.util.Set<String> camerasEncountered = new java.util.HashSet<>();
    try {
      String[] header = csv.readNext();
      if (header == null) throw new IOException("Missing NIM CSV header");
      Map<String, Integer> columns = columns(header);
      String[] row;
      long rowNumber = 1;
      while ((row = csv.readNext()) != null) {
        rowNumber++;
        if (row.length == 1 && row[0].isBlank()) continue;
        inputRows++;
        if (row.length != header.length) throw new IOException("Invalid NIM CSV column count at row " + rowNumber);
        try {
          String camera = required(value(row, columns, "idcamera"), "id_camera");
          if (!CAMERA_ID.matcher(camera).matches()) throw new IllegalArgumentException("Invalid id_camera");
          LocalDate date = LocalDate.parse(required(value(row, columns, "date"), "date"));
          camerasEncountered.add(camera);
          if (date.getYear() == 1970) {
            ignored1970Rows++;
            continue;
          }
          int hour = hour(required(value(row, columns, "tranche"), "tranche"));
          String rawCount = value(row, columns, "nbpassage");
          Integer count = rawCount == null ? null : Integer.valueOf(rawCount);
          if (count != null && count < 0) throw new IllegalArgumentException("Negative nbPassage");
          Location location = location(row, columns);
          CameraYear key = new CameraYear(camera, date.getYear());
          years.computeIfAbsent(key, ignored -> new YearCounts()).add(date, hour, count, location, conflictPolicy);
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
          throw new IOException("Invalid NIM CSV row " + rowNumber + ": " + invalid.getMessage(), invalid);
        }
      }
    } catch (CsvValidationException invalid) {
      throw new IOException("Invalid NIM CSV", invalid);
    }
    java.util.ArrayList<NIMComptage> result = new java.util.ArrayList<>();
    for (var entry : years.entrySet()) result.add(entry.getValue().finish(entry.getKey(), displayNames, logger));
    Map<String, long[]> cameraCoverage = new TreeMap<>();
    for (NIMComptage count : result) {
      long[] coverage = cameraCoverage.computeIfAbsent(count.getCameraId(), ignored -> new long[2]);
      coverage[0] += count.getObservedDays();
      coverage[1] += count.getObservedHours();
    }
    for (var entry : cameraCoverage.entrySet()) {
      long[] coverage = entry.getValue();
      logger.info("NIM camera coverage: camera={}, observedDays={}, observedHours={}, averageObservedHoursPerDay={}",
          COUNTER_PREFIX + entry.getKey(), coverage[0], coverage[1], averageHours(coverage[1], coverage[0]));
    }
    long observedDays = result.stream().mapToLong(NIMComptage::getObservedDays).sum();
    long observedHours = result.stream().mapToLong(NIMComptage::getObservedHours).sum();
    logger.info("NIM summary: camerasEncountered={}, camerasRetained={}, cameraYears={}, observedDays={}, completeDays={}, incompleteDays={}, daysWithoutRecords={}, observedHours={}, averageObservedHoursPerDay={}, conflictingHours={}, inputRows={}, ignored1970Rows={}",
        camerasEncountered.size(), cameraCoverage.size(), result.size(), observedDays,
        result.stream().mapToLong(NIMComptage::getCompleteDays).sum(),
        result.stream().mapToLong(NIMComptage::getIncompleteDays).sum(),
        result.stream().mapToLong(NIMComptage::getDaysWithoutRecords).sum(),
        observedHours, averageHours(observedHours, observedDays),
        result.stream().mapToLong(NIMComptage::getConflictingHours).sum(), inputRows, ignored1970Rows);
    return result;
  }

  private static String averageHours(long hours, long days) {
    return String.format(Locale.ROOT, "%.2f", days == 0 ? 0.0 : (double) hours / days);
  }

  private static Map<String, Integer> columns(String[] header) throws IOException {
    Map<String, Integer> columns = new HashMap<>();
    for (int index = 0; index < header.length; index++) {
      String name = normalize(header[index]);
      if (columns.putIfAbsent(name, index) != null) throw new IOException("Duplicate NIM CSV header: " + header[index]);
    }
    for (String name : List.of("id_camera", "date", "tranche", "nbpassage")) {
      if (!columns.containsKey(normalize(name))) throw new IOException("Missing NIM CSV header: " + name);
    }
    return columns;
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT).replaceAll("[\\s_]", "");
  }

  private static String value(String[] row, Map<String, Integer> columns, String name) {
    Integer index = columns.get(name);
    if (index == null) return null;
    return row[index].isBlank() ? null : row[index].trim();
  }

  private static String required(String value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }

  private static int hour(String value) {
    Matcher match = HOUR.matcher(value);
    if (!match.matches()) throw new IllegalArgumentException("Invalid tranche: " + value);
    int start = Integer.parseInt(match.group(1));
    int end = Integer.parseInt(match.group(2));
    if (start > 23 || (end != start + 1 && !(start == 23 && end == 0))) {
      throw new IllegalArgumentException("Expected a one-hour tranche: " + value);
    }
    return start;
  }

  private static Location location(String[] row, Map<String, Integer> columns) {
    String raw = value(row, columns, "coordonneesgeographique");
    Double latitude = null;
    Double longitude = null;
    if (raw != null) {
      String[] pair = raw.split(",", -1);
      if (pair.length != 2) throw new IllegalArgumentException("Expected latitude, longitude");
      latitude = Double.valueOf(pair[0].trim());
      longitude = Double.valueOf(pair[1].trim());
      if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
          || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
        throw new IllegalArgumentException("Invalid coordinates");
      }
    }
    return new Location(value(row, columns, "codeinsee"), value(row, columns, "commune"),
        value(row, columns, "position"), latitude, longitude);
  }

  private record CameraYear(String camera, int year) implements Comparable<CameraYear> {
    @Override public int compareTo(CameraYear other) {
      int order = camera.compareTo(other.camera);
      return order != 0 ? order : Integer.compare(year, other.year);
    }
  }

  private record Location(String insee, String city, String position, Double latitude, Double longitude) {
    private static final double COORDINATE_TOLERANCE_METRES = 50.0;

    boolean matches(Location other) {
      if (!java.util.Objects.equals(insee, other.insee)
          || !java.util.Objects.equals(city, other.city)
          || !java.util.Objects.equals(position, other.position)) return false;
      if (latitude == null || other.latitude == null) {
        return latitude == null && other.latitude == null;
      }
      double distance = GeoJsonUtils.distanceMeters(new LngLatAlt(longitude, latitude),
          new LngLatAlt(other.longitude, other.latitude));
      return distance <= COORDINATE_TOLERANCE_METRES;
    }
  }

  private static final class DayCounts {
    final int[] counts = new int[24];
    int mask;
    Location location;
    Map<Integer, java.util.SortedSet<Integer>> conflicts = Map.of();

    DayCounts() { Arrays.fill(counts, -1); }

    void add(int hour, Integer count, Location location, ConflictPolicy policy) {
      if (this.location == null) this.location = location;
      else if (!this.location.equals(location)) {
        boolean matches = this.location.matches(location);
        LOGGER.warn("{} location metadata for the same camera/date: before={}, after={}",
            matches ? "Tolerated coordinate difference in" : "Conflicting",
            this.location, location);
        if (!matches) {
          throw new IllegalArgumentException("Conflicting location metadata for the same camera/date");
        }
      }
      if (count == null) return;
      if (conflicts.containsKey(hour)) {
        conflicts.get(hour).add(count);
        return;
      }
      if (counts[hour] >= 0 && counts[hour] != count) {
        if (policy == ConflictPolicy.REJECT) {
          throw new IllegalArgumentException("Conflicting counts for the same camera/date/hour");
        }
        if (conflicts.isEmpty()) conflicts = new TreeMap<>();
        conflicts.put(hour, new java.util.TreeSet<>(List.of(counts[hour], count)));
        counts[hour] = -1;
        mask &= ~(1 << hour);
        return;
      }
      counts[hour] = count;
      mask |= 1 << hour;
    }

    long total() {
      long total = 0;
      for (int count : counts) if (count >= 0) total += count;
      return total;
    }
  }

  private static final class YearCounts {
    final Map<LocalDate, DayCounts> days = new TreeMap<>();
    LocalDate latestDate;
    Location latestLocation;

    void add(LocalDate date, int hour, Integer count, Location location, ConflictPolicy policy) {
      if (latestDate == null || date.isAfter(latestDate)) {
        latestDate = date;
        latestLocation = location;
      }
      DayCounts day = days.computeIfAbsent(date, ignored -> new DayCounts());
      day.add(hour, count, location, policy);
    }

    NIMComptage finish(CameraYear key, Properties displayNames, Logger logger) throws IOException {
      NIMComptage result = new NIMComptage();
      result.setCameraId(key.camera());
      result.setCodeCentrale(COUNTER_PREFIX + key.camera());
      result.setAnnee(key.year());
      result.setCodeInsee(latestLocation.insee());
      result.setCityName(StringUtils.toCamelCase(latestLocation.city()));
      result.setPosition(latestLocation.position());
      result.setLatitude(latestLocation.latitude());
      result.setLongitude(latestLocation.longitude());
      String city = result.getCityName();
      String position = result.getPosition();
      String name = position == null ? city : city == null ? position : position + " - " + city;
      result.setDisplayName(displayNames.getProperty(result.getCodeCentrale(), name == null ? result.getCodeCentrale() : name));
      result.setObservedDays(days.size());
      result.setFirstDate(days.keySet().iterator().next().toString());
      result.setLastDate(latestDate.toString());
      long total = 0;
      long completeTotal = 0;
      int hours = 0;
      int complete = 0;
      int conflicts = 0;
      LocalDate previous = null;
      for (var entry : days.entrySet()) {
        LocalDate date = entry.getKey();
        DayCounts day = entry.getValue();
        if (previous != null && ChronoUnit.DAYS.between(previous, date) > 1) {
          logger.debug("NIM gap: camera={}, from={}, to={}, daysWithoutRecords={} (within observed date range)",
              result.getCodeCentrale(), previous.plusDays(1), date.minusDays(1),
              ChronoUnit.DAYS.between(previous, date) - 1);
        }
        previous = date;
        conflicts += day.conflicts.size();
        for (var conflict : day.conflicts.entrySet()) {
          int hour = conflict.getKey();
          logger.debug("NIM conflicting hour excluded: camera={}, date={}, hour={}, values={}",
              result.getCodeCentrale(), date,
              String.format(Locale.ROOT, "%02d:00 - %02d:00", hour, hour + 1), conflict.getValue());
        }
        total += day.total();
        hours += Integer.bitCount(day.mask);
        if (day.mask == COMPLETE_DAY_MASK) {
          complete++;
          completeTotal += day.total();
        }
      }
      result.setObservedHours(hours);
      result.setTotalPassages(total);
      result.setCompleteDayPassages(completeTotal);
      result.setCompleteDays(complete);
      result.setConflictingHours(conflicts);
      if (complete > 0) {
        long average = Math.round((double) completeTotal / complete);
        if (average > Integer.MAX_VALUE) throw new IOException("NIM daily average exceeds the Comptage integer range");
        result.setDailyAverage((int) average);
      }
      logger.info("NIM camera/year: camera={}, year={}, observedDays={}, completeDays={}, incompleteDays={}, daysWithoutRecords={}, conflictingHours={}, dailyAverage={}",
          result.getCodeCentrale(), key.year(), days.size(), complete, result.getIncompleteDays(),
          result.getDaysWithoutRecords(), conflicts, result.getDailyAverage());
      return result;
    }
  }

  @Override
  public List<NIMComptage> parseComptages(File file, Properties displayNames) throws IOException {
    try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      return parseComptages(reader, displayNames);
    }
  }
}
