package org.traficinfo.parsers;

/** A person involved in an accident, including uninjured people. */
public interface AccidentParticipant {
  String getParticipantId();
  /** Vehicle reference; for a pedestrian, the vehicle that struck them. */
  String getVehicleId();
  Role getRole();
  InjurySeverity getInjurySeverity();
  Integer getBirthYear();
  Sex getSex();

  enum Role { DRIVER, PASSENGER, PEDESTRIAN, UNKNOWN }
  enum InjurySeverity { UNINJURED, KILLED, HOSPITALIZED, LIGHTLY_INJURED, UNKNOWN }
  enum Sex { MALE, FEMALE, UNKNOWN }
}
