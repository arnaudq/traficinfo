package org.traficinfo.dsfunctions.m3m;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.RenameObjectDetails;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.requests.RenameObjectRequest;
import com.oracle.bmc.objectstorage.responses.HeadObjectResponse;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import com.oracle.bmc.objectstorage.responses.RenameObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;

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

    ObjectStoreClient(ObjectStorage client, String namespace, String bucketName) {
        this.client = client;
        this.namespaceName = namespace;
        this.bucketName = bucketName;
    }

    /**
     * Downloads an object, decoding gzip content when present. The caller must close the stream.
     */
    public InputStream getResource(String resourceName) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .namespaceName(namespaceName)
                .bucketName(bucketName)
                .objectName(resourceName).build();
        GetObjectResponse response = client.getObject(request);
        InputStream input = response.getInputStream();
        if ("gzip".equalsIgnoreCase(response.getContentEncoding())) {
            try {
                return new GZIPInputStream(input);
            } catch (IOException | RuntimeException failure) {
                try {
                    input.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
        return input;
    }

    public void putResource(String resourceName, String contentType, OOSResourceMetadata metadata,
                              InputStream inputStream) throws IOException {
        // Stage compressed bytes on disk to avoid buffering an entire dataset in memory.
        Path compressedFile = Files.createTempFile("m3mds-", ".gz");
        try {
            try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(compressedFile))) {
                inputStream.transferTo(gzip);
            }
            try (InputStream compressedInput = Files.newInputStream(compressedFile)) {
                PutObjectRequest request = PutObjectRequest.builder()
                        .namespaceName(namespaceName)
                        .bucketName(bucketName)
                        .objectName(resourceName)
                        .contentType(contentType)
                        .contentEncoding("gzip")
                        .contentLength(Files.size(compressedFile))
                        .opcMeta(metadata.getMetadata())
                        .putObjectBody(compressedInput).build();

                final PutObjectResponse response = client.putObject(request);
                LOG.info("OOS PUT response = {}", response.toString());
            }
        } finally {
            Files.deleteIfExists(compressedFile);
        }
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
