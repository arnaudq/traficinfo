package org.traficinfo.dsfunctions.mrn;

import org.traficinfo.dsfunctions.AbstractCsvGeoJsonOutFunction;
import org.traficinfo.dsfunctions.CsvDataSource;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;

/** CSV-to-GeoJSON handler for MRN. */
public class GeoJsonOutFunction extends AbstractCsvGeoJsonOutFunction {
    public static final String DEFAULT_INPUT_BUCKET = CsvDataSource.MRN.getDefaultInputBucket();
    public static final String GEOJSON_OBJECT_NAME = CsvDataSource.MRN.getGeoJsonObjectName();

    public GeoJsonOutFunction() {
        super(CsvDataSource.MRN);
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        super(CsvDataSource.MRN, inputClient, outputClient, osmInfoProvider);
    }
}
