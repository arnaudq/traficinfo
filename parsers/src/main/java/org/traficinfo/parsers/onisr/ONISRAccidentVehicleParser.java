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

package org.traficinfo.parsers.onisr;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.traficinfo.parsers.AccidentVehicle.Motorization;

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

/** Reads modern vehicle files, filtering by retained accident IDs before parsing vehicle fields. */
public final class ONISRAccidentVehicleParser {
  private static final List<String> REQUIRED = List.of("num_acc", "id_vehicule", "catv", "motor");

  /** Caller owns the reader. Results are keyed by accident ID, which is implicit in nested JSON. */
  public Map<String, List<ONISRAccidentVehicle>> parseVehicles(Reader input, Set<String> accidentIds, int year)
      throws IOException {
    Set<String> selected = Set.copyOf(accidentIds);
    BufferedReader reader = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
    reader.mark(1);
    if (reader.read() != '\uFEFF') reader.reset();
    CSVReader csv = new CSVReaderBuilder(reader)
        .withCSVParser(new CSVParserBuilder().withSeparator(';').build()).build();
    Map<String, List<ONISRAccidentVehicle>> result = new LinkedHashMap<>();
    Set<String> vehicleIds = new HashSet<>();
    try {
      String[] headers = csv.readNext();
      if (headers == null) throw new IOException("Missing ONISR vehicles header");
      Map<String, Integer> columns = new LinkedHashMap<>();
      for (int i = 0; i < headers.length; i++) {
        String key = headers[i].trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || columns.putIfAbsent(key, i) != null) throw new IOException("Duplicate or blank vehicles header: " + key);
      }
      for (String column : REQUIRED) {
        if (!columns.containsKey(column)) throw new IOException("Missing vehicles column: " + column);
      }
      String[] values;
      while ((values = csv.readNext()) != null) {
        int index = columns.get("num_acc");
        if (index >= values.length) continue;
        String accidentId = values[index].trim();
        if (!selected.contains(accidentId)) continue;
        try {
          if (values.length != headers.length) throw new IllegalArgumentException("Wrong column count");
          if (!accidentId.matches(year + "[0-9]{8}")) throw new IllegalArgumentException("Accident year mismatch");
          String id = ONISRIdentifiers.normalize(values[columns.get("id_vehicule")].trim());
          int category = code(values[columns.get("catv")]);
          if (category < -1) throw new IllegalArgumentException("Invalid category code");
          Motorization motorization = switch (code(values[columns.get("motor")])) {
            case 1 -> Motorization.COMBUSTION;
            case 2 -> Motorization.HYBRID;
            case 3 -> Motorization.ELECTRIC;
            case 4 -> Motorization.HYDROGEN;
            case 5 -> Motorization.HUMAN;
            case 6 -> Motorization.OTHER;
            default -> Motorization.UNKNOWN;
          };
          var vehicle = new ONISRAccidentVehicle(id, category <= 0 ? null : category, motorization);
          if (!vehicleIds.add(id)) throw new IllegalArgumentException("Duplicate vehicle ID: " + id);
          result.computeIfAbsent(accidentId, ignored -> new ArrayList<>()).add(vehicle);
        } catch (RuntimeException invalid) {
          throw new IOException("Invalid ONISR vehicle at line " + csv.getLinesRead() + ": " + invalid.getMessage(), invalid);
        }
      }
    } catch (CsvValidationException invalid) {
      throw new IOException("Invalid ONISR vehicles CSV at line " + csv.getLinesRead(), invalid);
    }
    result.replaceAll((id, vehicles) -> vehicles.stream().sorted(Comparator.comparing(ONISRAccidentVehicle::getVehicleId)).toList());
    return Map.copyOf(result);
  }

  private static int code(String raw) {
    String value = raw.trim();
    return ONISRAccidentParser.isMissing(value) ? -1 : Integer.parseInt(value);
  }
}
