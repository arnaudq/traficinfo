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
