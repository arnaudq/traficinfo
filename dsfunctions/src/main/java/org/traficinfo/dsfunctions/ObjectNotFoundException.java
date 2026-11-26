package org.traficinfo.dsfunctions;

/** An absent object, distinct from authorization, service or other storage failures. */
public final class ObjectNotFoundException extends StorageException {
    public ObjectNotFoundException(String message, Throwable cause, String requestId) {
        super(message, cause, requestId);
    }
}
