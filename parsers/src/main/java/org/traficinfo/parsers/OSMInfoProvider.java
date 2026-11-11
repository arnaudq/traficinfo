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

import org.geojson.Point;

/** Provides cached OSM way information without exposing cache storage. */
@FunctionalInterface
public interface OSMInfoProvider {
    /** Returns cached information, or null if absent; implementations may queue missing or stale ways for later refresh. */
    OSMWayInfo getWayInfo(long wayId);

    /** Returns a cached way ID; records unresolved counters with nullable location and road reference. No REST lookup. */
    default Long getWayId(String centraleId, Point point, String roadReference) {
        return null;
    }
}
