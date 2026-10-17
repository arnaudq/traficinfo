package org.traficinfo.parsers.m3m;

import org.traficinfo.parsers.Comptage;

import com.opencsv.bean.CsvBindByName;

import java.util.Objects;

public class M3MComptage implements Comptage {

  @CsvBindByName(column="osm_id")
  private Long osmWayId;

  @CsvBindByName(column="code_centrale")
  private String codeCentrale;

  @CsvBindByName(column="name")
  private String streetName;

  @CsvBindByName(column="libelle_centrale")
  private String altStreetName;

  @CsvBindByName(column="nom_commune")
  private String cityName;


  @CsvBindByName(column="annee")
  private int annee;

  @CsvBindByName(column="type_compteur")
  private String typeCompteur;

  @CsvBindByName(column="type_comptage")
  private String typeComptage;

  @CsvBindByName(column="code_canal")
  private String codeCanal;

  @CsvBindByName(column = "mja_jo")
  private int moyenneJourAnnuelle;

  @CsvBindByName(column = "hp_johvs_matin")
  private String moyenneHeureDePointeMatin;

  @CsvBindByName(column = "hp_johvs_soir")
  private String moyenneHeureDePointeSoir;

  @CsvBindByName(column = "v85ma_tcj")
  private int v85Speed;

  @CsvBindByName(column = "vtma_tcj")
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

  @Override
  public String getCodeCentrale() {
    return codeCentrale;
  }

  @Override
  public void setCodeCentrale(String codeCentrale) {
    this.codeCentrale = codeCentrale;
  }

  public String getStreetName() {
    return streetName;
  }

  public void setStreetName(String streetName) {
    this.streetName = streetName;
  }

  public String getAltStreetName() {
    return altStreetName;
  }

  public void setAltStreetName(String altStreetName) {
    this.altStreetName = altStreetName;
  }

  public String getCityName() {
    return cityName;
  }

  public void setCityName(String cityName) {
    this.cityName = cityName;
  }

  public String getCodeCanal() {
    return codeCanal;
  }

  public void setCodeCanal(String codeCanal) {
    this.codeCanal = codeCanal;
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

  public String getTypeComptage() {
    return typeComptage;
  }

  public void setTypeComptage(String typeComptage) {
    this.typeComptage = typeComptage;
  }

  @Override
  public int getMoyenneJourAnnuelle() {
    return moyenneJourAnnuelle;
  }

  @Override
  public void setMoyenneJourAnnuelle(int moyenneJourAnnuelle) {
    this.moyenneJourAnnuelle = moyenneJourAnnuelle;
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
  public Type getType() {
    return type;
  }

  @Override
  public void setType(Type type) {
    this.type = type;
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
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    M3MComptage that = (M3MComptage) o;
    return annee == that.annee && moyenneJourAnnuelle == that.moyenneJourAnnuelle && v85Speed == that.v85Speed && meanSpeed == that.meanSpeed && maximumSpeed == that.maximumSpeed && bidirectionnelle == that.bidirectionnelle && Objects.equals(osmWayId, that.osmWayId) && Objects.equals(codeCentrale, that.codeCentrale) && Objects.equals(streetName, that.streetName) && Objects.equals(altStreetName, that.altStreetName) && Objects.equals(cityName, that.cityName) && Objects.equals(typeCompteur, that.typeCompteur) && Objects.equals(typeComptage, that.typeComptage) && Objects.equals(codeCanal, that.codeCanal) && Objects.equals(moyenneHeureDePointeMatin, that.moyenneHeureDePointeMatin) && Objects.equals(moyenneHeureDePointeSoir, that.moyenneHeureDePointeSoir) && Objects.equals(displayName, that.displayName) && type == that.type;
  }

  @Override
  public int hashCode() {
    return Objects.hash(osmWayId, codeCentrale, streetName, altStreetName, cityName, annee, typeCompteur, typeComptage, codeCanal, moyenneJourAnnuelle, moyenneHeureDePointeMatin, moyenneHeureDePointeSoir, v85Speed, meanSpeed, maximumSpeed, displayName, bidirectionnelle, type);
  }

  @Override
  public String toString() {
    return "M3MComptage{" +
      "osmWayId=" + osmWayId +
      ", codeCentrale='" + codeCentrale + '\'' +
      ", streetName='" + streetName + '\'' +
      ", altStreetName='" + altStreetName + '\'' +
      ", nomCommune='" + cityName + '\'' +
      ", annee=" + annee +
      ", typeCompteur='" + typeCompteur + '\'' +
      ", typeComptage='" + typeComptage + '\'' +
      ", codeCanal='" + codeCanal + '\'' +
      ", moyenneJourAnnuelle=" + moyenneJourAnnuelle +
      ", moyenneHeureDePointeMatin='" + moyenneHeureDePointeMatin + '\'' +
      ", moyenneHeureDePointeSoir='" + moyenneHeureDePointeSoir + '\'' +
      ", v85Speed=" + v85Speed +
      ", meanSpeed=" + meanSpeed +
      ", maximumSpeed=" + maximumSpeed +
      ", displayName='" + displayName + '\'' +
      ", bidirectionnelle=" + bidirectionnelle +
      ", type=" + type +
      '}';
  }
}
