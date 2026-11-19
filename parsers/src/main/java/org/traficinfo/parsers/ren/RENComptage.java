package org.traficinfo.parsers.ren;

import org.geojson.GeoJsonObject;
import org.geojson.Point;
import org.traficinfo.parsers.Comptage;
import java.util.Objects;

/** One Rennes station/year/direction, retaining nullable source measures at their original precision. */
public class RENComptage implements Comptage {
  private String stationCode;
  private String sens;
  private Double nbSemaines;
  private String fiabilite;
  private Double tmjaTv;
  private Double tmjaPl;
  private Double v85Tv;
  private Double v85Pl;
  private Double vMoyenneTv;
  private Double vMoyennePl;
  private Double debitHpmTv;
  private Double debitHpmPl;
  private Double debitHpsTv;
  private Double debitHpsPl;
  private Double latitude;
  private Double longitude;
  private Long osmWayId;
  private String codeCentrale;
  private String cityName;
  private String displayName;
  private int annee;
  private String moyenneHeureDePointeMatin;
  private String moyenneHeureDePointeSoir;
  private int maximumSpeed = -1;
  private Boolean bicycleContraflowAllowed;
  private Type type = Type.VEHICULES;

  public String getStationCode() { return stationCode; }
  public void setStationCode(String value) { stationCode = value; }

  public String getSens() { return sens; }
  public void setSens(String value) { sens = value; }

  public Double getNbSemaines() { return nbSemaines; }
  public void setNbSemaines(Double value) { nbSemaines = value; }

  public String getFiabilite() { return fiabilite; }
  public void setFiabilite(String value) { fiabilite = value; }

  public Double getTmjaTv() { return tmjaTv; }
  public void setTmjaTv(Double value) { tmjaTv = value; }

  public Double getTmjaPl() { return tmjaPl; }
  public void setTmjaPl(Double value) { tmjaPl = value; }

  public Double getV85Tv() { return v85Tv; }
  public void setV85Tv(Double value) { v85Tv = value; }

  public Double getV85Pl() { return v85Pl; }
  public void setV85Pl(Double value) { v85Pl = value; }

  public Double getVMoyenneTv() { return vMoyenneTv; }
  public void setVMoyenneTv(Double value) { vMoyenneTv = value; }

  public Double getVMoyennePl() { return vMoyennePl; }
  public void setVMoyennePl(Double value) { vMoyennePl = value; }

  public Double getDebitHpmTv() { return debitHpmTv; }
  public void setDebitHpmTv(Double value) { debitHpmTv = value; }

  public Double getDebitHpmPl() { return debitHpmPl; }
  public void setDebitHpmPl(Double value) { debitHpmPl = value; }

  public Double getDebitHpsTv() { return debitHpsTv; }
  public void setDebitHpsTv(Double value) { debitHpsTv = value; }

  public Double getDebitHpsPl() { return debitHpsPl; }
  public void setDebitHpsPl(Double value) { debitHpsPl = value; }

  public Double getLatitude() { return latitude; }
  public void setLatitude(Double value) { latitude = value; }

  public Double getLongitude() { return longitude; }
  public void setLongitude(Double value) { longitude = value; }

  public Long getOsmWayId() { return osmWayId; }
  public void setOsmWayId(Long value) { osmWayId = value; }

  public String getCodeCentrale() { return codeCentrale; }
  public void setCodeCentrale(String value) { codeCentrale = value; }

  public String getCityName() { return cityName; }
  public void setCityName(String value) { cityName = value; }

  public String getDisplayName() { return displayName; }
  public void setDisplayName(String value) { displayName = value; }

  public int getAnnee() { return annee; }
  public void setAnnee(int value) { annee = value; }

  public String getMoyenneHeureDePointeMatin() { return moyenneHeureDePointeMatin; }
  public void setMoyenneHeureDePointeMatin(String value) { moyenneHeureDePointeMatin = value; }

  public String getMoyenneHeureDePointeSoir() { return moyenneHeureDePointeSoir; }
  public void setMoyenneHeureDePointeSoir(String value) { moyenneHeureDePointeSoir = value; }

