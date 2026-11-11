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

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/** Tags fetched from OSM and the time they were last successfully cached. */
public class OSMWayInfo {
    private static final int CACHE_MAX_AGE_MONTHS = 3;

    private String lastUpdated;
    private Map<String, String> tags = new HashMap<>();

    public OSMWayInfo() {
    }

    public OSMWayInfo(Instant lastUpdated, Map<String, String> tags) {
        this.lastUpdated = lastUpdated.toString();
        setTags(tags);
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new HashMap<>() : new HashMap<>(tags);
    }

    public boolean needsRefresh(Instant now) {
        if (lastUpdated == null) {
            return true;
        }
        try {
            Instant cutoff = now.atZone(ZoneOffset.UTC).minusMonths(CACHE_MAX_AGE_MONTHS).toInstant();
            return Instant.parse(lastUpdated).isBefore(cutoff);
        } catch (DateTimeParseException invalidTimestamp) {
            return true;
        }
    }

    /** Explicit bicycle exception on a road tagged as one-way in either direction. */
    @JsonIgnore
    public boolean isBicycleContraflowAllowed() {
        String oneway = tags.get("oneway");
        return ("yes".equals(oneway) || "-1".equals(oneway))
                && "no".equals(tags.get("oneway:bicycle"));
    }

    /** Numeric maxspeed in km/h; absent or nonnumeric limits remain unknown. */
    @JsonIgnore
    public int getMaximumSpeed() {
        String value = tags.get("maxspeed");
        if (value == null) {
            return -1;
        }
        value = value.trim().replaceFirst("\\s+km/h$", "");
        if (!value.matches("[0-9]+")) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalidSpeed) {
            return -1;
        }
    }
}
