package org.traficinfo.dsfunctions;

/** Identifiers provided by the source, independent of whether the OSM candidate matches them. */
public enum OSMSourceMatchCriteria {
    /** Neither a road reference nor a street name was provided. */
    NONE,
    /** Only an official road reference, such as D925, was provided. */
    ROAD_REFERENCE,
    /** Only a street name/address was provided. */
    STREET_NAME,
    /** Both identifiers were provided; either matching is sufficient. */
    BOTH
}
