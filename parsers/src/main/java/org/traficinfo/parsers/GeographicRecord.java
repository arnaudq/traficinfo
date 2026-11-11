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

import org.geojson.GeoJsonObject;

/** Source location and optional OSM road assignment, shared by counts and accidents. */
public interface GeographicRecord {
  /** Source geometry in WGS84, or null when unavailable. */
  default GeoJsonObject getGeometry() {
    return null;
  }

  Long getOsmWayId();

  void setOsmWayId(Long osmWayId);

  /** Official road reference for comparison with an OSM ref tag, or null if absent. */
  default String getRoadReference() {
    return null;
  }
}
