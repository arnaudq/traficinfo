package org.traficinfo.dsfunctions.dpt30;

import org.traficinfo.dsfunctions.AbstractCsvGeoJsonOutFunction;
import org.traficinfo.dsfunctions.CsvDataSource;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;

/** CSV-to-GeoJSON handler for DPT30. */
public class GeoJsonOutFunction extends AbstractCsvGeoJsonOutFunction {
    public static final String DEFAULT_INPUT_BUCKET = CsvDataSource.DPT30.getDefaultInputBucket();
    public static final String GEOJSON_OBJECT_NAME = CsvDataSource.DPT30.getGeoJsonObjectName();

    public GeoJsonOutFunction() {
        super(CsvDataSource.DPT30);
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        super(CsvDataSource.DPT30, inputClient, outputClient, osmInfoProvider);
    }
}
