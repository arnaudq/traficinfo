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

import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.Comptage;

/** Sources supported by GeoJSON publication and their output naming conventions. */
public enum GeoJsonDataSource {
    MMM(GeoJsonParser.DataSource.M3MV2),
    DPT30(GeoJsonParser.DataSource.DPT30),
    DPT76(GeoJsonParser.DataSource.DPT76),
    DPT34(GeoJsonParser.DataSource.DPT34),
    MRN(GeoJsonParser.DataSource.MRN),
    NIM(GeoJsonParser.DataSource.NIM);

    private final GeoJsonParser.DataSource parserDataSource;

    GeoJsonDataSource(GeoJsonParser.DataSource parserDataSource) {
        this.parserDataSource = parserDataSource;
    }

    public String getDataSourceId() {
        return name();
    }

    public GeoJsonParser.DataSource getParserDataSource() {
        return parserDataSource;
    }

    public String getGeoJsonObjectName() {
        return getGeoJsonObjectName(Comptage.Type.VEHICULES);
    }

    public String getGeoJsonObjectName(Comptage.Type type) {
        String suffix = switch (type) {
            case VEHICULES -> "";
            case BICYCLES -> "_BICYCLES";
            default -> throw new IllegalArgumentException("Unsupported GeoJSON population: " + type);
        };
        return getDataSourceId() + "_TraficInfo" + suffix + ".geojson";
    }

    public String getOsmCacheObjectName() {
        return getDataSourceId() + "_OSMInfo.json";
    }

    public String getOsmWayMappingObjectName() {
        return getDataSourceId() + "_OSMWayMapping.json";
    }
}
