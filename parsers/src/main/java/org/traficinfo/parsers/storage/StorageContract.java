package org.traficinfo.parsers.storage;

/** Shared version gate for storage envelopes. */
public final class StorageContract {
    public static final int VERSION = 1;

    private StorageContract() {}

    static void requireVersion(int version) {
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported storage contract version: " + version);
        }
    }
}
