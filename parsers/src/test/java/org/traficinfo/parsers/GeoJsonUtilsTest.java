package org.traficinfo.parsers;

import org.geojson.LineString;
import org.geojson.LngLatAlt;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class GeoJsonUtilsTest {
    @Test
    public void measuresHorizontalDistanceInMetres() {
        LngLatAlt origin = new LngLatAlt(0, 0);
        assertEquals(0, GeoJsonUtils.distanceMeters(origin, new LngLatAlt(0, 0, 100)), 0);
        assertEquals(111_194.927, GeoJsonUtils.distanceMeters(origin, new LngLatAlt(1, 0)), 0.001);
        assertEquals(111_194.927, GeoJsonUtils.distanceMeters(origin, new LngLatAlt(0, 1)), 0.001);
        LngLatAlt first = new LngLatAlt(4.55076, 43.91028);
        LngLatAlt second = new LngLatAlt(4.5507249331597, 43.9105807039654);
        double distance = GeoJsonUtils.distanceMeters(first, second);
        assertEquals(33.55, distance, 0.02);
        assertEquals(distance, GeoJsonUtils.distanceMeters(second, first), 1e-9);
    }

    @Test
    public void measuresAcrossAntimeridianAndBetweenAntipodes() {
        assertEquals(222_389.853, GeoJsonUtils.distanceMeters(
                new LngLatAlt(179, 0), new LngLatAlt(-179, 0)), 0.001);
        assertEquals(20_015_086.796, GeoJsonUtils.distanceMeters(
                new LngLatAlt(0, 0), new LngLatAlt(180, 0)), 0.001);
    }

    @Test
    public void followsUnequalBentSegmentsInsteadOfAveragingVerticesOrEndpoints() throws Exception {
        var point = GeoJsonUtils.midpoint(new LineString(
                new LngLatAlt(0, 0), new LngLatAlt(0.01, 0), new LngLatAlt(0.01, 0.03)));
        assertEquals(0.01, point.getCoordinates().getLongitude(), 1e-12);
        assertEquals(0.01, point.getCoordinates().getLatitude(), 1e-12);
    }

    @Test
    public void usesGeographicLengthsAtHighLatitude() throws Exception {
        // At 60 degrees latitude, two longitude degrees are approximately one latitude degree.
        var point = GeoJsonUtils.midpoint(new LineString(
                new LngLatAlt(0, 60), new LngLatAlt(2, 60), new LngLatAlt(2, 61)));
        assertEquals(2, point.getCoordinates().getLongitude(), 1e-12);
        assertEquals(60, point.getCoordinates().getLatitude(), 0.0001);
    }

    @Test
    public void handlesRepeatedPositionsAndMidpointAtVertex() throws Exception {
        var point = GeoJsonUtils.midpoint(new LineString(new LngLatAlt(0, 0),
                new LngLatAlt(0, 0), new LngLatAlt(1, 0), new LngLatAlt(1, 0), new LngLatAlt(2, 0)));
        assertEquals(1, point.getCoordinates().getLongitude(), 1e-12);
        assertEquals(0, point.getCoordinates().getLatitude(), 1e-12);
        assertEquals(new org.geojson.Point(3, 43), GeoJsonUtils.midpoint(
                new LineString(new LngLatAlt(3, 43), new LngLatAlt(3, 43))));
    }

    @Test
    public void interpolatesAltitudeWhenBothEndpointsHaveIt() throws Exception {
        var point = GeoJsonUtils.midpoint(new LineString(new LngLatAlt(0, 0, 10), new LngLatAlt(2, 0, 30)));
        assertEquals(1, point.getCoordinates().getLongitude(), 1e-12);
        assertEquals(20, point.getCoordinates().getAltitude(), 1e-12);
    }

    @Test
    public void rejectsInvalidLines() throws Exception {
        for (LineString line : new LineString[]{new LineString(), new LineString(new LngLatAlt(0, 0)),
                new LineString(new LngLatAlt(0, 0), new LngLatAlt(Double.NaN, 0))}) {
            try {
                GeoJsonUtils.midpoint(line);
                fail("Expected invalid LineString to be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("LineString"));
            }
        }
    }
}
