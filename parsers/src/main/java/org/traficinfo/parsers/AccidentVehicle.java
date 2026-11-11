/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
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

/** A vehicle involved in an accident, referenced by its participants. */
public interface AccidentVehicle {
  String getVehicleId();
  Type getType();
  Motorization getMotorization();

  enum Type {
    BICYCLE, ELECTRIC_BICYCLE, MOPED, MOTORCYCLE, SCOOTER, CAR,
    LIGHT_COMMERCIAL_VEHICLE, HEAVY_GOODS_VEHICLE, BUS, COACH, TRAIN, TRAM,
    QUADRICYCLE, THREE_WHEELER, AGRICULTURAL_VEHICLE, SPECIAL_VEHICLE,
    MOTORIZED_PERSONAL_MOBILITY, NON_MOTORIZED_PERSONAL_MOBILITY, OTHER, UNKNOWN
  }

  enum Motorization { COMBUSTION, HYBRID, ELECTRIC, HYDROGEN, HUMAN, OTHER, UNKNOWN }
}
