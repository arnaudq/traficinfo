package org.traficinfo.dsfunctions;

/** Outcome of assigning an OSM road way to a traffic counter. */
public enum OSMWayMappingStatus {
    /** Lookup is pending or explicitly requested again; coordinates may be unavailable or an HTTP attempt may have failed. */
    MISSING,
    /** A road way is assigned, either from the seed/manual cache or an accepted reverse lookup. */
    MATCHED,
    /** A road way candidate needs review because its reference is missing or differs from the source. */
    NEEDS_REVIEW,
    /** A successful reverse response contained no suitable road way, including absent or unsupported objects. */
    NOT_FOUND
}
