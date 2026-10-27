package org.traficinfo.dsfunctions;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.RenameObjectDetails;
import com.oracle.bmc.objectstorage.model.ListObjects;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
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
import java.util.ArrayList;
import java.util.List;
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
        // getResource callers close response streams with try-with-resources. Suppress only
        // the SDK's generic startup reminder; keep stream auto-close and other warnings.
        client = ObjectStorageClient.builder()
                .isStreamWarningEnabled(false)
                .build(provider);

        this.namespaceName = namespace;
        this.bucketName = bucketName;
    }

    public ObjectStoreClient(ObjectStorage client, String namespace, String bucketName) {
        this.client = client;
        this.namespaceName = namespace;
        this.bucketName = bucketName;
    }

    /** Lists every object name in the bucket, following all listing pages. */
    public List<String> listResources() {
        List<String> names = new ArrayList<>();
        String start = null;
        do {
            ListObjects page = client.listObjects(ListObjectsRequest.builder()
                    .namespaceName(namespaceName).bucketName(bucketName).start(start).build())
                    .getListObjects();
            page.getObjects().forEach(object -> names.add(object.getName()));
            start = page.getNextStartWith();
        } while (start != null && !start.isEmpty());
        return names;
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
        writeResource(resourceName, contentType, metadata, inputStream, false);
    }

    /** Atomically creates an Object only if its name is absent. No ETag is involved. */
    public boolean createResource(String resourceName, String contentType, OOSResourceMetadata metadata,
                                  InputStream inputStream) throws IOException {
        return writeResource(resourceName, contentType, metadata, inputStream, true);
    }

    public void deleteResource(String resourceName) {
        client.deleteObject(DeleteObjectRequest.builder().namespaceName(namespaceName)
                .bucketName(bucketName).objectName(resourceName).build());
    }

    private boolean writeResource(String resourceName, String contentType, OOSResourceMetadata metadata,
                                  InputStream inputStream, boolean createOnly) throws IOException {
        // Stage compressed bytes on disk to avoid buffering an entire dataset in memory.
        Path compressedFile = Files.createTempFile("traficinfo-", ".gz");
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
                        .ifNoneMatch(createOnly ? "*" : null)
                        .contentEncoding("gzip")
                        .contentLength(Files.size(compressedFile))
                        .opcMeta(metadata.getMetadata())
                        .putObjectBody(compressedInput).build();

                try {
                    final PutObjectResponse response = client.putObject(request);
                    LOG.info("OOS PUT response = {}", response.toString());
                    return true;
                } catch (BmcException failure) {
                    if (createOnly && failure.getStatusCode() == 412) return false;
                    throw failure;
                }
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
