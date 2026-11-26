package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.storage.DataManifest;
import org.traficinfo.parsers.history.HistoryInput;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reads one private manifest snapshot and validates all of its referenced yearly count objects.
 * Includes years retained from inactive datasets and follows exact object references without listing.
 * {@link HistoryInput} keeps the manifest provenance alongside its records for history export;
 * {@link #read(String)} exposes only the records for consumers such as GeoJSON generation.
 */
public final class ManifestComptageReader {
    private final ObjectStore storage;
    private final ObjectMapper mapper = new ObjectMapper();

    public ManifestComptageReader(ObjectStore storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    /**
     * Reads the manifest once and follows only its exact yearly Object references, in year order.
     * Returns records only after every referenced Object passes validation. Storage decodes gzip.
     */
    public List<? extends Comptage> read(String dataSourceId) throws IOException {
        return readPublication(dataSourceId).counts();
    }

    /**
     * Reads the manifest once and validates each referenced object's checksum, record count and year.
     *
     * @param dataSourceId configured datasource ID selecting the manifest and concrete record class
     * @return manifest and all validated records from that snapshot, in year order
     * @throws IOException if reading, deserialization or publication validation fails
     */
    public HistoryInput readPublication(String dataSourceId) throws IOException {
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
        return new HistoryInput(manifest, counts);
    }

}
