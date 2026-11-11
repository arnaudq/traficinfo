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

/** Outcome of assigning an OSM road way to a traffic counter. */
public enum OSMWayMappingStatus {
    /** Lookup is pending or explicitly requested again; coordinates may be unavailable or an HTTP attempt may have failed. */
    MISSING,
    /** A road way is assigned, either from the seed/manual cache or an accepted reverse lookup. */
    MATCHED,
    /** A road way candidate needs review because its reference is missing or differs from the source. */
    NEEDS_REVIEW,
    /** A successful reverse response contained no suitable road way, including absent or unsupported objects. */
    NOT_FOUND
}
