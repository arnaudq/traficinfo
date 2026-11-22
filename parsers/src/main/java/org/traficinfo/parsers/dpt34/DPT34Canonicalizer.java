package org.traficinfo.parsers.dpt34;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.history.Canonicalizer;

import static org.traficinfo.parsers.history.CanonicalValues.*;
import static org.traficinfo.parsers.history.CounterHistory.*;

/** Canonicalizes Hérault annual counts using the source road-direction code as the channel identity. */
public final class DPT34Canonicalizer implements Canonicalizer {
    @Override
    public Observation canonicalize(Comptage record) {
        var source = (DPT34Comptage) record;
        return annual(countingMode(source.getTypeCompteur()), count(source,
                Integer.toString(source.getSensRoute()), null, nonnegative(source.getMjaTvTcj()), AverageBasis.ANNUAL));
    }
}
