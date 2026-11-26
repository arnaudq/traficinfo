package org.traficinfo.dsfunctions;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Storage operations used by the existing publication lifecycle, bound to one configured location. */
public interface ObjectStore extends AutoCloseable {
    /** Lists all object names, including every listing page. */
    List<String> listResources();

    /** Returns the decoded body; the caller closes it. Missing objects throw {@link ObjectNotFoundException}. */
    InputStream getResource(String name) throws IOException;

    /** Returns custom metadata, or null only when the object is absent. */
    ObjectMetadata getResourceMetadata(String name);

    /** Writes the body without taking ownership of the caller's stream. */
    void putResource(String name, String contentType, ObjectMetadata metadata, InputStream body) throws IOException;

    /** Writes with an explicit HTTP cache lifetime, without closing the caller's stream. */
    void putResource(String name, String contentType, ObjectMetadata metadata, InputStream body,
                     String cacheControl) throws IOException;

    /** Atomically creates an object; false means it already exists. Never replaces an existing object. */
    boolean createResource(String name, String contentType, ObjectMetadata metadata, InputStream body) throws IOException;

    void deleteResource(String name);

    @Override
    void close() throws IOException;
}
