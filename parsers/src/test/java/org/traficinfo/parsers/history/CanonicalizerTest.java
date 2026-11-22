package org.traficinfo.parsers.history;

import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.dpt30.DPT30Comptage;
import org.traficinfo.parsers.dpt34.DPT34Comptage;
import org.traficinfo.parsers.dpt76.DPT76Comptage;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.traficinfo.parsers.mrn.MRNComptage;
import org.traficinfo.parsers.nim.NIMComptage;
import org.traficinfo.parsers.ren.RENComptage;

import static org.junit.Assert.*;

public class CanonicalizerTest {
    @Test
    public void mmmRecordsExplicitlyMarkedUnusableAreDisregarded() {
        var source = new M3MComptage();
        source.setTypeCompteur(" NE PAS TENIR COMPTE ");
        var observation = GeoJsonParser.DataSource.M3MV2.getCanonicalizer().canonicalize(source);
        assertEquals(CounterHistory.CountingMode.DISREGARDED, observation.countingMode());
        assertTrue(observation.counts().isEmpty());
        assertEquals(" NE PAS TENIR COMPTE ", source.getTypeCompteur());
    }

    @Test
    public void everyRegisteredCanonicalizerAcceptsItsDatasourceRecordAndRejectsOtherRecordTypes() throws Exception {
        for (var source : GeoJsonParser.DataSource.values()) {
            var record = source.getDsComptageClass().getConstructor().newInstance();
            record.setCodeCentrale("001");
            record.setAnnee(2025);
            record.setMoyenneJourAnnuelle(123);
            var result = source.getCanonicalizer().canonicalize(record);
            assertFalse(source.getId(), result.counts().isEmpty());
            assertEquals(source.getId(), 123, result.counts().get(0).dailyAverage().intValue());
            assertNotNull(source.getId(), result.countingMode());
            Comptage wrong = source == GeoJsonParser.DataSource.NIM ? new M3MComptage() : new NIMComptage();
            assertThrows(source.getId(), ClassCastException.class,
                    () -> source.getCanonicalizer().canonicalize(wrong));
        }
    }

    @Test
    public void renCanonicalizerNormalizesSiteNameAndRoundsMeasurementsWhileKeepingPeakText() {
        var source = new RENComptage();
        source.setStationCode("001");
        source.setSens("Nord-Sud / Ouest-Est");
        source.setDisplayName("001 - Nord-Sud / Ouest-Est");
        source.setTmjaTv(123.75);
        source.setV85Tv(0.0);
        source.setVMoyenneTv(29.25);
        source.setDebitHpmTv(123.5);
        source.setDebitHpsTv(0.0);
        var canonicalizer = GeoJsonParser.DataSource.REN.getCanonicalizer();
        assertEquals("001", canonicalizer.displayName(source));
        source.setDisplayName("Custom site");
        assertEquals("Custom site", canonicalizer.displayName(source));
        var count = canonicalizer.canonicalize(source).counts().get(0);
        assertEquals(Integer.valueOf(124), count.dailyAverage());
        assertEquals(Integer.valueOf(0), count.v85Speed());
        assertEquals(Integer.valueOf(29), count.meanSpeed());
        assertEquals(Integer.valueOf(124), count.morningPeakAverage());
        assertEquals("123.5", count.morningPeakText());
        assertEquals(Integer.valueOf(0), count.eveningPeakAverage());
        assertEquals("0", count.eveningPeakText());
        assertEquals(123.75, source.getTmjaTv(), 0);
    }

    @Test
    public void preservesFormattedPeakTextEvenWhenItsNumericValueCannotBeParsed() {
        var source = new M3MComptage();
        String morning = " 313,5 (7h15 - 8h15) ";
        String evening = "inconnu (17h - 18h)";
        source.setMoyenneHeureDePointeMatin(morning);
        source.setMoyenneHeureDePointeSoir(evening);
        var count = GeoJsonParser.DataSource.M3MV2.getCanonicalizer().canonicalize(source).counts().get(0);
        assertEquals(Integer.valueOf(314), count.morningPeakAverage());
        assertEquals(morning, count.morningPeakText());
        assertNull(count.eveningPeakAverage());
        assertEquals(evening, count.eveningPeakText());
    }

