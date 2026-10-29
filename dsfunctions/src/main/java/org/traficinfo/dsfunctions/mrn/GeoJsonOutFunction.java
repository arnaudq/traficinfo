package org.traficinfo.dsfunctions.mrn;

import org.traficinfo.dsfunctions.AbstractGeoJsonOutFunction;
import org.traficinfo.dsfunctions.GeoJsonDataSource;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;

/** Manifest-to-GeoJSON handler for MRN. */
public class GeoJsonOutFunction extends AbstractGeoJsonOutFunction {
    public static final String DEFAULT_INPUT_BUCKET = GeoJsonDataSource.MRN.getDefaultInputBucket();
    public static final String GEOJSON_OBJECT_NAME = GeoJsonDataSource.MRN.getGeoJsonObjectName();

    public GeoJsonOutFunction() {
        super(GeoJsonDataSource.MRN);
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        super(GeoJsonDataSource.MRN, inputClient, outputClient, osmInfoProvider);
    }
}
