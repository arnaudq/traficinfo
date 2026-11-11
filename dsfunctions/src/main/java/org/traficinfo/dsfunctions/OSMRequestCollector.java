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

import org.geojson.Point;
import org.traficinfo.parsers.OSMInfoProvider;
import org.traficinfo.parsers.OSMWayInfo;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reads caches without changing them; the producer only publishes its own request object. */
final class OSMRequestCollector implements OSMInfoProvider {
    private final OSMInfoProviderImpl provider;
    private final Map<String, OSMRequests.ReverseLookup> reverseLookups = new LinkedHashMap<>();
    private final Set<Long> wayTagRefreshes = new LinkedHashSet<>();

    OSMRequestCollector(OSMInfoProviderImpl provider) {
        this.provider = provider;
    }

    @Override
    public Long getWayId(String counterId, Point point, String sourceRoadReference) {
        Long wayId = provider.getCachedWayId(counterId);
        if (wayId != null) return wayId;
        if (counterId != null && !counterId.isBlank()) {
            Double lat = point == null || point.getCoordinates() == null ? null : point.getCoordinates().getLatitude();
            Double lon = point == null || point.getCoordinates() == null ? null : point.getCoordinates().getLongitude();
            reverseLookups.put(counterId, new OSMRequests.ReverseLookup(lat, lon, sourceRoadReference));
        }
        return null;
    }

    @Override
    public OSMWayInfo getWayInfo(long wayId) {
        if (wayId > 0 && provider.needsWayTagRefresh(wayId)) wayTagRefreshes.add(wayId);
        return provider.getCachedWayInfo(wayId);
    }

    void publish(String sourceId) throws IOException {
        provider.saveRequests(sourceId, new OSMRequests(reverseLookups, wayTagRefreshes));
    }
}
