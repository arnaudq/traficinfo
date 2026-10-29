package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.storage.DataManifest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Reads one complete publication, including years retained from inactive datasets. */
public final class ManifestComptageReader {
    private final ObjectStoreClient storage;
    private final ObjectMapper mapper = new ObjectMapper();

    public ManifestComptageReader(ObjectStoreClient storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    /**
     * Reads the manifest once and follows only its exact yearly Object references, in year order.
     * Returns records only after every referenced Object passes validation. Storage decodes gzip.
     */
    public List<? extends Comptage> read(String dataSourceId) throws IOException {
        PreprocessingSource source = PreprocessingSource.forId(dataSourceId);
        DataManifest manifest;
        try (InputStream input = storage.getResource(dataSourceId + "_Manifest.json")) {
            manifest = mapper.readValue(input, DataManifest.class);
        }
        ManifestValidation.validateManifest(manifest, dataSourceId);
        List<Comptage> counts = new ArrayList<>();
        for (var entry : new TreeMap<>(manifest.years()).entrySet()) {
            counts.addAll(ManifestValidation.readYearlyObject(storage, entry.getKey(), entry.getValue(),
                    source.parser.getDsComptageClass()));
        }
        return counts;
    }
}
