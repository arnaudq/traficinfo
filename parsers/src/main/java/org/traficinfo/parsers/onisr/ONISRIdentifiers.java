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

/** The participant and vehicle files use the same whitespace-separated numeric IDs. */
final class ONISRIdentifiers {
  private ONISRIdentifiers() {}

  static String normalize(String value) {
    StringBuilder normalized = new StringBuilder();
    value.codePoints().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).forEach(normalized::appendCodePoint);
    String id = normalized.toString();
    if (!id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid ID: " + value);
    return id;
  }
}
