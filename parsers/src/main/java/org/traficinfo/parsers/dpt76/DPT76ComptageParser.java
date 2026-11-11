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

package org.traficinfo.parsers.dpt76;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.ComptageParser;

import java.io.File;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Reads ArcGIS counting-point GeoJSON, using FID rather than the non-unique station code. */
public final class DPT76ComptageParser implements ComptageParser {
  public static final String COUNTER_PREFIX = "DPT76_";
  private final ObjectMapper mapper = new ObjectMapper()
      .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  @Override
  public List<DPT76Comptage> parseComptages(Reader reader) throws IOException {
    return parseComptages(reader, new Properties());
  }

  @Override
  public List<DPT76Comptage> parseComptages(Reader source, Properties names) throws IOException {
    PushbackReader reader = new PushbackReader(source, 1);
    int first = reader.read();
    if (first != -1 && first != '\uFEFF') reader.unread(first);
    JsonNode root = mapper.readTree(reader);
    if (root == null || !"FeatureCollection".equals(root.path("type").asText())
        || !root.path("features").isArray()) {
      throw new IOException("Expected a DPT76 GeoJSON FeatureCollection");
    }
    if (root.path("exceededTransferLimit").asBoolean(false)
        || root.path("properties").path("exceededTransferLimit").asBoolean(false)) {
      throw new IOException("Incomplete DPT76 response: exceededTransferLimit");
    }
    List<DPT76Comptage> records = new ArrayList<>();
    Set<Long> ids = new HashSet<>();
    for (JsonNode feature : root.get("features")) {
      JsonNode p = feature.path("properties");
      if (!"Feature".equals(feature.path("type").asText()) || !p.isObject()) {
        throw new IOException("Expected DPT76 Feature with properties");
      }
      Long fid = integer(p.get("FID"), "FID");
      if (fid == null || fid < 0 || !ids.add(fid)) throw new IOException("Missing, invalid or duplicate DPT76 FID: " + fid);
      Long year = integer(p.get("Annee_de_"), "Annee_de_");
      if (year == null || year <= 0 || year > 9999) throw new IOException("Invalid DPT76 Annee_de_: " + year);
      DPT76Comptage count = new DPT76Comptage();
      count.setFid(fid);
      count.setCodeCentrale(COUNTER_PREFIX + fid);
      count.setObjectId(integer(p.get("objectid"), "objectid"));
      count.setStationCode(decimal(p.get("Code_stat_"), "Code_stat_"));
      count.setAnnee(year.intValue());
      count.setRouteId(text(p.get("axe")));
      count.setCumuld(decimal(p.get("cumuld"), "cumuld"));
      count.setPlod(text(p.get("plod")));
      count.setAbsd(decimal(p.get("absd"), "absd"));
      count.setTypeCompteur(text(p.get("Type_de_c_")));
      count.setMjaTvTcj(count(p.get("Trafic_Mo_"), "Trafic_Mo_"));
      count.setTmjaSens1(count(p.get("Tmja_sens_"), "Tmja_sens_"));
      count.setTmjaSens2(count(p.get("Tmja_sen_2"), "Tmja_sen_2"));
      count.setPourcentagePl(decimal(p.get("Pourcenta_"), "Pourcenta_"));
      count.setPourcentagePlSens1(decimal(p.get("Pourcent_0"), "Pourcent_0"));
      count.setPourcentagePlSens2(decimal(p.get("Pourcent_1"), "Pourcent_1"));
      geometry(feature.get("geometry"), count);
      String display = count.getRouteId() == null ? count.getCodeCentrale() : count.getRouteId();
      if (count.getPlod() != null) {
        display += " - PR " + count.getPlod();
        if (count.getAbsd() != null) display += " + " + java.math.BigDecimal.valueOf(count.getAbsd()).stripTrailingZeros().toPlainString();
      }
      count.setDisplayName(names.getProperty(count.getCodeCentrale(), display));
      records.add(count);
    }
    return records;
  }

  private static void geometry(JsonNode geometry, DPT76Comptage count) throws IOException {
    // Keep unlocated records. Lambert-93 x/y attributes are not longitude/latitude.
    if (geometry == null || geometry.isNull()) return;
    JsonNode coordinates = geometry.path("coordinates");
    if (!"Point".equals(geometry.path("type").asText()) || !coordinates.isArray() || coordinates.size() < 2
        || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber()) {
      throw new IOException("Expected DPT76 Point coordinates for FID " + count.getFid());
    }
    double longitude = coordinates.get(0).asDouble();
    double latitude = coordinates.get(1).asDouble();
    if (!Double.isFinite(longitude) || !Double.isFinite(latitude) || Math.abs(longitude) > 180 || Math.abs(latitude) > 90) {
      throw new IOException("Expected WGS84 DPT76 coordinates for FID " + count.getFid());
    }
    count.setLongitude(longitude);
    count.setLatitude(latitude);
  }

  private static String text(JsonNode node) throws IOException {
    if (node == null || node.isNull()) return null;
    if (!node.isValueNode()) throw new IOException("Expected scalar DPT76 property");
    String value = node.asText().trim();
    return value.isEmpty() ? null : value;
  }

  private static Long integer(JsonNode node, String field) throws IOException {
    String value = text(node);
    if (value == null) return null;
    try {
      return new java.math.BigDecimal(value).longValueExact();
    } catch (NumberFormatException | ArithmeticException invalid) {
      throw new IOException("Invalid DPT76 integer " + field + ": " + value, invalid);
    }
  }

  private static Integer count(JsonNode node, String field) throws IOException {
    Long value = integer(node, field);
    if (value == null) return null;
    if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("Invalid DPT76 count " + field);
    return value.intValue();
  }

  private static Double decimal(JsonNode node, String field) throws IOException {
    String value = text(node);
    if (value == null) return null;
    try {
      double number = Double.parseDouble(value);
      if (!Double.isFinite(number)) throw new NumberFormatException("Non-finite number");
      return number;
    } catch (NumberFormatException invalid) {
      throw new IOException("Invalid DPT76 number " + field + ": " + value, invalid);
    }
  }

  @Override
  public List<DPT76Comptage> parseComptages(File file, Properties names) throws IOException {
    try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      return parseComptages(reader, names);
    }
  }
}