  public int getMaximumSpeed() { return maximumSpeed; }
  public void setMaximumSpeed(int value) { maximumSpeed = value; }

  public Boolean getBicycleContraflowAllowed() { return bicycleContraflowAllowed; }
  public void setBicycleContraflowAllowed(Boolean value) { bicycleContraflowAllowed = value; }

  public Type getType() { return type; }
  public void setType(Type value) { type = value; }

  @Override public GeoJsonObject getGeometry() {
    return longitude == null || latitude == null ? null : new Point(longitude, latitude);
  }

  @Override public DirectionType getDirectionType() {
    if ("Sens cumulés".equals(sens)) return DirectionType.BIDIRECTIONAL;
    if ("Nord-Sud / Ouest-Est".equals(sens) || "Sud-Nord / Est-Ouest".equals(sens)) {
      return DirectionType.UNIDIRECTIONAL;
    }
    return DirectionType.UNKNOWN;
  }

  private static int rounded(Double value) { return value == null ? 0 : (int) Math.round(value); }
  @Override public int getMoyenneJourAnnuelle() { return rounded(tmjaTv); }
  @Override public void setMoyenneJourAnnuelle(int value) { tmjaTv = (double) value; }
  @Override public int getV85Speed() { return rounded(v85Tv); }
  @Override public void setV85Speed(int value) { v85Tv = (double) value; }
  @Override public int getMeanSpeed() { return rounded(vMoyenneTv); }
  @Override public void setMeanSpeed(int value) { vMoyenneTv = (double) value; }

  @Override public boolean equals(Object other) {
    if (this == other) return true;
    if (other == null || getClass() != other.getClass()) return false;
    RENComptage that = (RENComptage) other;
    return Objects.equals(stationCode, that.stationCode)
        && Objects.equals(sens, that.sens)
        && Objects.equals(nbSemaines, that.nbSemaines)
        && Objects.equals(fiabilite, that.fiabilite)
        && Objects.equals(tmjaTv, that.tmjaTv)
        && Objects.equals(tmjaPl, that.tmjaPl)
        && Objects.equals(v85Tv, that.v85Tv)
        && Objects.equals(v85Pl, that.v85Pl)
        && Objects.equals(vMoyenneTv, that.vMoyenneTv)
        && Objects.equals(vMoyennePl, that.vMoyennePl)
        && Objects.equals(debitHpmTv, that.debitHpmTv)
        && Objects.equals(debitHpmPl, that.debitHpmPl)
        && Objects.equals(debitHpsTv, that.debitHpsTv)
        && Objects.equals(debitHpsPl, that.debitHpsPl)
        && Objects.equals(latitude, that.latitude)
        && Objects.equals(longitude, that.longitude)
        && Objects.equals(osmWayId, that.osmWayId)
        && Objects.equals(codeCentrale, that.codeCentrale)
        && Objects.equals(cityName, that.cityName)
        && Objects.equals(displayName, that.displayName)
        && Objects.equals(annee, that.annee)
        && Objects.equals(moyenneHeureDePointeMatin, that.moyenneHeureDePointeMatin)
        && Objects.equals(moyenneHeureDePointeSoir, that.moyenneHeureDePointeSoir)
        && Objects.equals(maximumSpeed, that.maximumSpeed)
        && Objects.equals(bicycleContraflowAllowed, that.bicycleContraflowAllowed)
        && Objects.equals(type, that.type);
  }

  @Override public int hashCode() {
    return Objects.hash(stationCode, sens, nbSemaines, fiabilite, tmjaTv, tmjaPl, v85Tv, v85Pl, vMoyenneTv, vMoyennePl, debitHpmTv, debitHpmPl, debitHpsTv, debitHpsPl, latitude, longitude, osmWayId, codeCentrale, cityName, displayName, annee, moyenneHeureDePointeMatin, moyenneHeureDePointeSoir, maximumSpeed, bicycleContraflowAllowed, type);
  }
}
