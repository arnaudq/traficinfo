package org.traficinfo.dsfunctions.nim;

import org.traficinfo.dsfunctions.AbstractGeoJsonOutFunction;
import org.traficinfo.dsfunctions.GeoJsonDataSource;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;

/** Manifest-to-GeoJSON handler for NIM. */
public class GeoJsonOutFunction extends AbstractGeoJsonOutFunction {
    public static final String DEFAULT_INPUT_BUCKET = GeoJsonDataSource.NIM.getDefaultInputBucket();
    public static final String GEOJSON_OBJECT_NAME = GeoJsonDataSource.NIM.getGeoJsonObjectName();

    public GeoJsonOutFunction() {
        super(GeoJsonDataSource.NIM);
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        super(GeoJsonDataSource.NIM, inputClient, outputClient, osmInfoProvider);
    }
}
