package org.traficinfo.dsfunctions.m3m;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;

public class DataSourceFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_BUCKET_NAME = "BUCKETNAME";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";


    private static final Logger LOG = LoggerFactory.getLogger(DataSourceFunction.class);

    private String namespace;
    private String bucketName;

    private HttpClient httpClient;
    private ObjectStoreClient oosClient;

    public DataSourceFunction() {
        LOG.info("DataSource3M Function booting");
    }

    @FnConfiguration
    public void config(RuntimeContext ctx) {
        LOG.info("DataSource3M Function configuring using {}", ctx);

        namespace = ctx.getConfigurationByKey(CONFIG_KEY_NAMESPACE).orElse("");
        bucketName = ctx.getConfigurationByKey(CONFIG_KEY_BUCKET_NAME).orElse("");
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey(CONFIG_KEY_USE_RESOURCE_PRINCIPAL).orElse("true"));


        httpClient = new HttpClient();
        oosClient = new ObjectStoreClient(useResourcePrincipal, namespace, bucketName);

        LOG.info("DataSource3M Function configured. Namespace = {}, Bucketname = {}", namespace, bucketName);
    }

    public String handleRequest(String input) {

        try {
            storeResource(URI.create("https://data.montpellier3m.fr/sites/default/files/ressources/MMM_MMM_Comptage.csv"),
                    "MMM_MMM_Comptage.csv", "text/csv", "MMM_MMM_Comptage_%s.csv");
            storeResource(URI.create("https://data.montpellier3m.fr/sites/default/files/ressources/MMM_MMM_Comptage.json"),
                    "MMM_MMM_Comptage.json", "application/json", "MMM_MMM_Comptage_%s.json");

            return "OK";
        } catch (RuntimeException re) {
            LOG.warn("error while processing: {}", re.getMessage(), re);
            return "KO";
        }
    }

    private void storeResource(URI uri, String oosName, String contentType, String oosArchiveName) {

        LOG.info("Processing {}", uri.toASCIIString());

        OOSResourceMetadata metadata = oosClient.getResourceMetadata(oosName);
        String currentEtag = null;
        if (metadata !=  null) {
            currentEtag = metadata.getETag();
            LOG.info("Current known ETag for {} = {}", oosName, currentEtag);
        } else {
            LOG.info("No previous version stored in OOS");
        }

        try (HttpClient.EntityBody entityBody = httpClient.get(uri, currentEtag)) {
            if (entityBody.getInputStream() != null) {
                // move the old resource if present
                if (metadata != null) {
                    oosClient.renameResource(oosName, String.format(oosArchiveName, metadata.getLastModified()));
                }
                // updload the new resource
                OOSResourceMetadata updatedMetadata = new OOSResourceMetadata();
                updatedMetadata.setETag(entityBody.getEtag());
                updatedMetadata.setLastModified(entityBody.getLastModified());
                oosClient.putResource(oosName, contentType, updatedMetadata,
                        entityBody.getInputStream());
            } else {
                LOG.info("No change for file = {}", oosName);
            }
        } catch (IOException ioe) {
            LOG.warn("failed to read {}", oosName, ioe);
        }
    }
}