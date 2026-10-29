package org.traficinfo.dsfunctions.dpt30;

import org.traficinfo.dsfunctions.AbstractGeoJsonOutFunction;
import org.traficinfo.dsfunctions.GeoJsonDataSource;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;

/** Manifest-to-GeoJSON handler for DPT30. */
public class GeoJsonOutFunction extends AbstractGeoJsonOutFunction {
    public static final String DEFAULT_INPUT_BUCKET = GeoJsonDataSource.DPT30.getDefaultInputBucket();
    public static final String GEOJSON_OBJECT_NAME = GeoJsonDataSource.DPT30.getGeoJsonObjectName();

    public GeoJsonOutFunction() {
        super(GeoJsonDataSource.DPT30);
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        super(GeoJsonDataSource.DPT30, inputClient, outputClient, osmInfoProvider);
    }
}
