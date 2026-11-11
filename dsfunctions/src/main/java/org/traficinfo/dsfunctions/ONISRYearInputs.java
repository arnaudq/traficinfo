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

package org.traficinfo.dsfunctions;

import java.util.List;

/** One annual ONISR publication and its corresponding objects in onisrinfo. */
public record ONISRYearInputs(int year, String accidentObject, String participantsObject, String vehiclesObject) {
    private static final List<ONISRYearInputs> CONFIGURED = List.of(
            new ONISRYearInputs(2024, "caract-2024.csv", "usagers-2024.csv", "vehicules-2024.csv"),
            new ONISRYearInputs(2025, "caract-2025.csv", "usagers-2025.csv", "vehicules-2025.csv"));

    /** Explicit selection; uploaded files for other years are not automatically imported. */
    public static List<ONISRYearInputs> configured() {
        return CONFIGURED;
    }
}
