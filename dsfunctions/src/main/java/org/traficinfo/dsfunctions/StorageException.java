package org.traficinfo.dsfunctions;

/** Storage request failure, retaining provider diagnostics without exposing provider SDK types. */
public class StorageException extends RuntimeException {
    private final String requestId;

    public StorageException(String message, Throwable cause, String requestId) {
        super(message, cause);
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
