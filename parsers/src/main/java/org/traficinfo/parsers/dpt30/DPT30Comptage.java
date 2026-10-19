package org.traficinfo.parsers.dpt30;

import com.opencsv.bean.CsvBindByName;
import org.geojson.GeoJsonObject;
import org.geojson.Point;
import org.traficinfo.parsers.Comptage;

import java.util.Objects;

/** An annual traffic-count record from the Gard departmental CSV export. */
public class DPT30Comptage implements Comptage {

  private Long osmWayId;

  @CsvBindByName(column = "id_tmja")
  private String idTmja;

  private String siteId;

  private String codeCentrale;

  @CsvBindByName(column = "communes")
  private String cityName;

  @CsvBindByName(column = "numero_route")
  private String routeId;

  @CsvBindByName(column = "annee")
  private int annee;

  @CsvBindByName(column = "type_comptage")
  private String typeCompteur;

  @CsvBindByName(column = "tmja_poids_lourds")
  private Integer tmjaPoidsLourds;

  @CsvBindByName(column = "pourcentage_tmja_poids_lourds")
  private Double pourcentageTmjaPoidsLourds;

  @CsvBindByName(column = "latitude")
  private Double latitude;

  @CsvBindByName(column = "longitude")
  private Double longitude;

  @CsvBindByName(column = "tmja_tous_vehicules")
  private Integer mjaTvTcj;

  private String moyenneHeureDePointeMatin;

  private String moyenneHeureDePointeSoir;

  private int v85Speed;

  private int meanSpeed;

  private int maximumSpeed = -1;

  private String displayName;

  private boolean bidirectionnelle;

  private Type type = Type.VEHICULES;

  @Override
  public Long getOsmWayId() {
    return osmWayId;
  }

  @Override
  public void setOsmWayId(Long osmWayId) {
    this.osmWayId = osmWayId;
  }

  public String getIdTmja() {
    return idTmja;
  }

  public void setIdTmja(String idTmja) {
    this.idTmja = idTmja;
  }

  public Integer getTmjaPoidsLourds() {
    return tmjaPoidsLourds;
  }

  public void setTmjaPoidsLourds(Integer tmjaPoidsLourds) {
    this.tmjaPoidsLourds = tmjaPoidsLourds;
  }

  public Double getPourcentageTmjaPoidsLourds() {
    return pourcentageTmjaPoidsLourds;
  }

  public void setPourcentageTmjaPoidsLourds(Double pourcentageTmjaPoidsLourds) {
    this.pourcentageTmjaPoidsLourds = pourcentageTmjaPoidsLourds;
  }

  public String getSiteId() {
    return siteId;
  }

  public void setSiteId(String siteId) {
    this.siteId = siteId;
  }

  @Override
  public String getCodeCentrale() {
    return codeCentrale;
  }

  @Override
  public void setCodeCentrale(String codeCentrale) {
    this.codeCentrale = codeCentrale;
  }

  @Override
  public String getCityName() {
    return cityName;
  }

  @Override
  public void setCityName(String cityName) {
    this.cityName = cityName;
  }

  public String getRouteId() {
    return routeId;
  }

  public void setRouteId(String routeId) {
    this.routeId = routeId;
  }

  @Override
  public int getAnnee() {
    return annee;
  }

  @Override
  public void setAnnee(int annee) {
    this.annee = annee;
  }

  public String getTypeCompteur() {
    return typeCompteur;
  }

  public void setTypeCompteur(String typeCompteur) {
    this.typeCompteur = typeCompteur;
  }

  public Double getLatitude() {
    return latitude;
  }

  public void setLatitude(Double latitude) {
    this.latitude = latitude;
  }

  public Double getLongitude() {
    return longitude;
  }

  public void setLongitude(Double longitude) {
    this.longitude = longitude;
  }

  @Override
  public GeoJsonObject getGeometry() {
    return latitude == null || longitude == null ? null : new Point(longitude, latitude);
  }

  public Integer getMjaTvTcj() {
    return mjaTvTcj;
  }

