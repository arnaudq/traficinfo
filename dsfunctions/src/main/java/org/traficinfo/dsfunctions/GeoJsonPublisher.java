/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

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
import java.util.TreeSet;
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
                OSMRequestCollector osmRequests = new OSMRequestCollector(osmInfoProvider);
                Map<Comptage.Type, GeoJsonParser.RenderingStatistics> statistics = new EnumMap<>(Comptage.Type.class);
                for (Comptage.Type type : List.of(Comptage.Type.VEHICULES, Comptage.Type.BICYCLES)) {
                    Path mergedFile = Files.createTempFile(source.getDataSourceId() + "-geojson-", ".geojson");
                    try {
                        try (Writer geojsonMergedWriter = Files.newBufferedWriter(mergedFile, StandardCharsets.UTF_8)) {
                            statistics.put(type, GeoJsonParser.writeGeoJsonWithStatistics(source.getParserDataSource(), comptages,
                                    new FeatureCollection(), geojsonMergedWriter, osmRequests, type));
                        }
                        try (InputStream mergedInput = Files.newInputStream(mergedFile)) {
                            outputClient.putResource(source.getGeoJsonObjectName(type), "application/geo+json",
                                    new OOSResourceMetadata(), mergedInput);
                        }
                    } finally {
                        Files.deleteIfExists(mergedFile);
                    }
                }
                osmRequests.publish(source.getDataSourceId());
                publishMetrics(statistics);
                return "OK";
            } catch (Exception failure) {
                LOG.warn("Error while generating {} GeoJSON: {}", source.getDataSourceId(),
                        failure.getMessage(), failure);
                throw new IllegalStateException("GeoJSON generation failed for " + source.getDataSourceId(), failure);
            }
        }
    }

    private void publishMetrics(Map<Comptage.Type, GeoJsonParser.RenderingStatistics> statistics) {
        Instant timestamp = Instant.now();
        List<MetricSample> samples = new ArrayList<>();
        var years = new TreeSet<Integer>();
        statistics.values().forEach(result -> years.addAll(result.recordsBeforeFiltering().keySet()));
        statistics.forEach((population, result) -> {
            var dimensions = Map.of("datasourceId", source.getDataSourceId(), "population", population.name());
            samples.add(new MetricSample("GeoJsonFeatures", result.featureCount(), "count", timestamp, dimensions));
            for (int year : years) {
                var yearlyDimensions = Map.of("datasourceId", source.getDataSourceId(),
                        "population", population.name(), "year", Integer.toString(year));
                samples.add(new MetricSample("GeoJsonRecordsBeforeFiltering",
                        result.recordsBeforeFiltering().getOrDefault(year, 0L), "count", timestamp, yearlyDimensions));
                samples.add(new MetricSample("GeoJsonRecordsAfterFiltering",
                        result.recordsAfterFiltering().getOrDefault(year, 0L), "count", timestamp, yearlyDimensions));
            }
        });
        try {
            metrics.publish(List.copyOf(samples));
        } catch (RuntimeException failure) {
            LOG.warn("Could not publish GeoJSON metrics for {}", source.getDataSourceId(), failure);
        }
    }
}
