package org.traficinfo.parsers.nim;

import org.geojson.GeoJsonObject;
import org.geojson.Point;
import org.traficinfo.parsers.Comptage;

import java.util.Objects;

/** Yearly camera count with observation coverage and a daily average over complete days. */
public class NIMComptage implements Comptage {
  private Long osmWayId;
  private String cameraId;
  private String codeCentrale;
  private String codeInsee;
  private String position;
  private String displayName;
  private String cityName;
  private int annee;
  private Double latitude;
  private Double longitude;
  private Integer dailyAverage;
  private int observedDays;
  private int completeDays;
  private int observedHours;
  private int conflictingHours;
  private long totalPassages;
  private long completeDayPassages;
  private String firstDate;
  private String lastDate;
  private String moyenneHeureDePointeMatin;
  private String moyenneHeureDePointeSoir;
  private int v85Speed;
  private int meanSpeed;
  private int maximumSpeed = -1;
  private Type type = Type.VEHICULES;

  public Long getOsmWayId() { return osmWayId; }

  public void setOsmWayId(Long value) { osmWayId = value; }

  public String getCameraId() { return cameraId; }

  public void setCameraId(String value) { cameraId = value; }

  public String getCodeCentrale() { return codeCentrale; }

  public void setCodeCentrale(String value) { codeCentrale = value; }

  public String getCodeInsee() { return codeInsee; }

  public void setCodeInsee(String value) { codeInsee = value; }

  public String getPosition() { return position; }

  public void setPosition(String value) { position = value; }

  public String getDisplayName() { return displayName; }

  public void setDisplayName(String value) { displayName = value; }

  public String getCityName() { return cityName; }

  public void setCityName(String value) { cityName = value; }

  public int getAnnee() { return annee; }

  public void setAnnee(int value) { annee = value; }

  public Double getLatitude() { return latitude; }

  public void setLatitude(Double value) { latitude = value; }

  public Double getLongitude() { return longitude; }

  public void setLongitude(Double value) { longitude = value; }

  public Integer getDailyAverage() { return dailyAverage; }

  public void setDailyAverage(Integer value) { dailyAverage = value; }

  public int getObservedDays() { return observedDays; }

  public void setObservedDays(int value) { observedDays = value; }

  public int getCompleteDays() { return completeDays; }

  public void setCompleteDays(int value) { completeDays = value; }

  public int getConflictingHours() { return conflictingHours; }

  public void setConflictingHours(int value) { conflictingHours = value; }

  public int getObservedHours() { return observedHours; }

  public void setObservedHours(int value) { observedHours = value; }

  public long getTotalPassages() { return totalPassages; }

  public void setTotalPassages(long value) { totalPassages = value; }

  public long getCompleteDayPassages() { return completeDayPassages; }

  public void setCompleteDayPassages(long value) { completeDayPassages = value; }

  public String getFirstDate() { return firstDate; }

  public void setFirstDate(String value) { firstDate = value; }

  public String getLastDate() { return lastDate; }

  public void setLastDate(String value) { lastDate = value; }

  public String getMoyenneHeureDePointeMatin() { return moyenneHeureDePointeMatin; }

  public void setMoyenneHeureDePointeMatin(String value) { moyenneHeureDePointeMatin = value; }

  public String getMoyenneHeureDePointeSoir() { return moyenneHeureDePointeSoir; }

  public void setMoyenneHeureDePointeSoir(String value) { moyenneHeureDePointeSoir = value; }

  public int getV85Speed() { return v85Speed; }

  public void setV85Speed(int value) { v85Speed = value; }

  public int getMeanSpeed() { return meanSpeed; }

  public void setMeanSpeed(int value) { meanSpeed = value; }

  public int getMaximumSpeed() { return maximumSpeed; }

  public void setMaximumSpeed(int value) { maximumSpeed = value; }

  public Type getType() { return type; }

  public void setType(Type value) { type = value; }

  @Override
  public int getMoyenneJourAnnuelle() { return dailyAverage == null ? 0 : dailyAverage; }

  @Override
  public void setMoyenneJourAnnuelle(int value) { dailyAverage = value; }

  @Override
  public DirectionType getDirectionType() { return DirectionType.BIDIRECTIONAL; }

  @Override
  public GeoJsonObject getGeometry() {
    return latitude == null || longitude == null ? null : new Point(longitude, latitude);
  }

  public int getIncompleteDays() { return observedDays - completeDays; }

  /** Calendar gaps inside the observed date range, without assuming year-round operation. */
  public int getDaysWithoutRecords() {
    if (firstDate == null || lastDate == null) return 0;
    return (int) java.time.temporal.ChronoUnit.DAYS.between(
        java.time.LocalDate.parse(firstDate), java.time.LocalDate.parse(lastDate)) + 1 - observedDays;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (other == null || getClass() != other.getClass()) return false;
    NIMComptage that = (NIMComptage) other;
    return Objects.equals(osmWayId, that.osmWayId)
        && Objects.equals(cameraId, that.cameraId)
        && Objects.equals(codeCentrale, that.codeCentrale)
        && Objects.equals(codeInsee, that.codeInsee)
        && Objects.equals(position, that.position)
        && Objects.equals(displayName, that.displayName)
        && Objects.equals(cityName, that.cityName)
        && Objects.equals(annee, that.annee)
        && Objects.equals(latitude, that.latitude)
        && Objects.equals(longitude, that.longitude)
        && Objects.equals(dailyAverage, that.dailyAverage)
        && Objects.equals(observedDays, that.observedDays)
        && Objects.equals(completeDays, that.completeDays)
        && Objects.equals(observedHours, that.observedHours)
        && conflictingHours == that.conflictingHours
        && Objects.equals(totalPassages, that.totalPassages)
        && Objects.equals(completeDayPassages, that.completeDayPassages)
        && Objects.equals(firstDate, that.firstDate)
        && Objects.equals(lastDate, that.lastDate)
        && Objects.equals(moyenneHeureDePointeMatin, that.moyenneHeureDePointeMatin)
        && Objects.equals(moyenneHeureDePointeSoir, that.moyenneHeureDePointeSoir)
        && Objects.equals(v85Speed, that.v85Speed)
        && Objects.equals(meanSpeed, that.meanSpeed)
        && Objects.equals(maximumSpeed, that.maximumSpeed)
        && Objects.equals(type, that.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(osmWayId, cameraId, codeCentrale, codeInsee, position, displayName, cityName, annee, latitude, longitude, dailyAverage, observedDays, completeDays, observedHours, conflictingHours, totalPassages, completeDayPassages, firstDate, lastDate, moyenneHeureDePointeMatin, moyenneHeureDePointeSoir, v85Speed, meanSpeed, maximumSpeed, type);
  }
}