  public void setMjaTvTcj(Integer mjaTvTcj) {
    this.mjaTvTcj = mjaTvTcj;
  }

  @Override
  public String getMoyenneHeureDePointeMatin() {
    return moyenneHeureDePointeMatin;
  }

  @Override
  public void setMoyenneHeureDePointeMatin(String moyenneHeureDePointeMatin) {
    this.moyenneHeureDePointeMatin = moyenneHeureDePointeMatin;
  }

  @Override
  public String getMoyenneHeureDePointeSoir() {
    return moyenneHeureDePointeSoir;
  }

  @Override
  public void setMoyenneHeureDePointeSoir(String moyenneHeureDePointeSoir) {
    this.moyenneHeureDePointeSoir = moyenneHeureDePointeSoir;
  }

  @Override
  public int getV85Speed() {
    return v85Speed;
  }

  @Override
  public void setV85Speed(int v85Speed) {
    this.v85Speed = v85Speed;
  }

  @Override
  public int getMeanSpeed() {
    return meanSpeed;
  }

  @Override
  public void setMeanSpeed(int meanSpeed) {
    this.meanSpeed = meanSpeed;
  }

  @Override
  public int getMaximumSpeed() {
    return maximumSpeed;
  }

  @Override
  public void setMaximumSpeed(int maximumSpeed) {
    this.maximumSpeed = maximumSpeed;
  }

  @Override
  public String getDisplayName() {
    return displayName;
  }

  @Override
  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  @Override
  public boolean isBidirectionnelle() {
    return bidirectionnelle;
  }

  @Override
  public void setBidirectionnelle(boolean bidirectionnelle) {
    this.bidirectionnelle = bidirectionnelle;
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public void setType(Type type) {
    this.type = type;
  }

  /** Missing counts remain null in getMjaTvTcj(); the shared interface requires an int. */
  @Override
  public int getMoyenneJourAnnuelle() {
    return mjaTvTcj == null ? 0 : mjaTvTcj;
  }

  @Override
  public void setMoyenneJourAnnuelle(int moyenneJourAnnuelle) {
    this.mjaTvTcj = moyenneJourAnnuelle;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DPT30Comptage that = (DPT30Comptage) o;
    return Objects.equals(osmWayId, that.osmWayId)
        && Objects.equals(siteId, that.siteId)
        && Objects.equals(codeCentrale, that.codeCentrale)
        && Objects.equals(cityName, that.cityName)
        && Objects.equals(routeId, that.routeId)
        && annee == that.annee
        && Objects.equals(typeCompteur, that.typeCompteur)
        && Objects.equals(idTmja, that.idTmja)
        && Objects.equals(tmjaPoidsLourds, that.tmjaPoidsLourds)
        && Objects.equals(pourcentageTmjaPoidsLourds, that.pourcentageTmjaPoidsLourds)
        && Objects.equals(latitude, that.latitude)
        && Objects.equals(longitude, that.longitude)
        && Objects.equals(mjaTvTcj, that.mjaTvTcj)
        && Objects.equals(moyenneHeureDePointeMatin, that.moyenneHeureDePointeMatin)
        && Objects.equals(moyenneHeureDePointeSoir, that.moyenneHeureDePointeSoir)
        && v85Speed == that.v85Speed
        && meanSpeed == that.meanSpeed
        && maximumSpeed == that.maximumSpeed
        && Objects.equals(displayName, that.displayName)
        && bidirectionnelle == that.bidirectionnelle
        && Objects.equals(type, that.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(osmWayId, siteId, codeCentrale, cityName, routeId, annee, typeCompteur, idTmja, tmjaPoidsLourds, pourcentageTmjaPoidsLourds, latitude, longitude, mjaTvTcj, moyenneHeureDePointeMatin, moyenneHeureDePointeSoir, v85Speed, meanSpeed, maximumSpeed, displayName, bidirectionnelle, type);
  }

  @Override
  public String toString() {
    return "DPT30Comptage{codeCentrale='" + codeCentrale + "', annee=" + annee
        + ", mjaTvTcj=" + mjaTvTcj + ", cityName='" + cityName + "'}";
  }
}
