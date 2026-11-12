package org.traficinfo.parsers;

import org.geojson.GeoJsonObject;

/** Source location and optional OSM road assignment, shared by counts and accidents. */
public interface GeographicRecord {
  /** Source geometry in WGS84, or null when unavailable. */
  default GeoJsonObject getGeometry() {
    return null;
  }

  Long getOsmWayId();

  void setOsmWayId(Long osmWayId);

  /** Source street name/address for comparison with OSM names, or null if unavailable. */
  default String getStreetName() {
    return null;
  }

  /** Official road reference for comparison with an OSM ref tag, or null if absent. */
  default String getRoadReference() {
    return null;
  }
}
