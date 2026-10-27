package org.traficinfo.dsfunctions.dpt34;

import org.traficinfo.dsfunctions.AbstractDataSourceFunction;
import org.traficinfo.dsfunctions.DataSourceSettings;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;

/** Source download handler for DPT34. */
public class DataSourceFunction extends AbstractDataSourceFunction {
    public DataSourceFunction() {
        super("DPT34");
    }

    DataSourceFunction(DataSourceSettings settings, HttpClient httpClient, ObjectStoreClient objectStore) {
        super("DPT34", settings, httpClient, objectStore);
    }
}
