package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.geojson.Point;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.dpt30.DPT30Comptage;
import org.traficinfo.parsers.dpt34.DPT34Comptage;
import org.traficinfo.parsers.dpt76.DPT76Comptage;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.traficinfo.parsers.mrn.MRNComptage;
import org.traficinfo.parsers.nim.NIMComptage;
import org.traficinfo.parsers.ren.RENComptage;
import org.traficinfo.parsers.history.HistoryExport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class HistoryPublisherTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    @Test
    public void exportsAllYearsAndPopulationsWithMetadataAndDirectionalCampaigns() throws Exception {
        M3MComptage old = mmm(2001, "0", 30, Comptage.Type.UNKNOWN);
        old.setCityName("Historical city");
        old.setTypeCompteur("compteur ponctuel");
        old.setGeometry(new Point(3.8, 43.6));
        var a = mmm(2025, "0", 1300, Comptage.Type.VEHICULES);
        var b = mmm(2025, "1", 1100, Comptage.Type.VEHICULES);
        var combined = mmm(2025, "Z", 2400, Comptage.Type.VEHICULES);
        var bicycles = mmm(2025, "0", 100, Comptage.Type.BICYCLES);
        a.setDisplayName("Current name");
        a.setOsmWayId(9007199254740993L);
        a.setV85Speed(43);
        b.setV85Speed(55);
        a.setMoyenneHeureDePointeMatin("313 (7h15 - 8h15)");
        var fixture = new Fixture(GeoJsonDataSource.MMM, List.of(old, combined, b, a, bicycles));
        JsonNode history = fixture.publish();
        assertEquals(1, history.path("schemaVersion").asInt());
        assertEquals("MMM", history.path("dataSourceId").asText());
        assertEquals("publication", history.path("sourceRevision").asText());
        assertEquals(CLOCK.instant().toString(), history.path("publishedAt").asText());
        assertTrue(history.path("datasets").path("dataset-2001").path("url").asText().startsWith("https://"));
        JsonNode counter = history.path("counters").path("00123");
        assertEquals("00123", counter.path("codeCentrale").asText());
        assertEquals("Current name", counter.path("displayName").asText());
        assertEquals("Historical city", counter.path("cityName").asText());
        assertEquals("Point", counter.path("geometry").path("type").asText());
        assertEquals("9007199254740993", counter.path("osmWayId").asText());
        assertFalse(counter.has("dailyAverage"));
        JsonNode campaigns = counter.path("campaigns");
        assertEquals(2, campaigns.size());
        assertEquals(2001, campaigns.get(0).path("year").asInt());
        assertEquals("TEMPORARY", campaigns.get(0).path("countingMode").asText());
        assertEquals("PERMANENT", campaigns.get(1).path("countingMode").asText());
        assertEquals("UNKNOWN", campaigns.get(0).path("counts").get(0).path("population").asText());
        assertEquals(4, campaigns.get(1).path("counts").size());
        JsonNode total = findCount(campaigns.get(1), "BIDIRECTIONAL", "VEHICULES", null);
        assertEquals(2400, total.path("dailyAverage").asInt());
        assertTrue(total.path("direction").isNull());
        assertTrue(total.path("v85Speed").isNull());
        JsonNode first = findCount(campaigns.get(1), "UNIDIRECTIONAL", "VEHICULES", "0");
        assertEquals(43, first.path("v85Speed").asInt());
        assertEquals(313, first.path("morningPeakAverage").asInt());
        assertEquals("313 (7h15 - 8h15)", first.path("morningPeakText").asText());
        assertEquals("ANNUAL", first.path("dailyAverageBasis").asText());
        assertEquals(43, a.getV85Speed());
        assertEquals(0, combined.getV85Speed());
        assertEquals(List.of("MMM_Manifest.json", ManifestTestPublication.yearObjectName(GeoJsonDataSource.MMM, 2001),
                ManifestTestPublication.yearObjectName(GeoJsonDataSource.MMM, 2025)), fixture.reads);
        assertEquals(1, fixture.writes);
    }

    @Test
    public void sameYearCampaignsAreDistinctAndOrderIsIndependentOfInputRows() throws Exception {
        var first = mrn("Spring campaign", 100);
        var second = mrn("Autumn campaign", 200);
        var records = new ArrayList<Comptage>(List.of(first, second));
        var fixture = new Fixture(GeoJsonDataSource.MRN, records);
        var history = fixture.publish();
        JsonNode campaigns = history.path("counters").path("MRN_1").path("campaigns");
        assertEquals(2, campaigns.size());
        assertNotEquals(campaigns.get(0).path("id"), campaigns.get(1).path("id"));
        Collections.reverse(records);
        var reversed = new Fixture(GeoJsonDataSource.MRN, records);
        assertEquals(history, reversed.publish());
    }

    @Test
    public void canonicalizesNullableMeasurementsAndRoundsDecimalsToIntegers() throws Exception {
        var dpt30 = new DPT30Comptage();
        var dpt34 = new DPT34Comptage();
        dpt34.setSensRoute(1);
        for (var entry : Map.of(GeoJsonDataSource.DPT30, dpt30, GeoJsonDataSource.DPT34, dpt34).entrySet()) {
            var count = entry.getValue();
            count.setCodeCentrale("site");
            count.setAnnee(2024);
            JsonNode missing = onlyCount(new Fixture(entry.getKey(), List.of(count)).publish(), "site");
            assertTrue(missing.path("dailyAverage").isNull());
            count.setMoyenneJourAnnuelle(0);
            JsonNode zero = onlyCount(new Fixture(entry.getKey(), List.of(count)).publish(), "site");
            assertTrue(zero.path("dailyAverage").isNumber());
            assertEquals(0, zero.path("dailyAverage").intValue());
        }
        var ren = new RENComptage();
        ren.setCodeCentrale("REN_001");
        ren.setAnnee(2025);
        ren.setSens("Nord-Sud / Ouest-Est");
        ren.setStationCode("001");
        ren.setDisplayName("001 - Nord-Sud / Ouest-Est");
        ren.setTmjaTv(1234.75);
        ren.setV85Tv(0.0);
        ren.setVMoyenneTv(29.25);
        ren.setDebitHpmTv(123.5);
        ren.setDebitHpsTv(0.0);
        ren.setNbSemaines(2.5);
        JsonNode renHistory = new Fixture(GeoJsonDataSource.REN, List.of(ren)).publish();
        assertEquals("001", renHistory.path("counters").path("REN_001").path("displayName").asText());
        JsonNode normalized = onlyCount(renHistory, "REN_001");
        assertEquals(1235, normalized.path("dailyAverage").asInt());
        assertEquals(29, normalized.path("meanSpeed").asInt());
        assertEquals(0, normalized.path("v85Speed").asInt());
        assertEquals(124, normalized.path("morningPeakAverage").asInt());
        assertEquals("123.5", normalized.path("morningPeakText").asText());
        assertEquals(0, normalized.path("eveningPeakAverage").asInt());
        assertEquals("0", normalized.path("eveningPeakText").asText());
        assertFalse(normalized.has("sourceDetails"));
    }

    @Test
    public void preservesNimCoverageAndNullAverageWithoutInventingAFullYear() throws Exception {
        var count = new NIMComptage();
        count.setCodeCentrale("NIM_43_S02");
        count.setAnnee(2024);
        count.setFirstDate("2024-03-01");
        count.setLastDate("2024-03-02");
        count.setObservedDays(2);
        count.setObservedHours(23);
        count.setConflictingHours(1);
        count.setTotalPassages(120);
        var history = new Fixture(GeoJsonDataSource.NIM, List.of(count)).publish();
        var campaign = history.path("counters").path("NIM_43_S02").path("campaigns").get(0);
        assertEquals("2024-03-01", campaign.path("startDate").asText());
        JsonNode normalized = campaign.path("counts").get(0);
        assertTrue(normalized.path("dailyAverage").isNull());
        assertEquals("OBSERVED_COMPLETE_DAYS", normalized.path("dailyAverageBasis").asText());
        assertEquals(0, normalized.path("coverage").path("completeDays").asInt());
        assertEquals(1, normalized.path("coverage").path("conflictingHours").asInt());
        assertFalse(normalized.has("sourceDetails"));
        assertEquals("PERMANENT", campaign.path("countingMode").asText());
        count.setDailyAverage(0);
        var zero = onlyCount(new Fixture(GeoJsonDataSource.NIM, List.of(count)).publish(),
                "NIM_43_S02").path("dailyAverage");
        assertTrue(zero.isNumber());
        assertEquals(0, zero.intValue());
    }

    @Test
    public void exportsDpt76PublishedDirectionsAndCombinedCountWithoutRecomputation() throws Exception {
        var count = new DPT76Comptage();
        count.setCodeCentrale("DPT76_1");
        count.setAnnee(2022);
        count.setMjaTvTcj(999);
        count.setTmjaSens1(0);
        count.setTmjaSens2(450);
        var history = new Fixture(GeoJsonDataSource.DPT76, List.of(count)).publish();
        var campaign = history.path("counters").path("DPT76_1").path("campaigns").get(0);
        assertEquals(3, campaign.path("counts").size());
        assertEquals(999, findCount(campaign, "BIDIRECTIONAL", "VEHICULES", null).path("dailyAverage").asInt());
        assertEquals(0, findCount(campaign, "UNIDIRECTIONAL", "VEHICULES", "1").path("dailyAverage").intValue());
        assertEquals(450, findCount(campaign, "UNIDIRECTIONAL", "VEHICULES", "2").path("dailyAverage").asInt());
    }

    @Test
    public void invalidPublicationAndAmbiguousCountsNeverReplacePreviousExport() throws Exception {
        var a = mmm(2025, "0", 100, Comptage.Type.VEHICULES);
        var b = mmm(2025, "0", 200, Comptage.Type.VEHICULES);
        Fixture fixture = new Fixture(GeoJsonDataSource.MMM, List.of(a, b));
        assertFailsWithoutUpload(fixture, "Ambiguous history");
        fixture = new Fixture(GeoJsonDataSource.MMM, List.of(a));
        fixture.objects.put(ManifestTestPublication.yearObjectName(GeoJsonDataSource.MMM, 2025), "[]".getBytes());
        assertFailsWithoutUpload(fixture, "verification failed");
        fixture = new Fixture(GeoJsonDataSource.MMM, List.of(a));
        fixture.objects.remove("MMM_Manifest.json");
        assertFailsWithoutUpload(fixture, "Missing");
    }

    @Test
    public void emptyPublicationAndIdenticalDuplicatesAreSupported() throws Exception {
        assertTrue(new Fixture(GeoJsonDataSource.NIM, List.of()).publish().path("counters").isEmpty());
        var count = mmm(2025, "Z", 10, Comptage.Type.VEHICULES);
        assertEquals(1, new Fixture(GeoJsonDataSource.MMM, List.of(count, count)).publish()
                .path("counters").path("00123").path("campaigns").get(0).path("counts").size());
    }

    @Test
    public void conflictingModesWithinOneCampaignNeverReplacePreviousExport() throws Exception {
        var permanent = mmm(2025, "0", 100, Comptage.Type.VEHICULES);
        var temporary = mmm(2025, "1", 100, Comptage.Type.VEHICULES);
        temporary.setTypeCompteur("compteur ponctuel");
        var fixture = new Fixture(GeoJsonDataSource.MMM, List.of(permanent, temporary));
        var logger = (Logger) LoggerFactory.getLogger(HistoryExport.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.setContext(logger.getLoggerContext());
        captured.start();
        logger.addAppender(captured);
        try {
            byte[] previous = fixture.output;
            var failure = assertThrows(IOException.class, fixture::publish);
            String message = failure.getMessage();
            assertTrue(message, message.contains("codeCentrale=00123,"));
            assertTrue(message, message.contains("PERMANENT"));
            assertTrue(message, message.contains("TEMPORARY"));
            assertEquals(1, captured.list.size());
            assertEquals(message, captured.list.get(0).getFormattedMessage());
            assertEquals(0, fixture.writes);
            assertSame(previous, fixture.output);
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    public void publishesOnlyUsableMmmRecordsAndCanPublishAnEmptyHistory() throws Exception {
        var permanent = mmm(2013, "0", 13068, Comptage.Type.VEHICULES);
        var disregarded = mmm(2013, "0", 13068, Comptage.Type.UNKNOWN);
        disregarded.setTypeCompteur("ne pas tenir compte");
        var history = new Fixture(GeoJsonDataSource.MMM, List.of(permanent, disregarded)).publish();
        var campaign = history.path("counters").path("00123").path("campaigns").get(0);
        assertEquals("PERMANENT", campaign.path("countingMode").asText());
        assertEquals(1, campaign.path("counts").size());
        assertEquals("VEHICULES", campaign.path("counts").get(0).path("population").asText());
        var empty = new Fixture(GeoJsonDataSource.MMM, List.of(disregarded));
        assertTrue(empty.publish().path("counters").isEmpty());
        assertEquals(1, empty.writes);
    }

    private static void assertFailsWithoutUpload(Fixture fixture, String message) throws Exception {
        byte[] previous = fixture.output;
        try { fixture.publish(); fail("Expected invalid publication"); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
        assertEquals(0, fixture.writes);
        assertSame(previous, fixture.output);
    }

    private static M3MComptage mmm(int year, String channel, int average, Comptage.Type type) {
        var count = new M3MComptage();
        count.setCodeCentrale("00123");
        count.setAnnee(year);
        count.setCodeCanal(channel);
        count.setType(type);
        count.setTypeCompteur("compteur permanent");
        count.setMoyenneJourAnnuelle(average);
        return count;
    }

    private static MRNComptage mrn(String label, int average) {
        var count = new MRNComptage();
        count.setCodeCentrale("MRN_1");
        count.setAnnee(2024);
        count.setType(Comptage.Type.VEHICULES);
        count.setCampaignType("Comptage ponctuel en section");
        count.setCampaignDetails(label);
        count.setDirectionCount("2 sens");
        count.setTvTmja(average);
        return count;
    }

    private static JsonNode onlyCount(JsonNode history, String id) {
        return history.path("counters").path(id).path("campaigns").get(0).path("counts").get(0);
    }

    private static JsonNode findCount(JsonNode campaign, String directionType, String population, String channel) {
        for (var count : campaign.path("counts")) {
            if (directionType.equals(count.path("directionType").asText())
                    && population.equals(count.path("population").asText())
                    && (channel == null || channel.equals(count.path("direction").path("id").asText()))) return count;
        }
        throw new AssertionError("Missing count " + directionType + "/" + population + "/" + channel);
    }

    static final class Fixture {
        final Map<String, byte[]> objects;
        final List<String> reads = new ArrayList<>();
        final String sourceId;
        byte[] output = "previous publication".getBytes();
        int writes;

        Fixture(GeoJsonDataSource source, List<? extends Comptage> counts) throws IOException {
            sourceId = source.getDataSourceId();
            objects = new HashMap<>(ManifestTestPublication.fromRecords(source, counts));
        }

        JsonNode publish() throws IOException {
            var input = new StubObjectStore() {
                @Override public InputStream getResource(String name) throws IOException {
                    reads.add(name);
                    byte[] bytes = objects.get(name);
                    if (bytes == null) throw new IOException("Missing " + name);
                    return new ByteArrayInputStream(bytes);
                }
            };
            var destination = new StubObjectStore() {
                @Override public void putResource(String name, String type, ObjectMetadata metadata,
                                                   InputStream content, String cacheControl) throws IOException {
                    assertEquals("history/" + sourceId + ".json", name);
                    assertEquals("application/json", type);
                    assertEquals("public, max-age=300", cacheControl);
                    output = content.readAllBytes();
                    writes++;
                }
            };
            new HistoryPublisher(input, destination, CLOCK).publish(sourceId);
            var history = JSON.readTree(output);
            assertTrue("Export contains only canonical fields", history.findValues("sourceDetails").isEmpty());
            for (String field : List.of("dailyAverage", "morningPeakAverage", "eveningPeakAverage", "v85Speed", "meanSpeed")) {
                for (JsonNode value : history.findValues(field)) {
                    assertTrue(field + " must be an integer or null", value.isNull() || value.isIntegralNumber());
                }
            }
            return history;
        }
    }
}
