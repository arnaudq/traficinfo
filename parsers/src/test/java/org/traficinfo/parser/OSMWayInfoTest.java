package org.traficinfo.parser;

import org.junit.Test;
import org.traficinfo.parser.m3m.M3MComptage;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class OSMWayInfoTest {
    private static final Instant NOW = Instant.parse("2026-05-31T12:00:00Z");

    @Test
    public void refreshesOnlyEntriesOlderThanThreeCalendarMonths() {
        OSMWayInfo boundary = new OSMWayInfo(Instant.parse("2026-02-28T12:00:00Z"), Map.of());
        assertFalse(boundary.needsRefresh(NOW));
        boundary.setLastUpdated("2026-02-28T11:59:59Z");
        assertTrue(boundary.needsRefresh(NOW));
        boundary.setLastUpdated(null);
        assertTrue(boundary.needsRefresh(NOW));
        boundary.setLastUpdated("invalid");
        assertTrue(boundary.needsRefresh(NOW));
    }

    @Test
    public void readsNumericSpeedAndKeepsUnknownValuesAtMinusOne() {
        for (String value : Arrays.asList("none", "walk", "FR:urban", "50 mph", "30;50", "-10", "99999999999")) {
            assertEquals(-1, new OSMWayInfo(NOW, Map.of("maxspeed", value)).getMaximumSpeed());
        }
        assertEquals(-1, new OSMWayInfo(NOW, Map.of()).getMaximumSpeed());
        assertEquals(50, new OSMWayInfo(NOW, Map.of("maxspeed", "50")).getMaximumSpeed());
        assertEquals(30, new OSMWayInfo(NOW, Map.of("maxspeed", "30 km/h")).getMaximumSpeed());
    }

    @Test
    public void enrichesCountsAndQueuesUniqueMissingOrStaleWays() {
        M3MComptage fresh = count(1L);
        M3MComptage stale = count(2L);
        M3MComptage missing = count(3L);
        M3MComptage duplicate = count(3L);
        M3MComptage noId = count(null);
        M3MComptage invalidId = count(0L);
        M3MComptage noSpeed = count(4L);
        Set<Long> pending = new LinkedHashSet<>();
        GeoJsonParser.applyOsmInfo(Arrays.asList(fresh, stale, missing, duplicate, noId, invalidId, noSpeed),
                Map.<Long, OSMWayInfo>of(1L, new OSMWayInfo(NOW, Map.of("maxspeed", "50")),
                       2L, new OSMWayInfo(Instant.parse("2025-01-01T00:00:00Z"), Map.of("maxspeed", "30")),
                       4L, new OSMWayInfo(NOW, Map.of("highway", "residential")))::get, pending, NOW);
        assertEquals(50, fresh.getMaximumSpeed());
        assertEquals(30, stale.getMaximumSpeed());
        assertEquals(-1, missing.getMaximumSpeed());
        assertEquals(-1, noId.getMaximumSpeed());
        assertEquals(-1, invalidId.getMaximumSpeed());
        assertEquals(-1, noSpeed.getMaximumSpeed());
        assertEquals(new LinkedHashSet<>(Arrays.asList(2L, 3L)), pending);
    }

    private static M3MComptage count(Long id) {
        M3MComptage count = new M3MComptage();
        count.setOsmWayId(id);
        return count;
    }
}
