package org.traficinfo.parsers;

import org.geojson.Point;

/** Provides cached OSM way information without exposing cache storage. */
@FunctionalInterface
public interface OSMInfoProvider {
    /** Returns cached information, or null if absent; implementations may queue missing or stale ways for later refresh. */
    OSMWayInfo getWayInfo(long wayId);

    /** Returns a cached way ID; records unresolved counters with nullable location, road reference, and street name. No REST lookup. */
    default Long getWayId(String centraleId, Point point, String roadReference, String streetName) {
        return null;
    }
}
