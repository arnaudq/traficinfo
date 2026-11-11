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

package org.traficinfo.parsers;

public interface Comptage extends GeographicRecord {

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

  /** Cached OSM bicycle contraflow exception, or null when way information is unavailable. */
  Boolean getBicycleContraflowAllowed();

  void setBicycleContraflowAllowed(Boolean bicycleContraflowAllowed);

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
    PIETONS_VELOS,
    UNKNOWN;
  }
}
