package org.traficinfo.parsers;

import org.junit.Test;
import org.geojson.Point;
import org.traficinfo.parsers.m3m.M3MComptage;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

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
    public void requiresExplicitOnewayAndBicycleExceptionForContraflow() {
        for (String oneway : Arrays.asList("yes", "-1", "no", "", "reversible", "1")) {
            for (String bicycle : Arrays.asList("no", "yes", "-1", "")) {
                boolean expected = (oneway.equals("yes") || oneway.equals("-1")) && bicycle.equals("no");
                assertEquals(oneway + "/" + bicycle, expected,
                        new OSMWayInfo(NOW, Map.of("oneway", oneway, "oneway:bicycle", bicycle))
                                .isBicycleContraflowAllowed());
            }
        }
        for (Map<String, String> tags : Arrays.asList(Map.<String, String>of(),
                Map.of("oneway", "yes"), Map.of("oneway:bicycle", "no"),
                Map.of("cycleway", "opposite_lane"))) {
            assertFalse(new OSMWayInfo(NOW, tags).isBicycleContraflowAllowed());
        }
    }

    @Test
    public void storesNullableContraflowOnEveryCounterType() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (GeoJsonParser.DataSource source : GeoJsonParser.DataSource.values()) {
            Comptage count = source.getDsComptageClass().getDeclaredConstructor().newInstance();
            assertNull(count.getBicycleContraflowAllowed());
            for (Boolean value : Arrays.asList(true, false, null)) {
                count.setBicycleContraflowAllowed(value);
                com.fasterxml.jackson.databind.node.ObjectNode json = mapper.createObjectNode();
                json.set("bicycleContraflowAllowed", mapper.valueToTree(count).get("bicycleContraflowAllowed"));
                Comptage decoded = mapper.readValue(json.toString(), source.getDsComptageClass());
                assertEquals(value, decoded.getBicycleContraflowAllowed());
            }
        }
    }

    @Test
    public void enrichesNullableContraflowAndClearsPreviouslyKnownValues() {
        M3MComptage allowed = count(1L);
        M3MComptage notAllowed = count(2L);
        M3MComptage missingCache = count(3L);
        M3MComptage missingId = count(null);
        missingCache.setBicycleContraflowAllowed(true);
        missingId.setBicycleContraflowAllowed(false);
        GeoJsonParser.applyOsmInfo(Arrays.asList(allowed, notAllowed, missingCache, missingId),
                Map.<Long, OSMWayInfo>of(
                        1L, new OSMWayInfo(NOW, Map.of("oneway", "yes", "oneway:bicycle", "no")),
                        2L, new OSMWayInfo(NOW, Map.of("oneway", "no")))::get);
        assertEquals(Boolean.TRUE, allowed.getBicycleContraflowAllowed());
        assertEquals(Boolean.FALSE, notAllowed.getBicycleContraflowAllowed());
        assertNull(missingCache.getBicycleContraflowAllowed());
        assertNull(missingId.getBicycleContraflowAllowed());
    }

    @Test
    public void enrichesCountsWithCachedSpeeds() {
        M3MComptage fresh = count(1L);
        M3MComptage stale = count(2L);
        M3MComptage missing = count(3L);
        M3MComptage duplicate = count(3L);
        M3MComptage noId = count(null);
        M3MComptage invalidId = count(0L);
        M3MComptage noSpeed = count(4L);
        GeoJsonParser.applyOsmInfo(Arrays.asList(fresh, stale, missing, duplicate, noId, invalidId, noSpeed),
                Map.<Long, OSMWayInfo>of(1L, new OSMWayInfo(NOW, Map.of("maxspeed", "50")),
                       2L, new OSMWayInfo(Instant.parse("2025-01-01T00:00:00Z"), Map.of("maxspeed", "30")),
                       4L, new OSMWayInfo(NOW, Map.of("highway", "residential")))::get);
        assertEquals(50, fresh.getMaximumSpeed());
        assertEquals(30, stale.getMaximumSpeed());
        assertEquals(-1, missing.getMaximumSpeed());
        assertEquals(-1, noId.getMaximumSpeed());
        assertEquals(-1, invalidId.getMaximumSpeed());
        assertEquals(-1, noSpeed.getMaximumSpeed());
    }

    private static M3MComptage count(Long id) {
        M3MComptage count = new M3MComptage();
        count.setOsmWayId(id);
        return count;
    }

    @Test
    public void usesCachedCounterMappingAndPreservesSourceWayId() {
        org.traficinfo.parsers.dpt34.DPT34Comptage missing = new org.traficinfo.parsers.dpt34.DPT34Comptage();
        missing.setCodeCentrale("DPT34_A");
        missing.setRouteId("34D0011");
        missing.setLatitude(43.0);
        missing.setLongitude(3.0);
        M3MComptage provided = count(77L);
        M3MComptage noCoordinates = count(null);
        noCoordinates.setCodeCentrale("MMM_A");
        java.util.List<String> seen = new java.util.ArrayList<>();
        OSMInfoProvider provider = new OSMInfoProvider() {
            @Override public OSMWayInfo getWayInfo(long id) {
                return new OSMWayInfo(NOW, Map.of("maxspeed", "80"));
            }
            @Override public Long getWayId(String id, Point point, String roadReference) {
                seen.add(id);
                if ("DPT34_A".equals(id)) {
                    assertEquals("D0011", roadReference);
                    assertNotNull(point);
                    assertEquals(43.0, point.getCoordinates().getLatitude(), 0);
                    assertEquals(3.0, point.getCoordinates().getLongitude(), 0);
                    return 1L;
                }
                assertNull(roadReference);
                assertNull(point);
                return null;
            }
        };
        GeoJsonParser.applyOsmInfo(Arrays.asList(missing, provided, noCoordinates), provider);
        assertEquals(Long.valueOf(1), missing.getOsmWayId());
        assertEquals(Long.valueOf(77), provided.getOsmWayId());
        assertEquals(80, missing.getMaximumSpeed());
        assertEquals(Arrays.asList("DPT34_A", "MMM_A"), seen);
    }
}
