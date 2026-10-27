package org.traficinfo.dsfunctions.m3m;

import org.traficinfo.dsfunctions.AbstractDataSourceFunction;
import org.traficinfo.dsfunctions.DataSourceSettings;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;

/** Source download handler for MMM. */
public class DataSourceFunction extends AbstractDataSourceFunction {
    public DataSourceFunction() {
        super("MMM");
    }

    DataSourceFunction(DataSourceSettings settings, HttpClient httpClient, ObjectStoreClient objectStore) {
        super("MMM", settings, httpClient, objectStore);
    }
}
