package org.traficinfo.parsers.onisr;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.traficinfo.parsers.AccidentVehicle;

import java.util.Objects;

/** Selected BAAC vehicle fields, without a raw metadata map. */
public final class ONISRAccidentVehicle implements AccidentVehicle {
  private final String vehicleId;
  private final Integer categoryCode;
  private final Motorization motorization;

  @JsonCreator
  public ONISRAccidentVehicle(@JsonProperty("vehicleId") String vehicleId,
      @JsonProperty("categoryCode") Integer categoryCode, @JsonProperty("motorization") Motorization motorization) {
    if (vehicleId == null || !vehicleId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid vehicle ID");
    if (categoryCode != null && categoryCode <= 0) throw new IllegalArgumentException("Invalid category code");
    this.vehicleId = vehicleId;
    this.categoryCode = categoryCode;
    this.motorization = Objects.requireNonNull(motorization);
  }

  @Override public String getVehicleId() { return vehicleId; }
  public Integer getCategoryCode() { return categoryCode; }
  @Override public Motorization getMotorization() { return motorization; }

  /** Derived from the exact BAAC category; preserved codes remain available for finer distinctions. */
  @Override
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  public Type getType() {
    if (categoryCode == null) return Type.UNKNOWN;
    return switch (categoryCode) {
      case 1 -> Type.BICYCLE;
      case 80 -> Type.ELECTRIC_BICYCLE;
      case 2 -> Type.MOPED;
      case 31, 33 -> Type.MOTORCYCLE;
      case 30, 32, 34 -> Type.SCOOTER;
      case 7 -> Type.CAR;
      case 10 -> Type.LIGHT_COMMERCIAL_VEHICLE;
      case 13, 14, 15, 16, 17 -> Type.HEAVY_GOODS_VEHICLE;
      case 37 -> Type.BUS;
      case 38 -> Type.COACH;
      case 39 -> Type.TRAIN;
      case 40 -> Type.TRAM;
      case 3, 35, 36 -> Type.QUADRICYCLE;
      case 41, 42, 43 -> Type.THREE_WHEELER;
      case 21 -> Type.AGRICULTURAL_VEHICLE;
      case 20 -> Type.SPECIAL_VEHICLE;
      case 50 -> Type.MOTORIZED_PERSONAL_MOBILITY;
      case 60 -> Type.NON_MOTORIZED_PERSONAL_MOBILITY;
      case 99 -> Type.OTHER;
      default -> Type.UNKNOWN;
    };
  }
}
