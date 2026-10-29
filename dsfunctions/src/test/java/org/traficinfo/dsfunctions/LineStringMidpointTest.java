package org.traficinfo.dsfunctions;

import org.geojson.LineString;
import org.geojson.LngLatAlt;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class LineStringMidpointTest {
    @Test
    public void followsUnequalBentSegmentsInsteadOfAveragingVerticesOrEndpoints() throws Exception {
        var point = LineStringMidpoint.of(new LineString(
                new LngLatAlt(0, 0), new LngLatAlt(0.01, 0), new LngLatAlt(0.01, 0.03)));
        assertEquals(0.01, point.getCoordinates().getLongitude(), 1e-12);
        assertEquals(0.01, point.getCoordinates().getLatitude(), 1e-12);
    }

    @Test
    public void usesGeographicLengthsAtHighLatitude() throws Exception {
        // At 60 degrees latitude, two longitude degrees are approximately one latitude degree.
        var point = LineStringMidpoint.of(new LineString(
                new LngLatAlt(0, 60), new LngLatAlt(2, 60), new LngLatAlt(2, 61)));
        assertEquals(2, point.getCoordinates().getLongitude(), 1e-12);
        assertEquals(60, point.getCoordinates().getLatitude(), 0.0001);
    }

    @Test
    public void handlesRepeatedPositionsAndMidpointAtVertex() throws Exception {
        var point = LineStringMidpoint.of(new LineString(new LngLatAlt(0, 0),
                new LngLatAlt(0, 0), new LngLatAlt(1, 0), new LngLatAlt(1, 0), new LngLatAlt(2, 0)));
        assertEquals(1, point.getCoordinates().getLongitude(), 1e-12);
        assertEquals(0, point.getCoordinates().getLatitude(), 1e-12);
        assertEquals(new org.geojson.Point(3, 43), LineStringMidpoint.of(
                new LineString(new LngLatAlt(3, 43), new LngLatAlt(3, 43))));
    }

    @Test
    public void interpolatesAltitudeWhenBothEndpointsHaveIt() throws Exception {
        var point = LineStringMidpoint.of(new LineString(new LngLatAlt(0, 0, 10), new LngLatAlt(2, 0, 30)));
        assertEquals(1, point.getCoordinates().getLongitude(), 1e-12);
        assertEquals(20, point.getCoordinates().getAltitude(), 1e-12);
    }

    @Test
    public void rejectsInvalidLines() throws Exception {
        for (LineString line : new LineString[]{new LineString(), new LineString(new LngLatAlt(0, 0)),
                new LineString(new LngLatAlt(0, 0), new LngLatAlt(Double.NaN, 0))}) {
            try {
                LineStringMidpoint.of(line);
                fail("Expected invalid LineString to be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("LineString"));
            }
        }
    }
}
