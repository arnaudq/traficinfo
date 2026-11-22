package org.traficinfo.parsers.m3m;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.history.Canonicalizer;

import static org.traficinfo.parsers.history.CanonicalValues.*;
import static org.traficinfo.parsers.history.CounterHistory.*;

/** Canonicalizes MMM annual/channel records, including legacy missing-value and counter-type semantics. */
public final class M3MCanonicalizer implements Canonicalizer {
    @Override
    public Observation canonicalize(Comptage record) {
        var source = (M3MComptage) record;
        if ("ne pas tenir compte".equals(classification(source.getTypeCompteur()))) {
            return annual(CountingMode.DISREGARDED);
        }
        return annual(countingMode(source.getTypeCompteur()), count(source, text(source.getCodeCanal()), null,
                positive(source.getMoyenneJourAnnuelle()), averageBasis(source.getTypeCompteur())));
    }

    private static AverageBasis averageBasis(String type) {
        if (type == null) return AverageBasis.UNKNOWN;
        return switch (type) {
            case "compteur ponctuel", "compteur ponctuel vélo", "compteur tournant" -> AverageBasis.CAMPAIGN;
            case "compteur permanent", "compteur permanent vélo", "compteur feux" -> AverageBasis.ANNUAL;
            default -> AverageBasis.UNKNOWN;
        };
    }
}
