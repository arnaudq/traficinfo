package org.traficinfo.dsfunctions.m3m;

import org.traficinfo.dsfunctions.HttpClient;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.DataSourceSettings;
import org.traficinfo.dsfunctions.SourceObjectPublisher;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class DataSourceFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_BUCKET_NAME = "BUCKETNAME";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceFunction.class);

    private String namespace;
    private String bucketName;

    private HttpClient httpClient;
    private ObjectStoreClient oosClient;
    private DataSourceSettings settings = new DataSourceSettings("MMM", "current", List.of(
            new DataSourceSettings.Input(
                    "https://data.montpellier3m.fr/sites/default/files/ressources/MMM_MMM_Comptage.json",
                    "MMM_MMM_Comptage.json", "application/json"),
            new DataSourceSettings.Input(
                    "https://data.montpellier3m.fr/sites/default/files/ressources/MMM_MMM_Comptage.csv",
                    "MMM_MMM_Comptage.csv", "text/csv")));

    public DataSourceFunction() {
        LOG.info("DataSource3M Function booting");
    }

    DataSourceFunction(HttpClient httpClient, ObjectStoreClient oosClient) {
        this.httpClient = httpClient;
        this.oosClient = oosClient;
    }

    @FnConfiguration
    public void config(RuntimeContext ctx) {
        LOG.info("DataSource3M Function configuring using {}", ctx);

        settings = DataSourceSettings.from(ctx, settings);
        namespace = ctx.getConfigurationByKey(CONFIG_KEY_NAMESPACE).orElse("");
        bucketName = ctx.getConfigurationByKey(CONFIG_KEY_BUCKET_NAME).orElse("");
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey(CONFIG_KEY_USE_RESOURCE_PRINCIPAL).orElse("true"));

        httpClient = new HttpClient();
        oosClient = new ObjectStoreClient(useResourcePrincipal, namespace, bucketName);

        LOG.info("DataSource3M Function configured. Namespace = {}, Bucketname = {}", namespace, bucketName);
    }

    public String handleRequest(String input) {
        new SourceObjectPublisher(httpClient, oosClient).publish(settings);
        return "OK";
    }
}
