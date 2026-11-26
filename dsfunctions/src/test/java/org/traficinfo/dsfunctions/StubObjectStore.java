package org.traficinfo.dsfunctions;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Test storage whose unused operations fail without constructing a provider SDK client. */
public class StubObjectStore implements ObjectStore {
    @Override public List<String> listResources() { throw new UnsupportedOperationException(); }
    @Override public InputStream getResource(String name) throws IOException { throw new UnsupportedOperationException(); }
    @Override public ObjectMetadata getResourceMetadata(String name) { throw new UnsupportedOperationException(); }
    @Override public void putResource(String name, String type, ObjectMetadata metadata, InputStream body)
            throws IOException { throw new UnsupportedOperationException(); }
    @Override public void putResource(String name, String type, ObjectMetadata metadata, InputStream body,
                                      String cacheControl) throws IOException { throw new UnsupportedOperationException(); }
    @Override public boolean createResource(String name, String type, ObjectMetadata metadata, InputStream body)
            throws IOException { throw new UnsupportedOperationException(); }
    @Override public void deleteResource(String name) { throw new UnsupportedOperationException(); }
    @Override public void close() throws IOException { }
}
