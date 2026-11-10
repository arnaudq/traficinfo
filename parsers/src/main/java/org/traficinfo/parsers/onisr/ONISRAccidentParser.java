package org.traficinfo.parsers.onisr;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.traficinfo.parsers.AccidentParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses modern and historical BAAC characteristics CSVs; no network or storage access. */
public final class ONISRAccidentParser implements AccidentParser {
  private static final List<String> REQUIRED = List.of(
      "num_acc", "an", "mois", "jour", "hrmn", "dep", "com", "lat", "long");
  private static final List<String> CODES = List.of("lum", "agg", "int", "atm", "col");
  private final Set<String> departments;

  /** Parse all departments. */
  public ONISRAccidentParser() {
    this(Set.of());
  }

  /** Filter before constructing records; use normalized codes, e.g. 30, 34, 76 or 2A. */
  public ONISRAccidentParser(Set<String> departments) {
    this.departments = Set.copyOf(departments);
    for (String department : this.departments) {
      if (!department.matches("(?:[0-9]{2,3}|2[AB])")) {
        throw new IllegalArgumentException("Invalid department filter: " + department);
      }
    }
  }

  @Override
  public List<ONISRAccident> parseAccidents(File file) throws IOException {
    // Published pre-2019 files use comma-separated Latin-1; newer files use UTF-8 and semicolons.
    char separator;
    try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.ISO_8859_1)) {
      separator = separator(reader.readLine());
    }
    return parseAccidents(file, separator == ',' ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
  }

  /** Explicit encoding for files re-encoded before upload. */
  public List<ONISRAccident> parseAccidents(File file, Charset charset) throws IOException {
    try (Reader reader = Files.newBufferedReader(file.toPath(), charset)) {
      return parseAccidents(reader);
    }
  }

  @Override
  public List<ONISRAccident> parseAccidents(Reader input) throws IOException {
    BufferedReader reader = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
    reader.mark(65536);
    String firstLine = reader.readLine();
    reader.reset();
    if (firstLine == null) throw new IOException("Missing ONISR CSV header");
    // Consume the optional UTF-8 BOM before OpenCSV interprets a quoted first header.
    if (firstLine.startsWith("\uFEFF")) reader.read();
    CSVReader csv = new CSVReaderBuilder(reader)
        .withCSVParser(new CSVParserBuilder().withSeparator(separator(firstLine)).build()).build();
    List<ONISRAccident> accidents = new ArrayList<>();
    Set<String> identities = new HashSet<>();
    try {
      String[] headers = csv.readNext();
      Map<String, Integer> columns = new LinkedHashMap<>();
      for (int index = 0; index < headers.length; index++) {
        String key = headers[index].trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || columns.putIfAbsent(key, index) != null) {
          throw new IOException("Blank or duplicate ONISR column: " + headers[index]);
        }
      }
      for (String required : REQUIRED) {
        if (!columns.containsKey(required)) throw new IOException("Missing ONISR column: " + required);
      }
      String[] values;
      while ((values = csv.readNext()) != null) {
        if (values.length == 1 && values[0].isBlank()) continue;
        long line = csv.getLinesRead();
        if (values.length != headers.length) throw new IOException("Wrong ONISR column count at line " + line);
        try {
          String department = department(value(values, columns, "dep"));
          if (!departments.isEmpty() && !departments.contains(department)) continue;
          String id = value(values, columns, "num_acc");
          int year = Integer.parseInt(value(values, columns, "an"));
          if (year >= 0 && year < 100) year += 2000;
          if (year < 2005 || !id.matches("[0-9]{12}") || !id.startsWith(Integer.toString(year))) {
            throw new IllegalArgumentException("Invalid Num_Acc/an: " + id + "/" + year);
          }
          if (!identities.add(id)) throw new IllegalArgumentException("Duplicate Num_Acc: " + id);
          LocalDate date = LocalDate.of(year, Integer.parseInt(value(values, columns, "mois")),
              Integer.parseInt(value(values, columns, "jour")));
          LocalTime time = time(value(values, columns, "hrmn"));
          String commune = commune(value(values, columns, "com"), department, year);
          Double latitude = coordinate(value(values, columns, "lat"), year);
          Double longitude = coordinate(value(values, columns, "long"), year);
          if (year < 2019) {
            // Historical overseas magnitudes use the gps area to establish the hemisphere.
            String area = value(values, columns, "gps");
            if ((area.equals("A") || area.equals("G")) && longitude != null) longitude = -Math.abs(longitude);
            if ((area.equals("R") || area.equals("Y")) && latitude != null) latitude = -Math.abs(latitude);
          }
          if (latitude != null && Math.abs(latitude) > 90) latitude = null;
          if (longitude != null && Math.abs(longitude) > 180) longitude = null;
          if (latitude != null && longitude != null && latitude == 0 && longitude == 0) {
            latitude = null;
            longitude = null;
          }
          Map<String, String> sourceFields = new LinkedHashMap<>();
          for (int index = 0; index < headers.length; index++) sourceFields.put(headers[index].trim(), values[index]);
          for (String code : CODES) {
            String raw = value(values, columns, code);
            if (!isMissing(raw)) Integer.parseInt(raw);
          }
          String address = value(values, columns, "adr");
          accidents.add(new ONISRAccident(id, date, time, department, commune,
              isMissing(address) ? null : address, latitude, longitude, sourceFields));
        } catch (RuntimeException invalid) {
          throw new IOException("Invalid ONISR record at line " + line + ": " + invalid.getMessage(), invalid);
        }
      }
    } catch (CsvValidationException invalid) {
      throw new IOException("Invalid ONISR CSV at line " + csv.getLinesRead(), invalid);
    }
    return accidents;
  }

  private static char separator(String header) throws IOException {
    if (header == null) throw new IOException("Missing ONISR CSV header");
    if (header.indexOf(';') >= 0) return ';';
    if (header.indexOf(',') >= 0) return ',';
    throw new IOException("Expected comma- or semicolon-separated ONISR CSV");
  }

  private static String value(String[] values, Map<String, Integer> columns, String key) {
    Integer index = columns.get(key);
    return index == null ? "" : values[index].trim();
  }

  static boolean isMissing(String value) {
    return value.isBlank() || value.equals(".") || value.equalsIgnoreCase("N/A");
  }

  private static String department(String raw) {
    if (raw.equals("201")) return "2A";
    if (raw.equals("202")) return "2B";
    if (raw.matches("(?:0[1-9]|[1-8][0-9]|9[0-5])0")) return raw.substring(0, 2);
    if (raw.matches("(?:[0-9]{2,3}|2[AB])")) return raw;
    throw new IllegalArgumentException("Invalid dep: " + raw);
  }

  private static String commune(String raw, String department, int year) {
    if (isMissing(raw) || raw.matches("0+")) return null;
    if (year < 2019 && raw.matches("[0-9]{1,3}")) {
      String local = String.format(Locale.ROOT, "%03d", Integer.parseInt(raw));
      return department + (department.length() == 3 ? local.substring(1) : local);
    }
    if (!raw.matches("(?:[0-9]{5}|2[AB][0-9]{3})")) {
      throw new IllegalArgumentException("Invalid com: " + raw);
    }
    return raw;
  }

  private static LocalTime time(String raw) {
    if (isMissing(raw) || raw.equals("-1")) return null;
    if (raw.matches("[0-9]{1,2}:[0-9]{2}")) {
      String[] parts = raw.split(":");
      return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
    if (!raw.matches("[0-9]{1,4}")) throw new IllegalArgumentException("Invalid hrmn: " + raw);
    int hhmm = Integer.parseInt(raw);
    return LocalTime.of(hhmm / 100, hhmm % 100);
  }

  private static Double coordinate(String raw, int year) {
    if (isMissing(raw)) return null;
    try {
      double number = Double.parseDouble(raw.replace(',', '.'));
      return Double.isFinite(number) ? number / (year < 2019 ? 100000 : 1) : null;
    } catch (NumberFormatException invalid) {
      // Keep unlocated accidents and retain malformed coordinates in sourceFields.
      return null;
    }
  }
}
