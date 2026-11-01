package org.traficinfo.parsers.dpt76;

import org.geojson.GeoJsonObject;
import org.geojson.Point;
import org.traficinfo.parsers.Comptage;
import java.util.Objects;

/** One counting-point record from the Seine-Maritime GeoJSON export. */
public class DPT76Comptage implements Comptage {
  private Long fid;
  private Long objectId;
  private Double stationCode;
  private String routeId;
  private Double cumuld;
  private String plod;
  private Double absd;
  private String typeCompteur;
  private Integer mjaTvTcj;
  private Integer tmjaSens1;
  private Integer tmjaSens2;
  private Double pourcentagePl;
  private Double pourcentagePlSens1;
  private Double pourcentagePlSens2;
  private Double latitude;
  private Double longitude;
  private Long osmWayId;
  private String codeCentrale;
  private String cityName;
  private String displayName;
  private int annee;
  private String moyenneHeureDePointeMatin;
  private String moyenneHeureDePointeSoir;
  private int v85Speed;
  private int meanSpeed;
  private int maximumSpeed = -1;
  private Type type = Type.VEHICULES;

  public Long getFid() { return fid; }

  public void setFid(Long fid) { this.fid = fid; }

  public Long getObjectId() { return objectId; }

  public void setObjectId(Long objectId) { this.objectId = objectId; }

  public Double getStationCode() { return stationCode; }

  public void setStationCode(Double stationCode) { this.stationCode = stationCode; }

  public String getRouteId() { return routeId; }

  public void setRouteId(String routeId) { this.routeId = routeId; }

  public Double getCumuld() { return cumuld; }

  public void setCumuld(Double cumuld) { this.cumuld = cumuld; }

  public String getPlod() { return plod; }

  public void setPlod(String plod) { this.plod = plod; }

  public Double getAbsd() { return absd; }

  public void setAbsd(Double absd) { this.absd = absd; }

  public String getTypeCompteur() { return typeCompteur; }

  public void setTypeCompteur(String typeCompteur) { this.typeCompteur = typeCompteur; }

  public Integer getMjaTvTcj() { return mjaTvTcj; }

  public void setMjaTvTcj(Integer mjaTvTcj) { this.mjaTvTcj = mjaTvTcj; }

  public Integer getTmjaSens1() { return tmjaSens1; }

  public void setTmjaSens1(Integer tmjaSens1) { this.tmjaSens1 = tmjaSens1; }

  public Integer getTmjaSens2() { return tmjaSens2; }

  public void setTmjaSens2(Integer tmjaSens2) { this.tmjaSens2 = tmjaSens2; }

  public Double getPourcentagePl() { return pourcentagePl; }

  public void setPourcentagePl(Double pourcentagePl) { this.pourcentagePl = pourcentagePl; }

  public Double getPourcentagePlSens1() { return pourcentagePlSens1; }

  public void setPourcentagePlSens1(Double pourcentagePlSens1) { this.pourcentagePlSens1 = pourcentagePlSens1; }

  public Double getPourcentagePlSens2() { return pourcentagePlSens2; }

  public void setPourcentagePlSens2(Double pourcentagePlSens2) { this.pourcentagePlSens2 = pourcentagePlSens2; }

  public Double getLatitude() { return latitude; }

  public void setLatitude(Double latitude) { this.latitude = latitude; }

  public Double getLongitude() { return longitude; }

  public void setLongitude(Double longitude) { this.longitude = longitude; }

  public Long getOsmWayId() { return osmWayId; }

  public void setOsmWayId(Long osmWayId) { this.osmWayId = osmWayId; }

  public String getCodeCentrale() { return codeCentrale; }

  public void setCodeCentrale(String codeCentrale) { this.codeCentrale = codeCentrale; }

  public String getCityName() { return cityName; }

  public void setCityName(String cityName) { this.cityName = cityName; }

  public String getDisplayName() { return displayName; }

  public void setDisplayName(String displayName) { this.displayName = displayName; }

  public int getAnnee() { return annee; }

  public void setAnnee(int annee) { this.annee = annee; }

