package org.traficinfo.dsfunctions;

import org.geojson.FeatureCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.Comptage;
import java.util.List;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.time.Instant;

import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Manifest-to-GeoJSON publication with cached OSM enrichment. */
public final class GeoJsonPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(GeoJsonPublisher.class);
    private final GeoJsonDataSource source;
    private final ObjectStoreClient inputClient;
    private final ObjectStoreClient outputClient;
    private final OSMInfoProviderImpl osmInfoProvider;
    private final MetricsPublisher metrics;

    public GeoJsonPublisher(GeoJsonDataSource source, ObjectStoreClient inputClient,
                            ObjectStoreClient outputClient, OSMInfoProviderImpl osmInfoProvider) {
        this(source, inputClient, outputClient, osmInfoProvider, samples -> {});
    }

    public GeoJsonPublisher(GeoJsonDataSource source, ObjectStoreClient inputClient,
                            ObjectStoreClient outputClient, OSMInfoProviderImpl osmInfoProvider,
                            MetricsPublisher metrics) {
        this.source = Objects.requireNonNull(source);
        this.inputClient = Objects.requireNonNull(inputClient);
        this.outputClient = Objects.requireNonNull(outputClient);
        this.osmInfoProvider = Objects.requireNonNull(osmInfoProvider);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public String publish() {
        try (var logging = LoggingContext.forSource("geojson", source.getDataSourceId())) {
            try {
                var comptages = new ManifestComptageReader(inputClient).read(source.getDataSourceId());
                osmInfoProvider.loadCache();
                Map<Comptage.Type, Integer> featureCounts = new EnumMap<>(Comptage.Type.class);
                for (Comptage.Type type : List.of(Comptage.Type.VEHICULES, Comptage.Type.BICYCLES)) {
                    Path mergedFile = Files.createTempFile(source.getDataSourceId() + "-geojson-", ".geojson");
                    try {
                        try (Writer geojsonMergedWriter = Files.newBufferedWriter(mergedFile, StandardCharsets.UTF_8)) {
                            featureCounts.put(type, GeoJsonParser.writeGeoJsonWithCount(source.getParserDataSource(), comptages,
                                    new FeatureCollection(), geojsonMergedWriter, osmInfoProvider, type));
                        }
                        try (InputStream mergedInput = Files.newInputStream(mergedFile)) {
                            outputClient.putResource(source.getGeoJsonObjectName(type), "application/geo+json",
                                    new OOSResourceMetadata(), mergedInput);
                        }
                    } finally {
                        Files.deleteIfExists(mergedFile);
                    }
                }
                osmInfoProvider.refreshCache();
                publishMetrics(featureCounts);
                return "OK";
            } catch (Exception failure) {
                LOG.warn("Error while generating {} GeoJSON: {}", source.getDataSourceId(),
                        failure.getMessage(), failure);
                throw new IllegalStateException("GeoJSON generation failed for " + source.getDataSourceId(), failure);
            }
        }
    }

    private void publishMetrics(Map<Comptage.Type, Integer> featureCounts) {
        Instant timestamp = Instant.now();
        List<MetricSample> samples = new ArrayList<>();
        featureCounts.forEach((population, count) -> samples.add(new MetricSample(
                "GeoJsonFeatures", count, "count", timestamp,
                Map.of("datasourceId", source.getDataSourceId(), "population", population.name()))));
        try {
            metrics.publish(List.copyOf(samples));
        } catch (RuntimeException failure) {
            LOG.warn("Could not publish GeoJSON metrics for {}", source.getDataSourceId(), failure);
        }
    }
}
