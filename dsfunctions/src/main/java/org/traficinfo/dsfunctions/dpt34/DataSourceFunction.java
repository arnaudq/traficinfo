package org.traficinfo.dsfunctions.dpt34;

import org.traficinfo.dsfunctions.AbstractCsvDataSourceFunction;
import org.traficinfo.dsfunctions.CsvDataSource;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;

import java.net.URI;

/** CSV download handler for DPT34. */
public class DataSourceFunction extends AbstractCsvDataSourceFunction {
    public static final String DEFAULT_BUCKET_NAME = CsvDataSource.DPT34.getDefaultInputBucket();
    public static final String CSV_OBJECT_NAME = CsvDataSource.DPT34.getCsvObjectName();
    public static final String CSV_OBJECT_ARCHIVE_NAME = CsvDataSource.DPT34.getCsvArchiveNamePattern();
    public static final URI CSV_URI = CsvDataSource.DPT34.getCsvUri();

    public DataSourceFunction() {
        super(CsvDataSource.DPT34);
    }

    DataSourceFunction(HttpClient httpClient, ObjectStoreClient objectStore) {
        super(CsvDataSource.DPT34, httpClient, objectStore);
    }
}
