package org.traficinfo.parsers.ren;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.traficinfo.parsers.ComptageParser;

import java.io.File;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Reads the Rennes Opendatasoft semicolon CSV export, including latitude/longitude. */
public final class RENComptageParser implements ComptageParser {
  public static final String COUNTER_PREFIX = "REN_";
  private static final List<String> REQUIRED_HEADERS = List.of(
      "code_rm", "annee", "sens", "geo_point_2d", "nb_semaines", "tmja_tv");

  @Override public List<RENComptage> parseComptages(Reader reader) throws IOException {
    return parseComptages(reader, new Properties());
  }

  @Override public List<RENComptage> parseComptages(Reader source, Properties names) throws IOException {
    PushbackReader reader = new PushbackReader(source, 1);
    int first = reader.read();
    if (first != -1 && first != '\uFEFF') reader.unread(first);
    // Do not close the caller's reader.
    CSVReader csv = new CSVReaderBuilder(reader)
        .withCSVParser(new CSVParserBuilder().withSeparator(';').build()).build();
    List<RENComptage> records = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    try {
      String[] headers = csv.readNext();
      if (headers == null) throw new IOException("Missing REN CSV header");
      List<String> columns = Arrays.stream(headers).map(String::trim).toList();
      if (new HashSet<>(columns).size() != columns.size() || !columns.containsAll(REQUIRED_HEADERS)) {
        throw new IOException("Missing or duplicate REN CSV headers; expected " + REQUIRED_HEADERS);
      }
      String[] values;
      while ((values = csv.readNext()) != null) {
        if (Arrays.stream(values).allMatch(String::isBlank)) continue;
        if (values.length != headers.length) throw new IOException("Invalid REN CSV row width");
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < values.length; i++) row.put(columns.get(i), text(values[i]));
        String station = row.get("code_rm");
        String sens = row.get("sens");
        Integer year = year(row.get("annee"));
        if (station == null || sens == null || year == null || year <= 0 || year > 9999) {
          throw new IOException("Missing or invalid REN station, direction or year");
        }
        String id = COUNTER_PREFIX + java.net.URLEncoder.encode(station, StandardCharsets.UTF_8)
            + "__" + java.net.URLEncoder.encode(sens, StandardCharsets.UTF_8);
        if (!ids.add(id + "/" + year)) throw new IOException("Duplicate REN station/year/direction: " + id + "/" + year);
        RENComptage count = new RENComptage();
        count.setStationCode(station);
        count.setSens(sens);
        count.setAnnee(year);
        count.setCodeCentrale(id);
        count.setFiabilite(row.get("fiabilite"));
        count.setNbSemaines(measure(row.get("nb_semaines"), "nb_semaines"));
        count.setTmjaTv(measure(row.get("tmja_tv"), "tmja_tv"));
        count.setTmjaPl(measure(row.get("tmja_pl"), "tmja_pl"));
        count.setV85Tv(measure(row.get("v85_tv"), "v85_tv"));
        count.setV85Pl(measure(row.get("v85_pl"), "v85_pl"));
        count.setVMoyenneTv(measure(row.get("v_moyenne_tv"), "v_moyenne_tv"));
        count.setVMoyennePl(measure(row.get("v_moyenne_pl"), "v_moyenne_pl"));
        count.setDebitHpmTv(measure(row.get("debit_hpm_tv"), "debit_hpm_tv"));
        count.setDebitHpmPl(measure(row.get("debit_hpm_pl"), "debit_hpm_pl"));
        count.setDebitHpsTv(measure(row.get("debit_hps_tv"), "debit_hps_tv"));
        count.setDebitHpsPl(measure(row.get("debit_hps_pl"), "debit_hps_pl"));
        count.setMoyenneHeureDePointeMatin(formatted(count.getDebitHpmTv()));
        count.setMoyenneHeureDePointeSoir(formatted(count.getDebitHpsTv()));
        coordinates(row.get("geo_point_2d"), count);
        count.setDisplayName(names.getProperty(id, station + " - " + sens));
        records.add(count);
      }
    } catch (CsvValidationException invalid) {
      throw new IOException("Invalid REN CSV", invalid);
    }
    return records;
  }

  private static void coordinates(String value, RENComptage count) throws IOException {
    if (value == null) return;
    String[] coordinates = value.split(",", -1);
    if (coordinates.length != 2) throw new IOException("Expected REN latitude, longitude: " + value);
    double latitude = decimal(coordinates[0], "latitude");
    double longitude = decimal(coordinates[1], "longitude");
    if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
      throw new IOException("Expected WGS84 REN coordinates: " + value);
    }
    count.setLatitude(latitude);
    count.setLongitude(longitude);
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static Integer year(String value) throws IOException {
    if (value == null) return null;
    try { return new java.math.BigDecimal(value).intValueExact(); }
    catch (NumberFormatException | ArithmeticException invalid) {
      throw new IOException("Invalid REN year: " + value, invalid);
    }
  }

  private static String formatted(Double value) {
    return value == null ? null : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static Double measure(String value, String field) throws IOException {
    if (value == null) return null;
    double number = decimal(value, field);
    if (number < 0 || number > Integer.MAX_VALUE) throw new IOException("Invalid REN measure " + field);
    return number;
  }

  private static double decimal(String value, String field) throws IOException {
    try {
      double number = Double.parseDouble(value.trim());
      if (!Double.isFinite(number)) throw new NumberFormatException("Non-finite number");
      return number;
    } catch (NumberFormatException invalid) {
      throw new IOException("Invalid REN number " + field + ": " + value, invalid);
    }
  }

  @Override public List<RENComptage> parseComptages(File file, Properties names) throws IOException {
    try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      return parseComptages(reader, names);
    }
  }
}
