package org.traficinfo.parsers;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** One road accident, independent of the source's vehicle and casualty tables. */
public interface Accident extends GeographicRecord {
  /** Source accident identifier, retained for joins with companion files. */
  String getAccidentId();

  LocalDate getDate();

  /** Local clock time as reported by the source, or null when unknown; no inferred timezone. */
  LocalTime getTime();

  /** Source administrative area identifier; mapped to the department code for France. */
  String getRegionalId();

  /** Municipality identifier, or null when unknown; mapped to the INSEE commune code for France. */
  String getMunicipalityId();

  /** Source address, or null when unknown; not necessarily an official road reference. */
  String getAddress();

  List<? extends AccidentParticipant> getParticipants();
}