  public String getMoyenneHeureDePointeMatin() { return moyenneHeureDePointeMatin; }

  public void setMoyenneHeureDePointeMatin(String moyenneHeureDePointeMatin) { this.moyenneHeureDePointeMatin = moyenneHeureDePointeMatin; }

  public String getMoyenneHeureDePointeSoir() { return moyenneHeureDePointeSoir; }

  public void setMoyenneHeureDePointeSoir(String moyenneHeureDePointeSoir) { this.moyenneHeureDePointeSoir = moyenneHeureDePointeSoir; }

  public int getV85Speed() { return v85Speed; }

  public void setV85Speed(int v85Speed) { this.v85Speed = v85Speed; }

  public int getMeanSpeed() { return meanSpeed; }

  public void setMeanSpeed(int meanSpeed) { this.meanSpeed = meanSpeed; }

  public int getMaximumSpeed() { return maximumSpeed; }

  public void setMaximumSpeed(int maximumSpeed) { this.maximumSpeed = maximumSpeed; }

  public Type getType() { return type; }

  public void setType(Type type) { this.type = type; }

  @Override
  public GeoJsonObject getGeometry() {
    return longitude == null || latitude == null ? null : new Point(longitude, latitude);
  }

  /** Strip the department prefix; retain the road's leading zeros for OSM matching. */
  @Override
  public String getRoadReference() {
    return routeId == null ? null : routeId.replaceFirst("^76\\s+", "").replaceAll("\\s+", "");
  }

  /** The mapped TMJA is the publisher's total across both directions. */
  @Override
  public DirectionType getDirectionType() { return DirectionType.BIDIRECTIONAL; }

  @Override
  public int getMoyenneJourAnnuelle() { return mjaTvTcj == null ? 0 : mjaTvTcj; }

  @Override
  public void setMoyenneJourAnnuelle(int value) { mjaTvTcj = value; }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (other == null || getClass() != other.getClass()) return false;
    DPT76Comptage that = (DPT76Comptage) other;
    return Objects.equals(fid, that.fid)
        && Objects.equals(objectId, that.objectId)
        && Objects.equals(stationCode, that.stationCode)
        && Objects.equals(routeId, that.routeId)
        && Objects.equals(cumuld, that.cumuld)
        && Objects.equals(plod, that.plod)
        && Objects.equals(absd, that.absd)
        && Objects.equals(typeCompteur, that.typeCompteur)
        && Objects.equals(mjaTvTcj, that.mjaTvTcj)
        && Objects.equals(tmjaSens1, that.tmjaSens1)
        && Objects.equals(tmjaSens2, that.tmjaSens2)
        && Objects.equals(pourcentagePl, that.pourcentagePl)
        && Objects.equals(pourcentagePlSens1, that.pourcentagePlSens1)
        && Objects.equals(pourcentagePlSens2, that.pourcentagePlSens2)
        && Objects.equals(latitude, that.latitude)
        && Objects.equals(longitude, that.longitude)
        && Objects.equals(osmWayId, that.osmWayId)
        && Objects.equals(codeCentrale, that.codeCentrale)
        && Objects.equals(cityName, that.cityName)
        && Objects.equals(displayName, that.displayName)
        && Objects.equals(annee, that.annee)
        && Objects.equals(moyenneHeureDePointeMatin, that.moyenneHeureDePointeMatin)
        && Objects.equals(moyenneHeureDePointeSoir, that.moyenneHeureDePointeSoir)
        && Objects.equals(v85Speed, that.v85Speed)
        && Objects.equals(meanSpeed, that.meanSpeed)
        && Objects.equals(maximumSpeed, that.maximumSpeed)
        && Objects.equals(type, that.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fid, objectId, stationCode, routeId, cumuld, plod, absd, typeCompteur, mjaTvTcj, tmjaSens1, tmjaSens2, pourcentagePl, pourcentagePlSens1, pourcentagePlSens2, latitude, longitude, osmWayId, codeCentrale, cityName, displayName, annee, moyenneHeureDePointeMatin, moyenneHeureDePointeSoir, v85Speed, meanSpeed, maximumSpeed, type);
  }
}
