package org.traficinfo.dsfunctions.dpt76;

import org.traficinfo.dsfunctions.AbstractDataSourceFunction;
import org.traficinfo.dsfunctions.DataSourceSettings;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;

/** Source download handler for DPT76. */
public class DataSourceFunction extends AbstractDataSourceFunction {
    public DataSourceFunction() {
        super("DPT76");
    }

    DataSourceFunction(DataSourceSettings settings, HttpClient httpClient, ObjectStoreClient objectStore) {
        super("DPT76", settings, httpClient, objectStore);
    }
}
