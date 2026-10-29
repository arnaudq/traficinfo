package org.traficinfo.dsfunctions;

import org.traficinfo.parsers.GeoJsonParser;

import java.util.Locale;

/** Sources supported by the manifest-to-GeoJSON handlers and their naming conventions. */
public enum GeoJsonDataSource {
    MMM(GeoJsonParser.DataSource.M3MV2, "ds3mbucket", "MMM_MMM_Comptage.csv"),
    DPT30(GeoJsonParser.DataSource.DPT30),
    DPT34(GeoJsonParser.DataSource.DPT34),
    MRN(GeoJsonParser.DataSource.MRN);

    private final GeoJsonParser.DataSource parserDataSource;
    private final String defaultInputBucket;
    private final String csvObjectName;

    GeoJsonDataSource(GeoJsonParser.DataSource parserDataSource) {
        this(parserDataSource, "ds" + parserDataSource.getId().toLowerCase(Locale.ROOT),
                parserDataSource.getId() + "_Comptage.csv");
    }

    GeoJsonDataSource(GeoJsonParser.DataSource parserDataSource, String defaultInputBucket, String csvObjectName) {
        this.parserDataSource = parserDataSource;
        this.defaultInputBucket = defaultInputBucket;
        this.csvObjectName = csvObjectName;
    }

    public String getDataSourceId() {
        return name();
    }

    public GeoJsonParser.DataSource getParserDataSource() {
        return parserDataSource;
    }

    public String getDefaultInputBucket() {
        return defaultInputBucket;
    }

    public String getCsvObjectName() {
        return csvObjectName;
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
