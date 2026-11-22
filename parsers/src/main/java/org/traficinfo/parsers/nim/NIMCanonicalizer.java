package org.traficinfo.parsers.nim;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.history.Canonicalizer;

import java.util.List;

import static org.traficinfo.parsers.history.CanonicalValues.*;
import static org.traficinfo.parsers.history.CounterHistory.*;

/**
 * Canonicalizes Nîmes permanent site/year counts with their observed-day/hour coverage.
 * Daily averages describe observed complete days; partial coverage never changes permanent counting mode.
 */
public final class NIMCanonicalizer implements Canonicalizer {
    @Override
    public Observation canonicalize(Comptage record) {
        var source = (NIMComptage) record;
        String start = text(source.getFirstDate());
        String end = text(source.getLastDate());
        var coverage = new Coverage(start, end, source.getObservedDays(), source.getCompleteDays(),
                source.getObservedHours(), source.getConflictingHours());
        var count = count(source, null, null, nonnegative(source.getDailyAverage()), AverageBasis.OBSERVED_COMPLETE_DAYS,
                peak(source.getMoyenneHeureDePointeMatin()),
                peakText(source.getMoyenneHeureDePointeMatin(), null, null),
                peak(source.getMoyenneHeureDePointeSoir()),
                peakText(source.getMoyenneHeureDePointeSoir(), null, null),
                positive(source.getV85Speed()), positive(source.getMeanSpeed()), coverage);
        return new Observation("", null, start, end, CountingMode.PERMANENT, List.of(count));
    }
}
