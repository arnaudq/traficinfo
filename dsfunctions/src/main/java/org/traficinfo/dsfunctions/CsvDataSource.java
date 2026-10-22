package org.traficinfo.dsfunctions;

import org.traficinfo.parsers.GeoJsonParser;

import java.net.URI;
import java.util.Locale;

/** CSV-only sources whose coordinates are provided by their parser. */
public enum CsvDataSource {
    DPT30("https://www.data.gouv.fr/api/1/datasets/r/69584212-8ff0-40d3-b82d-2d5d2890bff7",
            GeoJsonParser.DataSource.DPT30),
    DPT34("https://www.herault-data.fr/api/explore/v2.1/catalog/datasets/"
            + "trafic-de-l-herault-donnees-de-comptage/exports/csv?use_labels=true&delimiter=%3B",
            GeoJsonParser.DataSource.DPT34),
    MRN("https://data.metropole-rouen-normandie.fr/api/explore/v2.1/catalog/datasets/"
            + "comptages-et-enquetes-sur-le-reseau-routier/exports/csv?use_labels=true&delimiter=%3B",
            GeoJsonParser.DataSource.MRN);

    private final URI csvUri;
    private final GeoJsonParser.DataSource parserDataSource;

    CsvDataSource(String csvUri, GeoJsonParser.DataSource parserDataSource) {
        this.csvUri = URI.create(csvUri);
        this.parserDataSource = parserDataSource;
    }

    public String getDataSourceId() {
        return name();
    }

    public URI getCsvUri() {
        return csvUri;
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

    public String getCsvArchiveNamePattern() {
        return getDataSourceId() + "_Comptage_%s.csv";
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
