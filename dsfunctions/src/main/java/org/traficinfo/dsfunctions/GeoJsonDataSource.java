package org.traficinfo.dsfunctions;

import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.Comptage;

import java.util.Locale;

/** Sources supported by the manifest-to-GeoJSON handlers and their naming conventions. */
public enum GeoJsonDataSource {
    MMM(GeoJsonParser.DataSource.M3MV2, "ds3mbucket", "MMM_MMM_Comptage.csv"),
    DPT30(GeoJsonParser.DataSource.DPT30),
    DPT76(GeoJsonParser.DataSource.DPT76, "dsdpt76", "DPT76_Comptage_2022.geojson"),
    DPT34(GeoJsonParser.DataSource.DPT34),
    MRN(GeoJsonParser.DataSource.MRN),
    NIM(GeoJsonParser.DataSource.NIM);

    private final GeoJsonParser.DataSource parserDataSource;
    private final String defaultInputBucket;
    private final String inputObjectName;

    GeoJsonDataSource(GeoJsonParser.DataSource parserDataSource) {
        this(parserDataSource, "ds" + parserDataSource.getId().toLowerCase(Locale.ROOT),
                parserDataSource.getId() + "_Comptage.csv");
    }

    GeoJsonDataSource(GeoJsonParser.DataSource parserDataSource, String defaultInputBucket, String inputObjectName) {
        this.parserDataSource = parserDataSource;
        this.defaultInputBucket = defaultInputBucket;
        this.inputObjectName = inputObjectName;
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

    public String getInputObjectName() {
        return inputObjectName;
    }

    /** @deprecated Source inputs may also be GeoJSON; use getInputObjectName(). */
    @Deprecated
    public String getCsvObjectName() {
        return getInputObjectName();
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
