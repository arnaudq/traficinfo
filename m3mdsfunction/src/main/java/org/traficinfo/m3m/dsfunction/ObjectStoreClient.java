package org.traficinfo.m3m.dsfunction;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.RenameObjectDetails;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.requests.RenameObjectRequest;
import com.oracle.bmc.objectstorage.responses.HeadObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import com.oracle.bmc.objectstorage.responses.RenameObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * ObjectStore client.
 */
public class ObjectStoreClient {

    private static final Logger LOG = LoggerFactory.getLogger(ObjectStoreClient.class);

    private static final String PROFILE = "arnaudq";

    private final ObjectStorage client;
    private final String namespaceName;
    private final String bucketName;

    public ObjectStoreClient(final boolean usePrincipalAuth, final String namespace, final String bucketName) {

        LOG.info("Configuring Objectstore using bucket {}", bucketName);
        final BasicAuthenticationDetailsProvider provider;
        if (usePrincipalAuth) {
            provider = ResourcePrincipalAuthenticationDetailsProvider.builder().build();
        } else {
            // Configuring the AuthenticationDetailsProvider. It's assuming there is a default OCI config file
            // "~/.oci/config", and a profile in that config with the name "DEFAULT". Make changes to the following
            // line if needed and use ConfigFileReader.parse(configurationFilePath, profile);

            final ConfigFileReader.ConfigFile configFile;
            try {
                configFile = ConfigFileReader.parse(ConfigFileReader.DEFAULT_FILE_PATH, PROFILE);
            } catch (IOException ioe) {
                throw new IllegalArgumentException("cant read OCI config", ioe);
            }
            provider = new ConfigFileAuthenticationDetailsProvider(configFile);
        }

        // both providers are RegionProvider so no need to set region
        LOG.info("Configuring Objectstore client");
        client = ObjectStorageClient.builder().build(provider);

        this.namespaceName = namespace;
        this.bucketName = bucketName;
    }

    public void putResource(String resourceName, String contentType, OOSResourceMetadata metadata,
                              InputStream inputStream) {
        PutObjectRequest request = PutObjectRequest.builder()
                .namespaceName(namespaceName)
                .bucketName(bucketName)
                .objectName(resourceName)
                .contentType(contentType)
                .opcMeta(metadata.getMetadata())
                .putObjectBody(inputStream).build();

        final PutObjectResponse response = client.putObject(request);

        LOG.info("OOS PUT response = {}", response.toString());
    }

    public OOSResourceMetadata getResourceMetadata(String resourceName) {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .namespaceName(namespaceName)
                .bucketName(bucketName)
                .objectName(resourceName).build();

        try {
            final HeadObjectResponse response = client.headObject(request);
            LOG.info("OOS HEAD response = {}", response.toString());
            LOG.info("OOS HEAD response = {}", response.getOpcMeta().entrySet());

            return new OOSResourceMetadata(response.getOpcMeta());
        } catch (BmcException bmce) {
           if (bmce.getStatusCode() == 404) {
               LOG.info("OOS HEAD response - resource  {} does not exist", resourceName);
               return null;
           } else {
               LOG.info("Getting exception on HEAD", bmce);
               throw bmce;
           }
        }
    }

    public void renameResource(String oldName, String newName) {
        RenameObjectDetails details = RenameObjectDetails.builder()
                .sourceName(oldName).newName(newName).build();
        RenameObjectRequest request = RenameObjectRequest.builder()
                .namespaceName(namespaceName)
                .bucketName(bucketName)
                .renameObjectDetails(details).build();
        try {
            RenameObjectResponse response = client.renameObject(request);
        } catch (BmcException bmce) {
            LOG.info("Getting exception on rename", bmce);
        }
    }
}
