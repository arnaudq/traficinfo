package org.traficinfo.dsfunctions.dpt30;

import org.traficinfo.dsfunctions.AbstractDataSourceFunction;
import org.traficinfo.dsfunctions.DataSourceSettings;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;

/** Source download handler for DPT30. */
public class DataSourceFunction extends AbstractDataSourceFunction {
    public DataSourceFunction() {
        super("DPT30");
    }

    DataSourceFunction(DataSourceSettings settings, HttpClient httpClient, ObjectStoreClient objectStore) {
        super("DPT30", settings, httpClient, objectStore);
    }
}
