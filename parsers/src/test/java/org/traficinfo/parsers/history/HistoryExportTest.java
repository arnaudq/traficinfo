package org.traficinfo.parsers.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.traficinfo.parsers.m3m.M3MComptageParser;
import org.traficinfo.parsers.storage.DataManifest;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

public class HistoryExportTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    @Test
    public void mmm4584ArchiveDuplicatesMarkedDisregardedDoNotConflictWithPermanentCounts() throws Exception {
        List<M3MComptage> records;
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/history/MMM_4584_2013.csv"),
                StandardCharsets.UTF_8)) {
            records = new M3MComptageParser().parseComptages(reader);
        }
        assertEquals(6, records.size());
        var history = new HistoryExport().build(input(records), NOW);
        assertEquals(1, history.counters().size());
        var campaign = history.counters().get("4584").campaigns().get(0);
        assertEquals(2013, campaign.year());
        assertEquals(CounterHistory.CountingMode.PERMANENT, campaign.countingMode());
        assertEquals(3, campaign.counts().size());
        assertTrue(campaign.counts().stream().allMatch(count -> count.population() == Comptage.Type.VEHICULES));
        assertEquals(List.of(13068, 14433, 27501), campaign.counts().stream()
                .map(CounterHistory.Count::dailyAverage).sorted().toList());
    }

    @Test
    public void disregardedRecordsCannotCreateCountersOrSupplySiteMetadata() throws Exception {
        var valid = count(2013, "0", 100, 30);
        valid.setDisplayName("Valid site");
        var disregarded = count(2025, "0", 200, 40);
        disregarded.setTypeCompteur("ne pas tenir compte");
        disregarded.setDisplayName("Disregarded name");
        var history = new HistoryExport().build(input(List.of(valid, disregarded)), NOW);
        assertEquals("Valid site", history.counters().get("001").displayName());
        assertEquals(1, history.counters().get("001").campaigns().size());
        assertTrue(new HistoryExport().build(input(List.of(disregarded)), NOW).counters().isEmpty());
        var unknown = count(2025, "0", 100, 30);
        unknown.setTypeCompteur("unrecognized");
        assertEquals(CounterHistory.CountingMode.UNKNOWN, new HistoryExport().build(input(List.of(unknown)), NOW)
                .counters().get("001").campaigns().get(0).countingMode());
    }

    @Test
    public void buildsCompleteDeterministicHistoryWithoutStorageOrMutatingMeasurements() throws Exception {
        var older = count(2001, "0", 1, 30);
        older.setCityName("Historical municipality");
        var a = count(2025, "0", 1300, 40);
        a.setDisplayName("Current name");
        var b = count(2025, "1", 1100, 50);
        var total = count(2025, "Z", 2400, 0);
        var records = new ArrayList<Comptage>(List.of(older, total, a, b));
        var input = input(records);
        var history = new HistoryExport().build(input, NOW);
        var counter = history.counters().get("001");
        assertEquals("Historical municipality", counter.cityName());
        assertEquals("Current name", counter.displayName());
        assertEquals(List.of(2001, 2025), counter.campaigns().stream().map(CounterHistory.Campaign::year).toList());
        assertEquals(3, counter.campaigns().get(1).counts().size());
        assertEquals(CounterHistory.CountingMode.PERMANENT, counter.campaigns().get(1).countingMode());
        assertEquals(40, a.getV85Speed());
        assertEquals(0, total.getV85Speed());
        Collections.reverse(records);
        var json = new ObjectMapper();
        assertEquals(json.writeValueAsString(history), json.writeValueAsString(new HistoryExport().build(input(records), NOW)));
    }

    @Test
    public void duplicateCountsCollapseButConflictingMeasurementsAndModesFail() throws Exception {
        var a = count(2025, "0", 100, 30);
        var duplicate = new HistoryExport().build(input(List.of(a, a)), NOW);
        assertEquals(1, duplicate.counters().get("001").campaigns().get(0).counts().size());
        var conflict = count(2025, "0", 200, 30);
        assertThrows(IOException.class, () -> new HistoryExport().build(input(List.of(a, conflict)), NOW));
        var temporary = count(2025, "1", 100, 30);
        temporary.setTypeCompteur("compteur ponctuel");
        var failure = assertThrows(IOException.class,
                () -> new HistoryExport().build(input(List.of(a, temporary)), NOW));
        assertTrue(failure.getMessage(), failure.getMessage().contains("codeCentrale=001,"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("PERMANENT"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("TEMPORARY"));
    }

    private static M3MComptage count(int year, String channel, int average, int v85) {
        var count = new M3MComptage();
        count.setCodeCentrale("001");
        count.setAnnee(year);
        count.setTypeCompteur("compteur permanent");
        count.setCodeCanal(channel);
        count.setMoyenneJourAnnuelle(average);
        count.setV85Speed(v85);
        return count;
    }

    private static HistoryInput input(List<? extends Comptage> counts) {
        var years = new TreeMap<Integer, DataManifest.YearData>();
        for (var count : counts) {
            int year = count.getAnnee();
            years.put(year, new DataManifest.YearData("data/r1/MMM_Comptage_" + year + ".json", "0".repeat(64),
                    counts.stream().filter(candidate -> candidate.getAnnee() == year).count(), "dataset", "1"));
        }
        var dataset = new DataManifest.Dataset("https://example.org/dataset", List.copyOf(years.keySet()),
                Map.of("counts.csv", new DataManifest.Input("https://example.org/counts.csv", "CSV", List.of("year"))));
        return new HistoryInput(new DataManifest(1, "MMM", "r1", NOW.toString(), "dataset",
                Map.of("dataset", dataset), years), counts);
    }
}
