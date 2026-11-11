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

package org.traficinfo.parsers.dpt30;

import com.opencsv.bean.CsvToBeanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.ComptageParser;
import org.traficinfo.parsers.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

public final class DPT30ComptageParser implements ComptageParser {

  public static final String COUNTER_PREFIX = "DPT30_";

  private static final Logger LOG = LoggerFactory.getLogger(DPT30ComptageParser.class);

  @Override
  public List<DPT30Comptage> parseComptages(Reader csvReader) throws IOException {
    return parseComptages(csvReader, new Properties());
  }

  @Override
  public List<DPT30Comptage> parseComptages(Reader csvReader, Properties idToDisplayNames)
      throws IOException {
    // Strip the optional UTF-8 BOM without closing the caller's reader.
    PushbackReader reader = new PushbackReader(csvReader, 1);
    int first = reader.read();
    if (first != -1 && first != '\uFEFF') {
      reader.unread(first);
    }
    List<DPT30Comptage> comptages = new CsvToBeanBuilder<DPT30Comptage>(reader)
        .withSeparator(',')
        .withType(DPT30Comptage.class)
        .build()
        .parse();

    for (DPT30Comptage comptage : comptages) {
      String idTmja = comptage.getIdTmja();
      String yearSuffix = "_" + comptage.getAnnee();
      if (idTmja == null || !idTmja.endsWith(yearSuffix)
          || comptage.getAnnee() <= 0
          || idTmja.substring(0, idTmja.length() - yearSuffix.length()).isBlank()) {
        throw new IOException("Expected id_tmja ending in _annee with a nonblank site ID in DPT30 CSV: "
            + idTmja);
      }
      String siteId = idTmja.substring(0, idTmja.length() - yearSuffix.length());
      comptage.setSiteId(siteId);
      comptage.setCodeCentrale(COUNTER_PREFIX + siteId);
      comptage.setCityName(StringUtils.toCamelCase(comptage.getCityName()));

      String displayName = comptage.getRouteId();
      if (displayName == null || displayName.isBlank()) {
        displayName = comptage.getCodeCentrale();
      }
      if (comptage.getCityName() != null && !comptage.getCityName().isBlank()) {
        displayName += " - " + comptage.getCityName();
      }
      comptage.setDisplayName(idToDisplayNames.getProperty(comptage.getCodeCentrale(), displayName));
    }
    LOG.info("Nb comptages: {}", comptages.size());
    return comptages;
  }

  @Override
  public List<DPT30Comptage> parseComptages(File csvFile, Properties idToDisplayNames)
      throws IOException {
    try (Reader reader = Files.newBufferedReader(csvFile.toPath(), StandardCharsets.UTF_8)) {
      return parseComptages(reader, idToDisplayNames);
    }
  }
}
