package org.traficinfo.dsfunctions;

import org.geojson.FeatureCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.Comptage;
import java.util.List;

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

    public GeoJsonPublisher(GeoJsonDataSource source, ObjectStoreClient inputClient,
                            ObjectStoreClient outputClient, OSMInfoProviderImpl osmInfoProvider) {
        this.source = Objects.requireNonNull(source);
        this.inputClient = Objects.requireNonNull(inputClient);
        this.outputClient = Objects.requireNonNull(outputClient);
        this.osmInfoProvider = Objects.requireNonNull(osmInfoProvider);
    }

    public String publish() {
        try (var logging = LoggingContext.forSource("geojson", source.getDataSourceId())) {
            try {
                var comptages = new ManifestComptageReader(inputClient).read(source.getDataSourceId());
                osmInfoProvider.loadCache();
                for (Comptage.Type type : List.of(Comptage.Type.VEHICULES, Comptage.Type.BICYCLES)) {
                    Path mergedFile = Files.createTempFile(source.getDataSourceId() + "-geojson-", ".geojson");
                    try {
                        try (Writer geojsonMergedWriter = Files.newBufferedWriter(mergedFile, StandardCharsets.UTF_8)) {
                            GeoJsonParser.writeGeoJson(source.getParserDataSource(), comptages,
                                    new FeatureCollection(), geojsonMergedWriter, osmInfoProvider, type);
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
                return "OK";
            } catch (Exception failure) {
                LOG.warn("Error while generating {} GeoJSON: {}", source.getDataSourceId(),
                        failure.getMessage(), failure);
                throw new IllegalStateException("GeoJSON generation failed for " + source.getDataSourceId(), failure);
            }
        }
    }
}
