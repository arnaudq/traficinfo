/*
 * This file is part of TraficInfo.
 * Copyright (C) 2024, 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.parsers.dpt34;

import com.opencsv.bean.CsvBindByName;
import org.geojson.GeoJsonObject;
import org.geojson.Point;
import org.traficinfo.parsers.Comptage;

import java.util.Objects;

/** An annual traffic-count record from the Hérault departmental CSV export. */
public class DPT34Comptage implements Comptage {

  private Long osmWayId;

  @CsvBindByName(column = "Identifiant du compteur")
  private String siteId;

  private String codeCentrale;

  @CsvBindByName(column = "Localisation")
  private String streetName;

  @CsvBindByName(column = "Libellé")
  private String altStreetName;

  @CsvBindByName(column = "Commune")
  private String cityName;

  @CsvBindByName(column = "route_id")
  private String routeId;

  @CsvBindByName(column = "Année")
  private int annee;

  @CsvBindByName(column = "Type de compteur")
  private String typeCompteur;

  @CsvBindByName(column = "Sens de la route")
  private int sensRoute;

  @CsvBindByName(column = "Latitude")
  private Double latitude;

  @CsvBindByName(column = "Longitude")
  private Double longitude;

  @CsvBindByName(column = "mja_tv_tcj")
  private Integer mjaTvTcj;

  private String moyenneHeureDePointeMatin;

  private String moyenneHeureDePointeSoir;

  private int v85Speed;

  private int meanSpeed;

  private int maximumSpeed = -1;
  private Boolean bicycleContraflowAllowed;

  private String displayName;

  private Type type = Type.VEHICULES;

  @Override
  public Long getOsmWayId() {
    return osmWayId;
  }

  @Override
  public void setOsmWayId(Long osmWayId) {
    this.osmWayId = osmWayId;
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

  @Override
  public String getRoadReference() {
    return routeId != null && routeId.startsWith("34") ? routeId.substring(2) : routeId;
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

  public int getSensRoute() {
    return sensRoute;
  }

  public void setSensRoute(int sensRoute) {
    this.sensRoute = sensRoute;
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
  public Boolean getBicycleContraflowAllowed() {
    return bicycleContraflowAllowed;
  }

  @Override
  public void setBicycleContraflowAllowed(Boolean bicycleContraflowAllowed) {
    this.bicycleContraflowAllowed = bicycleContraflowAllowed;
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
  public DirectionType getDirectionType() {
    return switch (sensRoute) {
      case 1, 2 -> DirectionType.UNIDIRECTIONAL;
      case 3 -> DirectionType.BIDIRECTIONAL;
      default -> DirectionType.UNKNOWN;
    };
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
    DPT34Comptage that = (DPT34Comptage) o;
    return Objects.equals(osmWayId, that.osmWayId)
        && Objects.equals(siteId, that.siteId)
        && Objects.equals(codeCentrale, that.codeCentrale)
        && Objects.equals(streetName, that.streetName)
        && Objects.equals(altStreetName, that.altStreetName)
        && Objects.equals(cityName, that.cityName)
        && Objects.equals(routeId, that.routeId)
        && annee == that.annee
        && Objects.equals(typeCompteur, that.typeCompteur)
        && sensRoute == that.sensRoute
        && Objects.equals(latitude, that.latitude)
        && Objects.equals(longitude, that.longitude)
        && Objects.equals(mjaTvTcj, that.mjaTvTcj)
        && Objects.equals(moyenneHeureDePointeMatin, that.moyenneHeureDePointeMatin)
        && Objects.equals(moyenneHeureDePointeSoir, that.moyenneHeureDePointeSoir)
        && v85Speed == that.v85Speed
        && meanSpeed == that.meanSpeed
        && maximumSpeed == that.maximumSpeed
        && Objects.equals(bicycleContraflowAllowed, that.bicycleContraflowAllowed)
        && Objects.equals(displayName, that.displayName)
        && Objects.equals(type, that.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(osmWayId, siteId, codeCentrale, streetName, altStreetName, cityName, routeId, annee, typeCompteur, sensRoute, latitude, longitude, mjaTvTcj, moyenneHeureDePointeMatin, moyenneHeureDePointeSoir, v85Speed, meanSpeed, maximumSpeed, bicycleContraflowAllowed, displayName, type);
  }

  @Override
  public String toString() {
    return "DPT34Comptage{codeCentrale='" + codeCentrale + "', annee=" + annee
        + ", mjaTvTcj=" + mjaTvTcj + ", cityName='" + cityName + "'}";
  }
}
