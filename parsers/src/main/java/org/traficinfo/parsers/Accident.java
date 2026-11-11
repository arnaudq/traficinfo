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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** One road accident, independent of the source's vehicle and casualty tables. */
public interface Accident extends GeographicRecord {
  /** Source accident identifier, retained for joins with companion files. */
  String getAccidentId();

  LocalDate getDate();

  /** Local clock time as reported by the source, or null when unknown; no inferred timezone. */
  LocalTime getTime();

  /** Source administrative area identifier; mapped to the department code for France. */
  String getRegionalId();

  /** Municipality identifier, or null when unknown; mapped to the INSEE commune code for France. */
  String getMunicipalityId();

  /** Source address, or null when unknown; not necessarily an official road reference. */
  String getAddress();

  List<? extends AccidentParticipant> getParticipants();

  List<? extends AccidentVehicle> getVehicles();
}
