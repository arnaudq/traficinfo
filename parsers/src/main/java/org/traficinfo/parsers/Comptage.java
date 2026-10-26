package org.traficinfo.parsers;

import org.geojson.GeoJsonObject;

public interface Comptage {
  /**
   * Geometry supplied by the source, such as a point, line or polygon.
   * @return geometry, or null when the source does not supply a location.
   */
  default GeoJsonObject getGeometry() {
    return null;
  }

  Long getOsmWayId();

  void setOsmWayId(Long osmWayId);

  /**
   * Official road reference retained for later comparison with an OSM ref tag.
   * For example, DPT34 route_id 34D0011 yields D0011 (typically D 11 in OSM).
   * @return the road reference, or null when the source does not provide one.
   */
  default String getRoadReference() {
    return null;
  }

  /**
   * Street + City name.
   * @return street name or null if not provided (case for 202207 data set).
   */
  String getDisplayName();

  void setDisplayName(String name);

  /**
   * City name.
   * @return city or null if not provided.
   */
  String getCityName();

  void setCityName(String name);

  String getCodeCentrale();

  void setCodeCentrale(String codeCentrale);

  /** Directional coverage derived from the data source's schema. */
  DirectionType getDirectionType();

  int getAnnee();

  void setAnnee(int annee);

  int getMoyenneJourAnnuelle();

  void setMoyenneJourAnnuelle(int moyenneJourAnnuelle);

  String getMoyenneHeureDePointeMatin();

  void setMoyenneHeureDePointeMatin(String moyenneHeureDePointeMatin);

  String getMoyenneHeureDePointeSoir();

  void setMoyenneHeureDePointeSoir(String moyenneHeureDePointeSoir);

  int getV85Speed();

  void setV85Speed(int v85Speed);

  int getMeanSpeed();

  void setMeanSpeed(int meanSpeed);

  int getMaximumSpeed();

  void setMaximumSpeed(int maximumSpeed);

  Type getType();

  void setType(Type type);

  /** Coverage of the count, rather than geographic orientation or channel identity. */
  enum DirectionType {
    UNIDIRECTIONAL,
    BIDIRECTIONAL,
    UNKNOWN
  }

  public enum Type {
    VEHICULES,
    BICYCLES,
    PIETONS_VELOS;
  }
}
