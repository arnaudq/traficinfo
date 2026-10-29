package org.traficinfo.dsfunctions.dpt34;

import org.traficinfo.dsfunctions.AbstractGeoJsonOutFunction;
import org.traficinfo.dsfunctions.GeoJsonDataSource;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;

/** Manifest-to-GeoJSON handler for DPT34. */
public class GeoJsonOutFunction extends AbstractGeoJsonOutFunction {
    public static final String DEFAULT_INPUT_BUCKET = GeoJsonDataSource.DPT34.getDefaultInputBucket();
    public static final String GEOJSON_OBJECT_NAME = GeoJsonDataSource.DPT34.getGeoJsonObjectName();

    public GeoJsonOutFunction() {
        super(GeoJsonDataSource.DPT34);
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        super(GeoJsonDataSource.DPT34, inputClient, outputClient, osmInfoProvider);
    }
}
