/*
 * This file is part of TraficInfo.
 * Copyright (C) 2024, 2026 Arnaud Quillaud
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

package org.traficinfo.parsers;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Properties;

/*
 * Generic data parser interface.
 */
public interface ComptageParser {

  /**
   * Get comptages from Reader.
   * @param dataReader data file as reader.
   * @return list of comptages.
   * @throws IOException if we failed to parse the file.
   */
  List<? extends Comptage> parseComptages(Reader dataReader)
          throws IOException;

  /**
   * Get comptages from Reader.
   * @param dataReader data file as reader.
   * @param idToDisplayNames id to displayname if displayname cant be constructed from data.
   * @return list of comptages.
   * @throws IOException if we failed to parse the file.
   */
  List<? extends Comptage> parseComptages(Reader dataReader, Properties idToDisplayNames)
          throws IOException;

  /**
   * Get comptages from file.
   * @param dataFile data file.
   * @param idToDisplayNames id to displayname if displayname cant be constructed from data.
   * @return list of comptages.
   * @throws IOException if we failed to parse the file.
   */
  List<? extends Comptage> parseComptages(File dataFile, Properties idToDisplayNames)
    throws IOException;
}
