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
