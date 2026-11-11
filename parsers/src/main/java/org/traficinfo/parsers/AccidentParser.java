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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.List;

/** Parses accident records without count-specific display names or measurements. */
public interface AccidentParser {
  /** The caller retains ownership of the reader. */
  List<? extends Accident> parseAccidents(Reader reader) throws IOException;

  List<? extends Accident> parseAccidents(File file) throws IOException;
}
