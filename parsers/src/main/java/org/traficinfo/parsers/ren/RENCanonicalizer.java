package org.traficinfo.parsers.ren;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.history.Canonicalizer;

import static org.traficinfo.parsers.history.CanonicalValues.*;
import static org.traficinfo.parsers.history.CounterHistory.*;

/**
 * Canonicalizes Rennes direction-specific counts, rounding nullable decimal measurements to integers.
 * Removes the parser's default direction suffix from site names; counting mode remains unknown.
 */
public final class RENCanonicalizer implements Canonicalizer {
    @Override
    public Observation canonicalize(Comptage record) {
        var source = (RENComptage) record;
        String channel = text(source.getSens());
        return annual(CountingMode.UNKNOWN, count(source, channel, channel,
                nonnegative(source.getTmjaTv()), AverageBasis.ANNUAL,
                nonnegative(source.getDebitHpmTv()),
                peakText(source.getMoyenneHeureDePointeMatin(), source.getDebitHpmTv(), null),
                nonnegative(source.getDebitHpsTv()),
                peakText(source.getMoyenneHeureDePointeSoir(), source.getDebitHpsTv(), null),
                nonnegative(source.getV85Tv()), nonnegative(source.getVMoyenneTv()), null));
    }

    @Override
    public String displayName(Comptage record) {
        var source = (RENComptage) record;
        if ((source.getStationCode() + " - " + source.getSens()).equals(source.getDisplayName())) {
            return text(source.getStationCode());
        }
        return Canonicalizer.super.displayName(source);
    }
}
