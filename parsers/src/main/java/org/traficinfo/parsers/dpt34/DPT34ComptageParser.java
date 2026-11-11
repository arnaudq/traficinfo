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

package org.traficinfo.parsers.dpt34;

import com.opencsv.bean.CsvToBeanBuilder;
import org.traficinfo.parsers.ComptageParser;
import org.traficinfo.parsers.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

public final class DPT34ComptageParser implements ComptageParser {

  public static final String COUNTER_PREFIX = "DPT34_";

  private static final Logger LOG = LoggerFactory.getLogger(DPT34ComptageParser.class);

  @Override
  public List<DPT34Comptage> parseComptages(Reader csvReader) throws IOException {
    return parseComptages(csvReader, new Properties());
  }

  @Override
  public List<DPT34Comptage> parseComptages(Reader csvReader, Properties idToDisplayNames)
      throws IOException {
    // Strip the optional UTF-8 BOM without closing the caller's reader.
    PushbackReader reader = new PushbackReader(csvReader, 1);
    int first = reader.read();
    if (first != -1 && first != '\uFEFF') {
      reader.unread(first);
    }
    List<DPT34Comptage> comptages = new CsvToBeanBuilder<DPT34Comptage>(reader)
        .withSeparator(';')
        .withType(DPT34Comptage.class)
        .build()
        .parse();

    for (DPT34Comptage comptage : comptages) {
      String siteId = comptage.getSiteId();
      if (siteId == null || siteId.isBlank()) {
        throw new IOException("Missing Identifiant du compteur in DPT34 CSV");
      }
      comptage.setCodeCentrale(COUNTER_PREFIX + siteId);
      comptage.setCityName(StringUtils.toCamelCase(comptage.getCityName()));
      String streetName = comptage.getStreetName();
      if (streetName == null || streetName.isBlank()) {
        streetName = comptage.getAltStreetName();
      }
      String displayName = streetName;
      String cityName = comptage.getCityName();
      if (streetName != null && !streetName.isBlank()
          && cityName != null && !cityName.isBlank()) {
        displayName = streetName + " - " + cityName;
      }
      comptage.setDisplayName(idToDisplayNames.getProperty(
          comptage.getCodeCentrale(), displayName));
    }
    LOG.info("Nb comptages: {}", comptages.size());
    return comptages;
  }

  @Override
  public List<DPT34Comptage> parseComptages(File csvFile, Properties idToDisplayNames)
      throws IOException {
    try (Reader reader = Files.newBufferedReader(csvFile.toPath(), StandardCharsets.UTF_8)) {
      return parseComptages(reader, idToDisplayNames);
    }
  }
}
