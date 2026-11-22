package org.traficinfo.parsers.mrn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.history.Canonicalizer;

import java.util.List;

import static org.traficinfo.parsers.history.CanonicalValues.*;
import static org.traficinfo.parsers.history.CounterHistory.*;

/** Canonicalizes Rouen counts, retaining campaign distinctions from the source's type/details descriptors. */
public final class MRNCanonicalizer implements Canonicalizer {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Observation canonicalize(Comptage record) {
        var source = (MRNComptage) record;
        String label = text(source.getCampaignDetails());
        String discriminator = JSON.createArrayNode().add(text(source.getCampaignType())).add(label).toString();
        if (label == null) label = text(source.getCampaignType());
        String channel = source.getDirectionType() == Comptage.DirectionType.UNIDIRECTIONAL
                ? text(source.getOrientation()) : null;
        var count = count(source, channel, channel, nonnegative(source.getTvTmja()), AverageBasis.ANNUAL,
                nonnegative(source.getTvHpm()),
                peakText(source.getMoyenneHeureDePointeMatin(), source.getTvHpm(), source.getTvHpmHour()),
                nonnegative(source.getTvHps()),
                peakText(source.getMoyenneHeureDePointeSoir(), source.getTvHps(), source.getTvHpsHour()),
                positive(source.getV85Speed()), positive(source.getMeanSpeed()), null);
        return new Observation(discriminator, label, null, null,
                countingMode(source.getCampaignType()), List.of(count));
    }
}
