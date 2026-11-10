package org.traficinfo.parsers.onisr;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.traficinfo.parsers.AccidentParticipant.InjurySeverity;
import org.traficinfo.parsers.AccidentParticipant.Role;
import org.traficinfo.parsers.AccidentParticipant.Sex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads 2025 usagers, filtering by selected accident IDs before parsing participant fields. */
public final class ONISRAccidentParticipantParser {
  private static final List<String> REQUIRED = List.of("num_acc", "id_usager", "id_vehicule", "catu", "grav", "an_nais", "sexe");

  /** Caller owns the reader. Accident IDs are map keys only, never duplicated inside participants. */
  public Map<String, List<ONISRAccidentParticipant>> parseParticipants(Reader input, Set<String> accidentIds, int year)
      throws IOException {
    Set<String> selected = Set.copyOf(accidentIds);
    BufferedReader reader = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
    reader.mark(1);
    if (reader.read() != '\uFEFF') reader.reset();
    CSVReader csv = new CSVReaderBuilder(reader)
        .withCSVParser(new CSVParserBuilder().withSeparator(';').build()).build();
    Map<String, List<ONISRAccidentParticipant>> result = new LinkedHashMap<>();
    Set<String> participantIds = new HashSet<>();
    try {
      String[] headers = csv.readNext();
      if (headers == null) throw new IOException("Missing ONISR usagers header");
      Map<String, Integer> columns = new LinkedHashMap<>();
      for (int i = 0; i < headers.length; i++) {
        String key = headers[i].trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || columns.putIfAbsent(key, i) != null) throw new IOException("Duplicate or blank usagers header: " + key);
      }
      for (String column : REQUIRED) {
        if (!columns.containsKey(column)) throw new IOException("Missing usagers column: " + column);
      }
      String[] values;
      while ((values = csv.readNext()) != null) {
        int accidentColumn = columns.get("num_acc");
        if (accidentColumn >= values.length) continue;
        String accidentId = values[accidentColumn].trim();
        if (!selected.contains(accidentId)) continue;
        try {
          if (values.length != headers.length) throw new IllegalArgumentException("Wrong column count");
          if (!accidentId.matches(year + "[0-9]{8}")) throw new IllegalArgumentException("Accident year mismatch");
          String participantId = ONISRIdentifiers.normalize(value(values, columns, "id_usager"));
          String vehicle = value(values, columns, "id_vehicule");
          String vehicleId = ONISRAccidentParser.isMissing(vehicle) || vehicle.equals("-1") ? null : ONISRIdentifiers.normalize(vehicle);
          String birth = value(values, columns, "an_nais");
          Integer birthYear = ONISRAccidentParser.isMissing(birth) || birth.equals("-1") || birth.equals("0")
              ? null : Integer.valueOf(birth);
          if (birthYear != null && (birthYear <= 0 || birthYear > year)) throw new IllegalArgumentException("Invalid birth year");
          Role role = switch (code(value(values, columns, "catu"))) {
            case 1 -> Role.DRIVER;
            case 2 -> Role.PASSENGER;
            case 3 -> Role.PEDESTRIAN;
            default -> Role.UNKNOWN;
          };
          InjurySeverity severity = switch (code(value(values, columns, "grav"))) {
            case 1 -> InjurySeverity.UNINJURED;
            case 2 -> InjurySeverity.KILLED;
            case 3 -> InjurySeverity.HOSPITALIZED;
            case 4 -> InjurySeverity.LIGHTLY_INJURED;
            default -> InjurySeverity.UNKNOWN;
          };
          Sex sex = switch (code(value(values, columns, "sexe"))) {
            case 1 -> Sex.MALE;
            case 2 -> Sex.FEMALE;
            default -> Sex.UNKNOWN;
          };
          var participant = new ONISRAccidentParticipant(participantId, vehicleId, role, severity, birthYear, sex);
          if (!participantIds.add(participantId)) throw new IllegalArgumentException("Duplicate participant ID: " + participantId);
          result.computeIfAbsent(accidentId, ignored -> new ArrayList<>()).add(participant);
        } catch (RuntimeException invalid) {
          throw new IOException("Invalid ONISR usager at line " + csv.getLinesRead() + ": " + invalid.getMessage(), invalid);
        }
      }
    } catch (CsvValidationException invalid) {
      throw new IOException("Invalid ONISR usagers CSV at line " + csv.getLinesRead(), invalid);
    }
    result.replaceAll((id, participants) -> participants.stream()
        .sorted(Comparator.comparing(ONISRAccidentParticipant::getParticipantId)).toList());
    return Map.copyOf(result);
  }

  private static String value(String[] values, Map<String, Integer> columns, String field) {
    return values[columns.get(field)].trim();
  }

  private static int code(String value) {
    return ONISRAccidentParser.isMissing(value) ? -1 : Integer.parseInt(value);
  }

}
