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

package org.traficinfo.parsers;

import java.util.Locale;

public final class StringUtils {

  private StringUtils() {
  }

  /**
   * Capitalizes words separated by whitespace or hyphens, preserving separators.
   * For example, "SAINT CLEMENT-DE-RIVIERE" becomes "Saint Clement-De-Riviere".
   * Returns null for null input.
   */
  public static String toCamelCase(String value) {
    if (value == null) {
      return null;
    }
    String lowerCase = value.toLowerCase(Locale.ROOT);
    StringBuilder result = new StringBuilder(lowerCase.length());
    boolean wordStart = true;
    for (int offset = 0; offset < lowerCase.length();) {
      int codePoint = lowerCase.codePointAt(offset);
      boolean separator = Character.isWhitespace(codePoint) || codePoint == '-';
      result.appendCodePoint(wordStart && !separator
          ? Character.toTitleCase(codePoint) : codePoint);
      wordStart = separator;
      offset += Character.charCount(codePoint);
    }
    return result.toString();
  }
}
