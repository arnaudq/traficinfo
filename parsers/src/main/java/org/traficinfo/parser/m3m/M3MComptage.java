package org.traficinfo.parser.m3m;

import org.traficinfo.parser.Comptage;

import com.opencsv.bean.CsvBindByName;

import java.util.Objects;

public class M3MComptage implements Comptage {

  @CsvBindByName(column="osm_id")
  private Long osmWayId;

  @CsvBindByName(column="code_centrale")
  private String codeCentrale;

  @CsvBindByName(column="name")
  private String nomVoie;

  @CsvBindByName(column="nom_commune")
  private String cityName;


  @CsvBindByName(column="annee")
  private int annee;

  @CsvBindByName(column="type_compteur")
  private String typeCompteur;

  @CsvBindByName(column="type_comptage")
  private String typeComptage;

  @CsvBindByName(column="libelle_groupage")
  private String libelleGroupage;

  @CsvBindByName(column="code_canal")
  private String codeCanal;

  @CsvBindByName(column = "mja_jo")
  private int moyenneJourAnnuelle;

  @CsvBindByName(column = "hp_johvs_matin")
  private String moyenneHeureDePointeMatin;

  @CsvBindByName(column = "hp_johvs_soir")
  private String moyenneHeureDePointeSoir;

  @CsvBindByName(column = "v85ma_tcj")
  private int vitessePratiquee;

  @CsvBindByName(column = "vtma_tcj")
  private int meanSpead;

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

  public String getNomVoie() {
    return nomVoie;
  }

  public void setNomVoie(String nomVoie) {
    this.nomVoie = nomVoie;
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
  public String getLibelleGroupage() {
    return libelleGroupage;
  }

  @Override
  public void setLibelleGroupage(String libelleGroupage) {
    this.libelleGroupage = libelleGroupage;
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
  public int getVitessePratiquee() {
    return vitessePratiquee;
  }

  @Override
  public void setVitessePratiquee(int vitessePratiquee) {
    this.vitessePratiquee = vitessePratiquee;
  }

  @Override
  public int getMeanSpead() {
    return meanSpead;
  }

  @Override
  public void setMeanSpead(int meanSpead) {
    this.meanSpead = meanSpead;
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
    return annee == that.annee && moyenneJourAnnuelle == that.moyenneJourAnnuelle && vitessePratiquee == that.vitessePratiquee && meanSpead == that.meanSpead && maximumSpeed == that.maximumSpeed && bidirectionnelle == that.bidirectionnelle && Objects.equals(osmWayId, that.osmWayId) && Objects.equals(codeCentrale, that.codeCentrale) && Objects.equals(nomVoie, that.nomVoie) && Objects.equals(cityName, that.cityName) && Objects.equals(typeCompteur, that.typeCompteur) && Objects.equals(typeComptage, that.typeComptage) && Objects.equals(libelleGroupage, that.libelleGroupage) && Objects.equals(codeCanal, that.codeCanal) && Objects.equals(moyenneHeureDePointeMatin, that.moyenneHeureDePointeMatin) && Objects.equals(moyenneHeureDePointeSoir, that.moyenneHeureDePointeSoir) && Objects.equals(displayName, that.displayName) && type == that.type;
  }

  @Override
  public int hashCode() {
    return Objects.hash(osmWayId, codeCentrale, nomVoie, cityName, annee, typeCompteur, typeComptage, libelleGroupage, codeCanal, moyenneJourAnnuelle, moyenneHeureDePointeMatin, moyenneHeureDePointeSoir, vitessePratiquee, meanSpead, maximumSpeed, displayName, bidirectionnelle, type);
  }

  @Override
  public String toString() {
    return "M3MComptage{" +
      "osmWayId=" + osmWayId +
      ", codeCentrale='" + codeCentrale + '\'' +
      ", nomVoie='" + nomVoie + '\'' +
      ", nomCommune='" + cityName + '\'' +
      ", annee=" + annee +
      ", typeCompteur='" + typeCompteur + '\'' +
      ", typeComptage='" + typeComptage + '\'' +
      ", libelleGroupage='" + libelleGroupage + '\'' +
      ", codeCanal='" + codeCanal + '\'' +
      ", moyenneJourAnnuelle=" + moyenneJourAnnuelle +
      ", moyenneHeureDePointeMatin='" + moyenneHeureDePointeMatin + '\'' +
      ", moyenneHeureDePointeSoir='" + moyenneHeureDePointeSoir + '\'' +
      ", vitessePratiquee=" + vitessePratiquee +
      ", meanSpead=" + meanSpead +
      ", maximumSpeed=" + maximumSpeed +
      ", displayName='" + displayName + '\'' +
      ", bidirectionnelle=" + bidirectionnelle +
      ", type=" + type +
      '}';
  }
}
