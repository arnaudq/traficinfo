package org.traficinfo.dsfunctions;

import java.util.List;

/** One annual ONISR publication and its corresponding objects in onisrinfo. */
public record ONISRYearInputs(int year, String accidentObject, String participantsObject, String vehiclesObject) {
    private static final List<ONISRYearInputs> CONFIGURED = List.of(
            new ONISRYearInputs(2024, "caract-2024.csv", "usagers-2024.csv", "vehicules-2024.csv"),
            new ONISRYearInputs(2025, "caract-2025.csv", "usagers-2025.csv", "vehicules-2025.csv"));

    /** Explicit selection; uploaded files for other years are not automatically imported. */
    public static List<ONISRYearInputs> configured() {
        return CONFIGURED;
    }
}
