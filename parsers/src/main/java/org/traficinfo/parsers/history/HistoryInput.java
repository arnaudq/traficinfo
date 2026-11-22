package org.traficinfo.parsers.history;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.storage.DataManifest;

import java.util.List;

/**
 * Source publication supplied to {@link HistoryExport}, before canonicalization.
 * The storage caller validates the manifest and its referenced yearly objects before constructing it.
 * This representation has no Object Storage or function-runtime dependencies.
 *
 * @param manifest source revision, dataset attribution and yearly object references
 * @param counts records from every referenced year, retaining their source-specific class
 */
public record HistoryInput(DataManifest manifest, List<? extends Comptage> counts) {
    public HistoryInput {
        counts = List.copyOf(counts);
    }
}
