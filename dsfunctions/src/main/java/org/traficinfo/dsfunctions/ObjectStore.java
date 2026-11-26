package org.traficinfo.dsfunctions;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Provider-independent storage operations, bound to one configured storage location.
 * Callers supply object keys; addressing, authentication and provider response translation
 * belong to the implementation.
 *
 * <p>Bodies passed to writes and returned by reads are uncompressed. The production adapter
 * writes gzip objects while retaining their keys and content types, and decodes gzip reads;
 * existing uncompressed objects remain readable. Callers close returned streams and the
 * store, and retain ownership of streams supplied to writes. Implementations own and clean
 * up any temporary files used to transfer or compress bodies, including on failure.
 *
 * <p>Request failures use {@link StorageException}, retaining the provider cause and an
 * opaque diagnostic request ID when available. An absent object is distinguished as
 * specified by each operation. This contract adds no retries, multi-object transactions
 * or snapshots; callers determine publication ordering and validate content checksums.
 */
public interface ObjectStore extends AutoCloseable {
    /**
     * Lists all object keys in the configured location, following every listing page.
     *
     * @return all keys, or an empty list when the location contains no objects; no ordering is promised
     * @throws StorageException if listing fails
     */
    List<String> listResources();

    /**
     * Opens an object's body, decoding gzip when indicated by its content encoding.
     * The caller must close the returned stream, including when parsing fails.
     *
     * @param name object key
     * @return decoded body; never null
     * @throws ObjectNotFoundException if the object is absent
     * @throws StorageException if the read request otherwise fails
     * @throws IOException if the body cannot be decoded or read
     */
    InputStream getResource(String name) throws IOException;

    /**
     * Inspects custom metadata without downloading the body. Content type, content encoding
     * and cache control are HTTP settings, not entries in this custom-metadata map.
     *
     * @param name object key
     * @return custom metadata; empty for an existing object with no custom metadata,
     *         or null only when the object is absent
     * @throws StorageException if inspection fails for a reason other than absence
     */
    ObjectMetadata getResourceMetadata(String name);

    /**
     * Writes or replaces an object without specifying an HTTP cache lifetime.
     * Uses the same body ownership and storage encoding as the overload with cache control.
     *
     * @param name object key, retained when encoding the stored body
     * @param contentType original body's media type
     * @param metadata custom metadata to store with the object
     * @param body uncompressed input; consumed but not closed by this operation
     * @throws StorageException if the write request fails
     * @throws IOException if reading, compression or temporary-file handling fails
     */
    void putResource(String name, String contentType, ObjectMetadata metadata, InputStream body) throws IOException;

    /**
     * Writes or replaces an object with the supplied HTTP cache-control setting.
     * For example, public history uses a five-minute cache lifetime. Compression must
     * preserve the object key, content type, custom metadata and cache-control setting.
     *
     * @param name object key
     * @param contentType original body's media type
     * @param metadata custom metadata to store with the object
     * @param body uncompressed input; consumed but not closed by this operation
     * @param cacheControl HTTP Cache-Control value, or null to leave it unspecified
     * @throws StorageException if the write request fails
     * @throws IOException if reading, compression or temporary-file handling fails
     */
    void putResource(String name, String contentType, ObjectMetadata metadata, InputStream body,
                     String cacheControl) throws IOException;

    /**
     * Atomically creates an object only if its key is absent. Used for immutable yearly
     * objects and the preprocessing publication lock; an existence check followed by a
     * normal write does not satisfy this contract. Existing objects are never replaced.
     *
     * @param name object key
     * @param contentType original body's media type
     * @param metadata custom metadata to store with the object
     * @param body uncompressed input; may be consumed even when the key exists, but is not closed
     * @return true if created, false only if the key already exists
     * @throws StorageException if the request fails for a reason other than an existing key
     * @throws IOException if reading, compression or temporary-file handling fails
     */
    boolean createResource(String name, String contentType, ObjectMetadata metadata, InputStream body) throws IOException;

    /**
     * Deletes an object, for example an acquired lock or a processed approval submission.
     *
     * @param name object key
     * @throws StorageException if the delete request fails
     */
    void deleteResource(String name);

    /**
     * Releases the implementation's storage client. Callers remain responsible for closing
     * streams obtained from this store; closing it does not replace stream cleanup.
     *
     * @throws IOException if client cleanup fails
     */
    @Override
    void close() throws IOException;
}
