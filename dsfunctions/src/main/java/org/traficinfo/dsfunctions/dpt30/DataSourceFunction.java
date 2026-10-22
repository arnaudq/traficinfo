package org.traficinfo.dsfunctions.dpt30;

import org.traficinfo.dsfunctions.AbstractCsvDataSourceFunction;
import org.traficinfo.dsfunctions.CsvDataSource;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;

import java.net.URI;

/** CSV download handler for DPT30. */
public class DataSourceFunction extends AbstractCsvDataSourceFunction {
    public static final String DEFAULT_BUCKET_NAME = CsvDataSource.DPT30.getDefaultInputBucket();
    public static final String CSV_OBJECT_NAME = CsvDataSource.DPT30.getCsvObjectName();
    public static final String CSV_OBJECT_ARCHIVE_NAME = CsvDataSource.DPT30.getCsvArchiveNamePattern();
    public static final URI CSV_URI = CsvDataSource.DPT30.getCsvUri();

    public DataSourceFunction() {
        super(CsvDataSource.DPT30);
    }

    DataSourceFunction(HttpClient httpClient, ObjectStoreClient objectStore) {
        super(CsvDataSource.DPT30, httpClient, objectStore);
    }
}
