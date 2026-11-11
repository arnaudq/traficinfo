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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A source-owned snapshot of unresolved locations and requested way-tag refreshes. */
public record OSMRequests(Map<String, ReverseLookup> reverseLookups,
        @JsonDeserialize(contentUsing = OSMWayMapping.WayIdDeserializer.class) Set<Long> wayTagRefreshes) {
    public static final String SUFFIX = "_OSMRequests.json";

    public record ReverseLookup(Double latitude, Double longitude, String sourceRoadReference) {
        public ReverseLookup {
            if (latitude != null && (!Double.isFinite(latitude) || Math.abs(latitude) > 90)
                    || longitude != null && (!Double.isFinite(longitude) || Math.abs(longitude) > 180)) {
                throw new IllegalArgumentException("Invalid reverse lookup coordinates");
            }
        }
    }

    public OSMRequests {
        if (reverseLookups == null || wayTagRefreshes == null) {
            throw new IllegalArgumentException("reverseLookups and wayTagRefreshes are required");
        }
        reverseLookups.forEach((id, request) -> {
            if (id == null || id.isBlank() || request == null) {
                throw new IllegalArgumentException("Reverse requests require a counter ID and location object");
            }
        });
        if (wayTagRefreshes.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Tag requests require positive integral way IDs");
        }
        reverseLookups = Collections.unmodifiableMap(new LinkedHashMap<>(reverseLookups));
        wayTagRefreshes = Collections.unmodifiableSet(new LinkedHashSet<>(wayTagRefreshes));
    }

    public static OSMRequests empty() {
        return new OSMRequests(Map.of(), Set.of());
    }
}
