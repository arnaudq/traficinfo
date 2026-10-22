package org.traficinfo.dsfunctions.mrn;

import org.traficinfo.dsfunctions.AbstractCsvDataSourceFunction;
import org.traficinfo.dsfunctions.CsvDataSource;
import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;

import java.net.URI;

/** CSV download handler for MRN. */
public class DataSourceFunction extends AbstractCsvDataSourceFunction {
    public static final String DEFAULT_BUCKET_NAME = CsvDataSource.MRN.getDefaultInputBucket();
    public static final String CSV_OBJECT_NAME = CsvDataSource.MRN.getCsvObjectName();
    public static final String CSV_OBJECT_ARCHIVE_NAME = CsvDataSource.MRN.getCsvArchiveNamePattern();
    public static final URI CSV_URI = CsvDataSource.MRN.getCsvUri();

    public DataSourceFunction() {
        super(CsvDataSource.MRN);
    }

    DataSourceFunction(HttpClient httpClient, ObjectStoreClient objectStore) {
        super(CsvDataSource.MRN, httpClient, objectStore);
    }
}
