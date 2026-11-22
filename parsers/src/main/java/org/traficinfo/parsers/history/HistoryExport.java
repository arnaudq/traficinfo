package org.traficinfo.parsers.history;

import org.geojson.GeoJsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.storage.ComptageJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.traficinfo.parsers.history.CounterHistory.*;
import static org.traficinfo.parsers.history.CanonicalValues.text;

/**
 * Assembles the {@link CounterHistory} containment hierarchy from a validated private publication.
 *
 * <p>Uses {@link Canonicalizer} to normalize records, groups them by counter and campaign,
 * selects the latest available site metadata, and produces deterministic IDs and ordering.
 * Identical canonical counts collapse; conflicting counts with the same identity fail the build.
 * Publisher-disregarded records are excluded before grouping and metadata selection.
 * No map filtering, OSM enrichment, record mutation or storage writes occur here.
 * The caller handles serialization and publication of the completed result.
 */
public final class HistoryExport {
    private static final Logger LOG = LoggerFactory.getLogger(HistoryExport.class);

    /** One normalized input plus provenance, campaign identity and a stable metadata ordering key. */
    private record Row(Comptage record, Canonicalizer.Observation observation, String displayName,
                       String datasetId, String campaignId, String tieBreaker) {}

    /**
     * Builds all retained counter histories from one manifest snapshot.
     *
     * @param publication manifest and validated records read together
     * @param publishedAt timestamp to place in the public export
     * @return datasource history containing shared attribution and complete counter campaigns
     * @throws IOException if a counter lacks identity, counts conflict, or ordering-key serialization fails
     */
    public CounterHistory build(HistoryInput publication, Instant publishedAt) throws IOException {
        var manifest = publication.manifest();
        var canonicalizer = GeoJsonParser.DataSource.fromId(manifest.dataSourceId()).getCanonicalizer();
        var rows = new ArrayList<Row>();
        var storedJson = new ComptageJson();
        for (var count : publication.counts()) {
            var observation = canonicalizer.canonicalize(count);
            if (observation.countingMode() == CountingMode.DISREGARDED) continue;
            if (count.getCodeCentrale() == null || count.getCodeCentrale().isBlank()) {
                throw new IOException("History record has no codeCentrale");
            }
            String dataset = manifest.years().get(count.getAnnee()).datasetId();
            String id = count.getAnnee() + "-" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(dataset.getBytes(StandardCharsets.UTF_8));
            if (!observation.discriminator().isEmpty()) id += "-" + sha256(observation.discriminator());
            rows.add(new Row(count, observation, canonicalizer.displayName(count), dataset, id,
                    new String(storedJson.write(count), StandardCharsets.UTF_8)));
        }
        // Metadata is selected field by field from the latest known record. Ties are deterministic.
        rows.sort(Comparator.comparingInt((Row row) -> row.record().getAnnee())
                .thenComparing(row -> nullToEmpty(row.observation().endDate()))
                .thenComparing(row -> nullToEmpty(row.observation().startDate()))
                .thenComparing(Row::campaignId).thenComparing(Row::tieBreaker));
        var counters = new TreeMap<String, CounterBuilder>();
        for (var row : rows) {
            counters.computeIfAbsent(row.record().getCodeCentrale(), CounterBuilder::new).add(row);
        }
        var result = new TreeMap<String, Counter>();
        for (var entry : counters.entrySet()) result.put(entry.getKey(), entry.getValue().build());
        var datasets = new TreeMap<String, Dataset>();
        manifest.datasets().forEach((id, dataset) -> datasets.put(id, new Dataset(dataset.datasetUrl())));
        return new CounterHistory(1, manifest.dataSourceId(), manifest.revision(), publishedAt.toString(),
                datasets, result);
    }

    /**
     * Accumulates one site's latest available metadata and campaign groups in input sort order.
     * Produces a {@link Counter} after validating duplicate count identities within each campaign.
     */
    private static final class CounterBuilder {
        private final String id;
        private String displayName;
        private String cityName;
        private String roadReference;
        private GeoJsonObject geometry;
        private String osmWayId;
        private final Map<String, CampaignBuilder> campaigns = new TreeMap<>();

        CounterBuilder(String id) { this.id = id; }

        void add(Row row) throws IOException {
            var record = row.record();
            String name = row.displayName();
            if (name != null) displayName = name;
            if (text(record.getCityName()) != null) cityName = text(record.getCityName());
            if (text(record.getRoadReference()) != null) roadReference = text(record.getRoadReference());
            if (record.getGeometry() != null) geometry = record.getGeometry();
            if (record.getOsmWayId() != null && record.getOsmWayId() > 0) osmWayId = record.getOsmWayId().toString();
            var campaign = campaigns.computeIfAbsent(row.campaignId(), ignored -> new CampaignBuilder(row));
            if (campaign.row.observation().countingMode() != row.observation().countingMode()) {
                String message = "Conflicting counting modes for codeCentrale=" + id
                        + ", campaign=" + row.campaignId() + ": "
                        + campaign.row.observation().countingMode() + " vs " + row.observation().countingMode();
                LOG.warn(message);
                throw new IOException(message);
            }
            for (var count : row.observation().counts()) {
                Count previous = campaign.counts.putIfAbsent(count.id(), count);
                if (previous != null && !previous.equals(count)) {
                    throw new IOException("Ambiguous history campaign/count for " + id + "/"
                            + row.campaignId() + "/" + count.id());
                }
            }
        }

        Counter build() {
            var sorted = campaigns.values().stream().map(CampaignBuilder::build)
                    .sorted(Comparator.comparingInt(Campaign::year)
                            .thenComparing(campaign -> nullToEmpty(campaign.startDate()))
                            .thenComparing(campaign -> nullToEmpty(campaign.endDate()))
                            .thenComparing(Campaign::id)).toList();
            return new Counter(id, displayName, cityName, roadReference, geometry, osmWayId, sorted);
        }
    }

    /**
     * Accumulates counts keyed by their canonical IDs for one counter's campaign.
     * The first row supplies the shared campaign descriptors; count IDs determine output order.
     */
    private static final class CampaignBuilder {
        private final Row row;
        private final Map<String, Count> counts = new TreeMap<>();

        CampaignBuilder(Row row) { this.row = row; }

        Campaign build() {
            var observation = row.observation();
            return new Campaign(row.campaignId(), row.record().getAnnee(), observation.startDate(),
                    observation.endDate(), observation.label(), row.datasetId(), observation.countingMode(),
                    List.copyOf(counts.values()));
        }
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
