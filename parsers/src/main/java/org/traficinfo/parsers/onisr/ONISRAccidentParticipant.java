package org.traficinfo.parsers.onisr;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.traficinfo.parsers.AccidentParticipant;

import java.util.Objects;

/** Selected usagers fields only; the containing accident supplies the accident ID. */
public final class ONISRAccidentParticipant implements AccidentParticipant {
  private final String participantId;
  private final String vehicleId;
  private final Role role;
  private final InjurySeverity injurySeverity;
  private final Integer birthYear;
  private final Sex sex;

  @JsonCreator
  public ONISRAccidentParticipant(@JsonProperty("participantId") String participantId,
      @JsonProperty("vehicleId") String vehicleId, @JsonProperty("role") Role role,
      @JsonProperty("injurySeverity") InjurySeverity injurySeverity,
      @JsonProperty("birthYear") Integer birthYear, @JsonProperty("sex") Sex sex) {
    if (participantId == null || !participantId.matches("[0-9]+")
        || (vehicleId != null && !vehicleId.matches("[0-9]+"))) {
      throw new IllegalArgumentException("Invalid participant or vehicle ID");
    }
    if (birthYear != null && birthYear <= 0) throw new IllegalArgumentException("Invalid birth year");
    this.participantId = participantId;
    this.vehicleId = vehicleId;
    this.role = Objects.requireNonNull(role);
    this.injurySeverity = Objects.requireNonNull(injurySeverity);
    this.birthYear = birthYear;
    this.sex = Objects.requireNonNull(sex);
  }

  @Override public String getParticipantId() { return participantId; }
  @Override public String getVehicleId() { return vehicleId; }
  @Override public Role getRole() { return role; }
  @Override public InjurySeverity getInjurySeverity() { return injurySeverity; }
  @Override public Integer getBirthYear() { return birthYear; }
  @Override public Sex getSex() { return sex; }
}
