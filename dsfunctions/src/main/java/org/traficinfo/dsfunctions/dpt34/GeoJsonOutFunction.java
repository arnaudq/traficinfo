package org.traficinfo.dsfunctions.dpt34;

import org.traficinfo.dsfunctions.AbstractGeoJsonOutFunction;
import org.traficinfo.dsfunctions.CsvDataSource;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;

/** Manifest-to-GeoJSON handler for DPT34. */
public class GeoJsonOutFunction extends AbstractGeoJsonOutFunction {
    public static final String DEFAULT_INPUT_BUCKET = CsvDataSource.DPT34.getDefaultInputBucket();
    public static final String GEOJSON_OBJECT_NAME = CsvDataSource.DPT34.getGeoJsonObjectName();

    public GeoJsonOutFunction() {
        super(CsvDataSource.DPT34);
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        super(CsvDataSource.DPT34, inputClient, outputClient, osmInfoProvider);
    }
}
