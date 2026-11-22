package org.traficinfo.parsers.dpt76;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.history.Canonicalizer;

import java.util.ArrayList;

import static org.traficinfo.parsers.history.CanonicalValues.*;
import static org.traficinfo.parsers.history.CounterHistory.*;

/**
 * Canonicalizes Seine-Maritime's annual total and any publisher-supplied directional counts.
 * Neither the total nor its components are synthesized from the other values.
 */
public final class DPT76Canonicalizer implements Canonicalizer {
    @Override
    public Observation canonicalize(Comptage record) {
        var source = (DPT76Comptage) record;
        var total = count(source, null, null, nonnegative(source.getMjaTvTcj()), AverageBasis.ANNUAL);
        var counts = new ArrayList<Count>();
        counts.add(total);
        addDirection(counts, "1", source.getTmjaSens1(), total.population());
        addDirection(counts, "2", source.getTmjaSens2(), total.population());
        return annual(countingMode(source.getTypeCompteur()), counts.toArray(Count[]::new));
    }

    private static void addDirection(ArrayList<Count> counts, String channel, Integer average,
                                     Comptage.Type population) {
        if (average == null) return;
        counts.add(new Count(countId(channel, population, Comptage.DirectionType.UNIDIRECTIONAL), population,
                Comptage.DirectionType.UNIDIRECTIONAL, new Direction(channel, null), nonnegative(average),
                AverageBasis.ANNUAL, null, null, null, null, null, null, null));
    }
}
