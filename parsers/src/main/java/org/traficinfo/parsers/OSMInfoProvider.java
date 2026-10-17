package org.traficinfo.parsers;

/** Provides cached OSM way information without exposing cache storage. */
@FunctionalInterface
public interface OSMInfoProvider {
    /** Returns cached information for the way, or null when no record is available. */
    OSMWayInfo get(long wayId);
}
