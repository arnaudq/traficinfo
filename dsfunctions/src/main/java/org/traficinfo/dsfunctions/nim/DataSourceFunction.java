package org.traficinfo.dsfunctions.nim;

import org.traficinfo.dsfunctions.AbstractDataSourceFunction;
import org.traficinfo.dsfunctions.DataSourceSettings;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;

/** Source download handler for Nîmes Métropole. */
public class DataSourceFunction extends AbstractDataSourceFunction {
    public DataSourceFunction() {
        super("NIM");
    }

    DataSourceFunction(DataSourceSettings settings, HttpClient httpClient, ObjectStoreClient objectStore) {
        super("NIM", settings, httpClient, objectStore);
    }
}