    @Test
    public void preservesMrnPeakHoursFromSeparateSourceFieldsIncludingZeroAndMissingVolumes() {
        var source = new MRNComptage();
        source.setTvHpm(0);
        source.setTvHpmHour("07:00 - 08:00");
        source.setTvHpsHour("17:00 - 18:00");
        var count = GeoJsonParser.DataSource.MRN.getCanonicalizer().canonicalize(source).counts().get(0);
        assertEquals(Integer.valueOf(0), count.morningPeakAverage());
        assertEquals("0 (07:00 - 08:00)", count.morningPeakText());
        assertNull(count.eveningPeakAverage());
        assertEquals("17:00 - 18:00", count.eveningPeakText());
        source.setMoyenneHeureDePointeMatin("0 (7h - 8h)");
        count = GeoJsonParser.DataSource.MRN.getCanonicalizer().canonicalize(source).counts().get(0);
        assertEquals("0 (7h - 8h)", count.morningPeakText());
    }

    @Test
    public void integerRoundingPreservesNullAndZeroAndRejectsInvalidOrOverflowingValues() {
        assertEquals(Integer.valueOf(1), CanonicalValues.nonnegative(1.49));
        assertEquals(Integer.valueOf(2), CanonicalValues.nonnegative(1.5));
        assertEquals(Integer.valueOf(0), CanonicalValues.nonnegative(0.0));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), CanonicalValues.nonnegative(Integer.MAX_VALUE));
        assertNull(CanonicalValues.nonnegative(null));
        assertNull(CanonicalValues.nonnegative(-1));
        assertNull(CanonicalValues.nonnegative(Double.NaN));
        assertNull(CanonicalValues.nonnegative(Double.POSITIVE_INFINITY));
        assertNull(CanonicalValues.nonnegative((long) Integer.MAX_VALUE + 1));
        assertNull(CanonicalValues.peak("2147483648 (7h - 8h)"));
    }

    @Test
    public void normalizesCountingModesWithoutInferringFromSamplingDuration() {
        var mmm = new M3MComptage();
        checkModes(GeoJsonParser.DataSource.M3MV2.getCanonicalizer(), mmm, mmm::setTypeCompteur, "compteur permanent vélo", "compteur ponctuel vélo");
        var dpt30 = new DPT30Comptage();
        checkModes(GeoJsonParser.DataSource.DPT30.getCanonicalizer(), dpt30, dpt30::setTypeCompteur, "Permanent", "Tournant");
        var dpt34 = new DPT34Comptage();
        checkModes(GeoJsonParser.DataSource.DPT34.getCanonicalizer(), dpt34, dpt34::setTypeCompteur, "Per", "Tou");
        var dpt76 = new DPT76Comptage();
        checkModes(GeoJsonParser.DataSource.DPT76.getCanonicalizer(), dpt76, dpt76::setTypeCompteur, "Permanent", "Temporaire");
        var mrn = new MRNComptage();
        checkModes(GeoJsonParser.DataSource.MRN.getCanonicalizer(), mrn, mrn::setCampaignType, "Comptage permanent en section", "Comptage ponctuel en section");
        mrn.setCampaignType("Comptage permanent de vélos");
        assertEquals(CounterHistory.CountingMode.PERMANENT, GeoJsonParser.DataSource.MRN.getCanonicalizer().canonicalize(mrn).countingMode());
        var nim = new NIMComptage();
        assertEquals(CounterHistory.CountingMode.PERMANENT, GeoJsonParser.DataSource.NIM.getCanonicalizer().canonicalize(nim).countingMode());
        nim.setCompleteDays(365);
        assertEquals(CounterHistory.CountingMode.PERMANENT, GeoJsonParser.DataSource.NIM.getCanonicalizer().canonicalize(nim).countingMode());
        var ren = new RENComptage();
        ren.setNbSemaines(52.0);
        assertEquals(CounterHistory.CountingMode.UNKNOWN, GeoJsonParser.DataSource.REN.getCanonicalizer().canonicalize(ren).countingMode());
    }

    private static void checkModes(Canonicalizer canonicalizer, Comptage count, java.util.function.Consumer<String> setType,
                                   String permanent, String temporary) {
        setType.accept(permanent);
        assertEquals(CounterHistory.CountingMode.PERMANENT, canonicalizer.canonicalize(count).countingMode());
        setType.accept(temporary);
        assertEquals(CounterHistory.CountingMode.TEMPORARY, canonicalizer.canonicalize(count).countingMode());
        setType.accept("unrecognized");
        assertEquals(CounterHistory.CountingMode.UNKNOWN, canonicalizer.canonicalize(count).countingMode());
        setType.accept(null);
        assertEquals(CounterHistory.CountingMode.UNKNOWN, canonicalizer.canonicalize(count).countingMode());
    }

}
