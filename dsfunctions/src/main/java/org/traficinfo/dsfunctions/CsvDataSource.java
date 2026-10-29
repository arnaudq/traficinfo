package org.traficinfo.dsfunctions;

import org.traficinfo.parsers.GeoJsonParser;

import java.util.Locale;

/** Sources supported by the manifest-to-GeoJSON handlers and their naming conventions. */
public enum CsvDataSource {
    DPT30(GeoJsonParser.DataSource.DPT30),
    DPT34(GeoJsonParser.DataSource.DPT34),
    MRN(GeoJsonParser.DataSource.MRN);

    private final GeoJsonParser.DataSource parserDataSource;

    CsvDataSource(GeoJsonParser.DataSource parserDataSource) {
        this.parserDataSource = parserDataSource;
    }

    public String getDataSourceId() {
        return name();
    }

    public GeoJsonParser.DataSource getParserDataSource() {
        return parserDataSource;
    }

    public String getDefaultInputBucket() {
        return "ds" + getDataSourceId().toLowerCase(Locale.ROOT);
    }

    public String getCsvObjectName() {
        return getDataSourceId() + "_Comptage.csv";
    }

    public String getGeoJsonObjectName() {
        return getDataSourceId() + "_TraficInfo.geojson";
    }

    public String getOsmCacheObjectName() {
        return getDataSourceId() + "_OSMInfo.json";
    }

    public String getOsmWayMappingObjectName() {
        return getDataSourceId() + "_OSMWayMapping.json";
    }
}
