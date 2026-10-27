package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.geojson.FeatureCollection;
import org.geojson.GeoJsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.traficinfo.parsers.storage.ComptageJson;
import org.traficinfo.parsers.storage.DataManifest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Publishes a complete manifest last, while retaining immutable Objects for unchanged years. */
public final class PreprocessingPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(PreprocessingPublisher.class);
    private final ObjectStoreClient storage;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ComptageJson countsJson = new ComptageJson();

    public PreprocessingPublisher(ObjectStoreClient storage) { this(storage, LOG); }

    PreprocessingPublisher(ObjectStoreClient storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
    }

    public DataManifest publish(PreprocessingSettings settings) throws IOException {
        String dataSourceId = settings.source().dataSourceId();
        String lock = dataSourceId + "_Preprocessing.lock";
        byte[] owner = mapper.writeValueAsBytes(Map.of("owner", UUID.randomUUID().toString(),
                "startedAt", Instant.now().toString()));
        if (!storage.createResource(lock, "application/json", new OOSResourceMetadata(), new ByteArrayInputStream(owner))) {
            throw new IOException("Preprocessing already locked for " + dataSourceId);
        }
        // Locks never expire automatically: a timed-out invocation must not race a replacement publisher.
        try {
            return publishLocked(settings);
        } finally {
            storage.deleteResource(lock);
        }
    }

    private DataManifest publishLocked(PreprocessingSettings settings) throws IOException {
        DataSourceSettings source = settings.source();
        String manifestName = source.dataSourceId() + "_Manifest.json";
        PreprocessingSource parser = PreprocessingSource.forId(source.dataSourceId());

        // Load the current publication so inactive datasets and unchanged years can be retained.
        DataManifest previous = null;
        if (storage.getResourceMetadata(manifestName) != null) {
            try (InputStream input = storage.getResource(manifestName)) {
                previous = mapper.readValue(input, DataManifest.class);
            }
            validateManifest(previous, source.dataSourceId());
        }

        Map<String, Path> files = new HashMap<>();
        Map<String, Map<String, String>> inputMetadata = new HashMap<>();
        try {
            // Check every input belongs to the configured source and capture metadata for the final recheck.
            for (DataSourceSettings.Input input : source.inputs()) {
                inputMetadata.put(input.objectName(), sourceMetadata(source, input));
            }

            // Keep the existing dataset scope intact when processing another download.
            Map<String, DataManifest.Input> inputs = new TreeMap<>();
            DataManifest.Dataset oldDataset = previous == null ? null : previous.datasets().get(source.datasetId());
            if (oldDataset != null && !settings.years().containsAll(oldDataset.years())) {
                throw new IOException("Reducing a dataset's year scope requires a new datasetId");
            }

            // Stage inputs locally and compare CSV headers with their last published definitions.
            for (DataSourceSettings.Input input : source.inputs()) {
                Path file = Files.createTempFile("traficinfo-preprocessing-", ".input");
                files.put(input.objectName(), file);
                try (InputStream content = storage.getResource(input.objectName())) {
                    Files.copy(content, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                boolean csv = input.contentType().equals("text/csv");
                List<String> headers = csv ? headers(file, parser) : null;
                if (oldDataset != null) {
                    DataManifest.Input oldInput = oldDataset.inputs().get(input.objectName());
                    if (oldInput != null && csv && oldInput.headers() != null) {
                        Set<String> current = headerKeys(headers, parser);
                        Set<String> previousHeaders = headerKeys(oldInput.headers(), parser);
                        List<String> addedHeaders = headers.stream()
                                .filter(header -> !previousHeaders.contains(parser.headerKey(header))).toList();
                        if (!addedHeaders.isEmpty()) {
                            logger.warn("CSV headers added: dataSourceId={}, datasetId={}, input={}, headers={}",
                                    source.dataSourceId(), source.datasetId(), input.objectName(), addedHeaders);
                        }
                        if (!current.containsAll(previousHeaders)) {
                            throw new IOException("CSV headers removed or renamed for " + input.objectName());
                        }
                    }
                }

                inputs.put(input.objectName(), new DataManifest.Input(input.url(), csv ? "CSV" : "GEOJSON", headers));
            }

            if (oldDataset != null && !inputs.keySet().equals(oldDataset.inputs().keySet())) {
                throw new IOException("Changing a dataset's input names requires a new datasetId");
            }

            // Parse source-specific counts and attach companion geometry for MMM.
            DataSourceSettings.Input csv = source.inputs().stream()
                    .filter(input -> input.contentType().equals("text/csv")).findFirst().orElseThrow();
            List<? extends Comptage> counts;
            try (Reader reader = reader(files.get(csv.objectName()))) {
                counts = parser.parser.getParser().parseComptages(reader, new Properties());
            }
            if (parser == PreprocessingSource.MMM) attachGeometry(counts, source, files);

            // Validate counter identities and expected years before preparing the yearly arrays.
            Map<Integer, List<byte[]>> byYear = new TreeMap<>();
            for (Comptage count : counts) {
                if (!settings.years().contains(count.getAnnee())) throw new IOException("Unexpected year: " + count.getAnnee());
                if (count.getCodeCentrale() == null || count.getCodeCentrale().isBlank()) throw new IOException("Missing counter ID");
                byYear.computeIfAbsent(count.getAnnee(), ignored -> new ArrayList<>()).add(countsJson.write(count));
            }
            if (!byYear.keySet().equals(new HashSet<>(settings.years()))) throw new IOException("Missing expected years: " + settings.years());

            // Retain existing year references; upload only changed years under a fresh immutable path.
            String revision = UUID.randomUUID().toString();
            Map<Integer, DataManifest.YearData> years = new TreeMap<>();
            if (previous != null) years.putAll(previous.years());
            for (var entry : byYear.entrySet()) {
                // Canonical property and record ordering makes source row reordering a no-op.
                entry.getValue().sort(java.util.Arrays::compareUnsigned);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                output.write('[');
                for (int index = 0; index < entry.getValue().size(); index++) {
                    if (index > 0) output.write(',');
                    output.write(entry.getValue().get(index));
                }
                output.write(']');
                byte[] payload = output.toByteArray();
                String checksum = HexFormat.of().formatHex(digest().digest(payload));

                DataManifest.YearData old = years.get(entry.getKey());
                if (old != null && checksum.equals(old.sha256()) && source.datasetId().equals(old.datasetId())
                        && parser.parserVersion.equals(old.parserVersion()) && old.recordCount() == entry.getValue().size()) continue;

                String object = "data/" + revision + "/" + source.dataSourceId() + "_Comptage_" + entry.getKey() + ".json";
                if (!storage.createResource(object, "application/json", new OOSResourceMetadata(), new ByteArrayInputStream(payload))) {
                    throw new IOException("Immutable yearly Object already exists: " + object);
                }
                years.put(entry.getKey(), new DataManifest.YearData(object, checksum, entry.getValue().size(),
                        source.datasetId(), parser.parserVersion));
            }

            // Assemble the complete candidate manifest, retaining inactive dataset definitions.
            Map<String, DataManifest.Dataset> datasets = new TreeMap<>();
            if (previous != null) datasets.putAll(previous.datasets());
            datasets.put(source.datasetId(), new DataManifest.Dataset(settings.datasetUrl(), settings.years(), inputs));
            DataManifest next = new DataManifest(DataManifest.VERSION, source.dataSourceId(), revision,
                    Instant.now().toString(), source.datasetId(), datasets, years);

            // Read back all referenced yearly Objects and verify their checksum, record count and year.
            validateManifest(next, source.dataSourceId());
            for (var entry : years.entrySet()) verifyYearlyObject(entry.getKey(), entry.getValue(), parser);

            // Abort if any source input was replaced while this publication was being prepared.
            for (DataSourceSettings.Input input : source.inputs()) {
                if (!inputMetadata.get(input.objectName()).equals(sourceMetadata(source, input))) {
                    throw new IOException("Source changed while preprocessing: " + input.objectName());
                }
            }

            // An identical retry keeps the existing manifest, including its revision and publication time.
            if (previous != null && previous.activeDatasetId().equals(next.activeDatasetId())
                    && previous.datasets().equals(next.datasets()) && previous.years().equals(next.years())) {
                logger.info("No preprocessing change for {}", source.dataSourceId());
                return previous;
            }

            // Publish the manifest last, after every referenced Object has passed verification.
            storage.putResource(manifestName, "application/json", new OOSResourceMetadata(),
                    new ByteArrayInputStream(mapper.writeValueAsBytes(next)));
            logger.info("Published {}: {} years, revision {}", manifestName, years.size(), revision);
            return next;
        } finally {
            // Remove staged inputs on both successful publication and failure.
            for (Path file : files.values()) Files.deleteIfExists(file);
        }
    }

    private Map<String, String> sourceMetadata(DataSourceSettings source, DataSourceSettings.Input input) throws IOException {
        OOSResourceMetadata metadata = storage.getResourceMetadata(input.objectName());
        if (metadata == null) throw new IOException("Missing input: " + input.objectName());
        Map<String, String> values = metadata.getMetadata();
        if (!source.dataSourceId().equals(values.get("datasource-id"))
                || !source.datasetId().equals(values.get("dataset-id")) || !input.url().equals(values.get("source-url"))
                || values.get("downloaded-at") == null) {
            throw new IOException("Input does not match configured source: " + input.objectName());
        }
        return Map.copyOf(values);
    }

    private List<String> headers(Path file, PreprocessingSource source) throws IOException {
        try (CSVReader csv = new CSVReaderBuilder(reader(file))
                .withCSVParser(new CSVParserBuilder().withSeparator(source.separator).build()).build()) {
            String[] header = csv.readNext();
            if (header == null) throw new IOException("Missing CSV header");
            List<String> result = List.of(header);
            Set<String> keys = headerKeys(result, source);
            if (!keys.containsAll(headerKeys(source.requiredHeaders, source))) throw new IOException("Missing required CSV headers");
            return result;
        } catch (CsvValidationException invalid) {
            throw new IOException("Invalid CSV header", invalid);
        }
    }

    private Set<String> headerKeys(List<String> headers, PreprocessingSource source) throws IOException {
        Set<String> keys = new HashSet<>();
        for (String header : headers) {
            String key = source.headerKey(header);
            if (key.isBlank() || !keys.add(key)) throw new IOException("Blank or duplicate CSV header: " + header);
        }
        return keys;
    }

    private Reader reader(Path file) throws IOException {
        PushbackReader reader = new PushbackReader(Files.newBufferedReader(file, StandardCharsets.UTF_8), 1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') reader.unread(first);
        return reader;
    }

    private void attachGeometry(List<? extends Comptage> counts, DataSourceSettings source, Map<String, Path> files) throws IOException {
        DataSourceSettings.Input geometry = source.inputs().stream()
                .filter(input -> !input.contentType().equals("text/csv")).findFirst().orElseThrow();
        FeatureCollection collection;
        try (Reader reader = reader(files.get(geometry.objectName()))) {
            collection = mapper.readValue(reader, FeatureCollection.class);
        }
        Map<String, GeoJsonObject> locations = new HashMap<>();
        for (var feature : collection.getFeatures()) {
            Object id = feature.getProperty("code_centrale");
            if (id == null) id = feature.getProperty("gid");
            if (id == null || feature.getGeometry() == null || locations.putIfAbsent(id.toString(), feature.getGeometry()) != null) {
                throw new IOException("Missing or duplicate MMM geometry identifier");
            }
        }
        for (Comptage count : counts) {
            GeoJsonObject location = locations.get(count.getCodeCentrale());
            if (location == null) throw new IOException("Missing MMM geometry for " + count.getCodeCentrale());
            ((M3MComptage) count).setGeometry(location);
        }
    }

    private void validateManifest(DataManifest manifest, String dataSourceId) throws IOException {
        if (!dataSourceId.equals(manifest.dataSourceId()) || manifest.revision() == null || manifest.revision().isBlank()
                || manifest.publishedAt() == null || !manifest.publishedAt().endsWith("Z")
                || !manifest.datasets().containsKey(manifest.activeDatasetId()) || manifest.years().isEmpty()) {
            throw new IOException("Invalid manifest identity");
        }
        try { Instant.parse(manifest.publishedAt()); }
        catch (RuntimeException invalid) { throw new IOException("Invalid manifest timestamp", invalid); }
        for (DataManifest.Dataset dataset : manifest.datasets().values()) {
            if (dataset.years().isEmpty() || dataset.inputs().isEmpty()
                    || !manifest.years().keySet().containsAll(dataset.years())) {
                throw new IOException("Incomplete manifest dataset scope");
            }
        }
        for (var entry : manifest.years().entrySet()) {
            DataManifest.YearData year = entry.getValue();
            DataManifest.Dataset dataset = manifest.datasets().get(year.datasetId());
            if (dataset == null || !dataset.years().contains(entry.getKey()) || year.recordCount() <= 0
                    || year.sha256() == null || !year.sha256().matches("[0-9a-f]{64}")
                    || year.object() == null || !year.object().matches("data/[A-Za-z0-9_-]+/" + dataSourceId + "_Comptage_" + entry.getKey() + "\\.json")
                    || year.parserVersion() == null || year.parserVersion().isBlank()) {
                throw new IOException("Invalid manifest year: " + entry.getKey());
            }
        }
    }

    /**
     * Reads a stored yearly Object as the datasource's Comptage class and checks its record count,
     * each record's year and the SHA-256 checksum of the exact decompressed bytes against the manifest.
     *
     * @throws IOException if reading, deserialization or any consistency check fails, preventing publication
     */
    private void verifyYearlyObject(int year, DataManifest.YearData data, PreprocessingSource source) throws IOException {
        MessageDigest digest = digest();
        try (InputStream raw = storage.getResource(data.object()); DigestInputStream input = new DigestInputStream(raw, digest)) {
            List<? extends Comptage> counts = countsJson.read(input, source.parser.getDsComptageClass());
            input.transferTo(java.io.OutputStream.nullOutputStream());
            if (counts.size() != data.recordCount() || counts.stream().anyMatch(count -> count.getAnnee() != year)
                    || !HexFormat.of().formatHex(digest.digest()).equals(data.sha256())) {
                throw new IOException("Yearly Object verification failed: " + data.object());
            }
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
