package org.traficinfo.parser;

public interface Comptage {
  Long getOsmWayId();

  void setOsmWayId(Long osmWayId);

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

  String getLibelleGroupage();

  void setLibelleGroupage(String libelleGroupage);

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

  boolean isBidirectionnelle();

  void setBidirectionnelle(boolean bidirectionnelle);

  public enum Type {
    VEHICULES,
    BICYCLES,
    PIETONS_VELOS;
  }
}
