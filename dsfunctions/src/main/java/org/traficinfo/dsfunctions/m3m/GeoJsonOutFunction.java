package org.traficinfo.dsfunctions.m3m;

import org.traficinfo.dsfunctions.AbstractGeoJsonOutFunction;
import org.traficinfo.dsfunctions.GeoJsonDataSource;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;

/** Manifest-to-GeoJSON handler for MMM; geometry is supplied by the stored records. */
public class GeoJsonOutFunction extends AbstractGeoJsonOutFunction {
    public static final String DEFAULT_INPUT_BUCKET = GeoJsonDataSource.MMM.getDefaultInputBucket();
    public static final String GEOJSON_OBJECT_NAME = GeoJsonDataSource.MMM.getGeoJsonObjectName();
    public static final String CONFIG_KEY_OSM_BUCKET = "BUCKETNAME_OSM";
    public static final String CONFIG_KEY_OSM_CACHE_OBJECT = "OSM_CACHE_OBJECT";
    public static final String DEFAULT_OSM_CACHE_OBJECT = GeoJsonDataSource.MMM.getOsmCacheObjectName();

    public GeoJsonOutFunction() {
        super(GeoJsonDataSource.MMM);
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        super(GeoJsonDataSource.MMM, inputClient, outputClient, osmInfoProvider);
    }
}
