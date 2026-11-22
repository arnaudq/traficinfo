package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.history.CounterHistory;
import org.traficinfo.parsers.history.HistoryExport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Coordinates private-history reading, canonical assembly and public JSON publication.
 *
 * <p>{@link ManifestComptageReader} validates the complete input snapshot and
 * {@link HistoryExport} builds its {@link CounterHistory}. The result is serialized to
 * a temporary file before the single public object is replaced. {@link ObjectStoreClient}
 * compresses the upload; this publisher sets JSON content type and a five-minute cache lifetime.
 * Clients are supplied by the caller and remain owned by that caller.
 */
public final class HistoryPublisher {
    private final ObjectStoreClient inputStore;
    private final ObjectStoreClient outputStore;
    private final Clock clock;

    public HistoryPublisher(ObjectStoreClient inputStore, ObjectStoreClient outputStore) {
        this(inputStore, outputStore, Clock.systemUTC());
    }

    HistoryPublisher(ObjectStoreClient inputStore, ObjectStoreClient outputStore, Clock clock) {
        this.inputStore = Objects.requireNonNull(inputStore);
        this.outputStore = Objects.requireNonNull(outputStore);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Replaces {@code history/<datasourceId>.json} only after input validation and serialization succeed.
     *
     * @param datasourceId configured datasource ID used to select its manifest and record class
     * @throws IOException if input validation, history assembly, serialization or upload fails
     */
    public void publish(String datasourceId) throws IOException {
        var publication = new ManifestComptageReader(inputStore).readPublication(datasourceId);
        var history = new HistoryExport().build(publication, Instant.now(clock));
        var file = Files.createTempFile("traficinfo-history-", ".json");
        try {
            new ObjectMapper().writeValue(file.toFile(), history);
            try (InputStream input = Files.newInputStream(file)) {
                outputStore.putResource("history/" + datasourceId + ".json", "application/json",
                        new OOSResourceMetadata(), input, "public, max-age=300");
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
