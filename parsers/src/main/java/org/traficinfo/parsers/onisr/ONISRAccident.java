package org.traficinfo.parsers.onisr;

import org.geojson.Point;
import org.traficinfo.parsers.Accident;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** A BAAC characteristics record; raw columns remain available for future companion joins. */
public final class ONISRAccident implements Accident {
  private final String accidentId;
  private final LocalDate date;
  private final LocalTime time;
  private final String regionalId;
  private final String municipalityId;
  private final String address;
  private final Double latitude;
  private final Double longitude;
  private final Map<String, String> sourceFields;
  private final List<ONISRAccidentParticipant> participants;
  private Long osmWayId;

  ONISRAccident(String accidentId, LocalDate date, LocalTime time, String regionalId,
      String municipalityId, String address, Double latitude, Double longitude,
      Map<String, String> sourceFields) {
    this(accidentId, date, time, regionalId, municipalityId, address, latitude, longitude, sourceFields, List.of());
  }

  ONISRAccident(String accidentId, LocalDate date, LocalTime time, String regionalId,
      String municipalityId, String address, Double latitude, Double longitude,
      Map<String, String> sourceFields, List<ONISRAccidentParticipant> participants) {
    this.accidentId = accidentId;
    this.date = date;
    this.time = time;
    this.regionalId = regionalId;
    this.municipalityId = municipalityId;
    this.address = address;
    this.latitude = latitude;
    this.longitude = longitude;
    this.sourceFields = Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    this.participants = participants == null ? List.of() : List.copyOf(participants);
  }

  @Override public String getAccidentId() { return accidentId; }
  @Override public LocalDate getDate() { return date; }
  @Override public LocalTime getTime() { return time; }
  @Override public String getRegionalId() { return regionalId; }
  @Override public String getMunicipalityId() { return municipalityId; }
  @Override public String getAddress() { return address; }
  public Double getLatitude() { return latitude; }
  public Double getLongitude() { return longitude; }
  @Override public Long getOsmWayId() { return osmWayId; }
  @Override public void setOsmWayId(Long osmWayId) { this.osmWayId = osmWayId; }
  @Override public List<ONISRAccidentParticipant> getParticipants() { return participants; }

  public ONISRAccident withParticipants(List<ONISRAccidentParticipant> participants) {
    ONISRAccident enriched = new ONISRAccident(accidentId, date, time, regionalId, municipalityId,
        address, latitude, longitude, sourceFields, participants);
    enriched.setOsmWayId(osmWayId);
    return enriched;
  }

  @Override
  public Point getGeometry() {
    return latitude == null || longitude == null ? null : new Point(longitude, latitude);
  }

  /** Original column names and cell text, including unknown columns and missing-value codes. */
  public Map<String, String> getSourceFields() { return sourceFields; }

  /** BAAC codes; null means unspecified (blank, dot, zero, N/A or -1). */
  public Integer getLighting() { return code("lum"); }
  public Integer getBuiltUpArea() { return code("agg"); }
  public Integer getIntersection() { return code("int"); }
  public Integer getAtmosphericConditions() { return code("atm"); }
  public Integer getCollisionType() { return code("col"); }

  private Integer code(String column) {
    String value = sourceFields.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(column))
        .map(Map.Entry::getValue).findFirst().orElse("").trim();
    if (ONISRAccidentParser.isMissing(value) || value.equals("-1") || value.equals("0")) return null;
    return Integer.valueOf(value);
  }
}
