package org.traficinfo.parsers.dpt30;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.history.Canonicalizer;

import static org.traficinfo.parsers.history.CanonicalValues.*;
import static org.traficinfo.parsers.history.CounterHistory.*;

/** Canonicalizes Gard annual combined-direction counts, preserving nullable and measured-zero averages. */
public final class DPT30Canonicalizer implements Canonicalizer {
    @Override
    public Observation canonicalize(Comptage record) {
        var source = (DPT30Comptage) record;
        return annual(countingMode(source.getTypeCompteur()),
                count(source, null, null, nonnegative(source.getMjaTvTcj()), AverageBasis.ANNUAL));
    }
}
