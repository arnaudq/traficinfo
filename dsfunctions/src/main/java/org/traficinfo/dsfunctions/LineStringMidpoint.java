package org.traficinfo.dsfunctions;

import org.geojson.LineString;
import org.geojson.LngLatAlt;
import org.geojson.Point;

import java.io.IOException;

/** Half the horizontal geographic length, interpolated on the original coordinate segment. */
final class LineStringMidpoint {
    private LineStringMidpoint() {}

    static Point of(LineString line) throws IOException {
        var coordinates = line.getCoordinates();
        if (coordinates == null || coordinates.size() < 2) {
            throw new IOException("LineString requires at least two positions");
        }
        for (LngLatAlt position : coordinates) {
            if (position == null || !Double.isFinite(position.getLongitude())
                    || !Double.isFinite(position.getLatitude()) || Math.abs(position.getLatitude()) > 90
                    || Math.abs(position.getLongitude()) > 180) {
                throw new IOException("Invalid LineString position");
            }
        }

        double[] lengths = new double[coordinates.size() - 1];
        double total = 0;
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = angularDistance(coordinates.get(i), coordinates.get(i + 1));
            total += lengths[i];
        }
        if (total == 0) return new Point(coordinates.get(0));

        double remaining = total / 2;
        for (int i = 0; i < lengths.length; i++) {
            if (lengths[i] == 0) continue;
            if (remaining <= lengths[i]) {
                LngLatAlt start = coordinates.get(i);
                LngLatAlt end = coordinates.get(i + 1);
                double fraction = remaining / lengths[i];
                Point point = new Point(
                        start.getLongitude() + fraction * (end.getLongitude() - start.getLongitude()),
                        start.getLatitude() + fraction * (end.getLatitude() - start.getLatitude()));
                if (start.hasAltitude() && end.hasAltitude()) {
                    point.getCoordinates().setAltitude(start.getAltitude()
                            + fraction * (end.getAltitude() - start.getAltitude()));
                }
                return point;
            }
            remaining -= lengths[i];
        }
        throw new IOException("Unable to locate LineString midpoint");
    }

    private static double angularDistance(LngLatAlt start, LngLatAlt end) {
        // Haversine distance without the common Earth-radius factor, which cancels in the ratio.
        double latitude = Math.toRadians(end.getLatitude() - start.getLatitude());
        double longitude = Math.toRadians(end.getLongitude() - start.getLongitude());
        double haversine = Math.pow(Math.sin(latitude / 2), 2)
                + Math.cos(Math.toRadians(start.getLatitude())) * Math.cos(Math.toRadians(end.getLatitude()))
                * Math.pow(Math.sin(longitude / 2), 2);
        return 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, haversine))));
    }
}
