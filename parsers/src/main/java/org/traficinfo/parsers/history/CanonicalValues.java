package org.traficinfo.parsers.history;

import org.traficinfo.parsers.Comptage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.traficinfo.parsers.history.CounterHistory.*;

/** Common value normalization and count construction, with no datasource-specific branching. */
public final class CanonicalValues {
    private static final Pattern PEAK = Pattern.compile("^([0-9]+(?:[.,][0-9]+)?)(?:\\s*\\(.*\\))?$");

    private CanonicalValues() {}

    /** Wraps counts in a campaign with only year/dataset identity and no known measurement dates. */
    public static Canonicalizer.Observation annual(CountingMode mode, Count... counts) {
        return new Canonicalizer.Observation("", null, null, null, mode, List.of(counts));
    }

    /** Constructs a count using shared legacy peak/speed accessors and no retained coverage. */
    public static Count count(Comptage record, String channel, String label, Integer average, AverageBasis basis) {
        return count(record, channel, label, average, basis, peak(record.getMoyenneHeureDePointeMatin()),
                peakText(record.getMoyenneHeureDePointeMatin(), null, null),
                peak(record.getMoyenneHeureDePointeSoir()),
                peakText(record.getMoyenneHeureDePointeSoir(), null, null), positive(record.getV85Speed()),
                positive(record.getMeanSpeed()), null);
    }

    /** Constructs a count from normalized integer measurements and the available peak text. */
    public static Count count(Comptage record, String channel, String label, Integer average, AverageBasis basis,
                       Integer morning, String morningText, Integer evening, String eveningText,
                              Integer v85, Integer mean, Coverage coverage) {
        var population = record.getType() == null ? Comptage.Type.UNKNOWN : record.getType();
        var directionType = record.getDirectionType();
        var direction = directionType == Comptage.DirectionType.BIDIRECTIONAL || channel == null ? null
                : new Direction(channel, label);
        return new Count(countId(channel, population, directionType), population, directionType,
                direction, average, basis, morning, morningText, evening, eveningText, v85, mean, coverage);
    }

    /** Builds a campaign-scoped measurement ID from channel/coverage and population. */
    public static String countId(String channel, Comptage.Type population, Comptage.DirectionType type) {
        return (channel == null ? type.name() : "channel-" + channel) + ":" + population.name();
    }

    /** Trims descriptive text, treating blank values as unavailable. */
    public static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    /** Normalizes classification text for matching explicit publisher categories. */
    public static String classification(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Maps known permanent/temporary source labels to canonical modes, retaining UNKNOWN otherwise. */
    public static CountingMode countingMode(String type) {
        return switch (classification(type)) {
            case "per", "permanent", "compteur permanent", "compteur permanent vélo",
                 "comptage permanent en section", "comptage permanent de vélos" -> CountingMode.PERMANENT;
            case "tou", "tournant", "temporaire", "ponctuel", "compteur tournant",
                 "compteur ponctuel", "compteur ponctuel vélo", "comptage ponctuel en section",
                 "comptage ponctuel directionnel sur carrefour", "comptage ponctuel de modes doux",
                 "autre type de campagne ponctuelle" -> CountingMode.TEMPORARY;
            default -> CountingMode.UNKNOWN;
        };
    }

    /** Converts legacy integer values to null when a nonpositive value can mean missing data. */
    public static Integer positive(int value) { return value <= 0 ? null : value; }

    /**
     * Rounds a finite nonnegative source value to the nearest integer, with half values rounded up.
     * Preserves measured zero and returns null for unavailable, negative or out-of-range values.
     */
    public static Integer nonnegative(Number value) {
        if (value == null || !Double.isFinite(value.doubleValue()) || value.doubleValue() < 0) return null;
        try {
            return new BigDecimal(value.toString()).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException invalid) {
            return null;
        }
    }

    /** Reads and rounds a numeric hourly peak, optionally followed by a parenthesized interval. */
    public static Integer peak(String value) {
        if (value == null) return null;
        var match = PEAK.matcher(value.trim());
        return match.matches() ? nonnegative(new BigDecimal(match.group(1).replace(',', '.'))) : null;
    }

    /**
     * Preserves nonblank formatted peak text, including text whose numeric value cannot be parsed.
     * If no formatted text is retained, combines the unrounded source value and available hour label.
     */
    public static String peakText(String formatted, Number value, String hour) {
        if (formatted != null && !formatted.isBlank()) return formatted;
        String interval = text(hour);
        if (value == null || !Double.isFinite(value.doubleValue())) return interval;
        String volume = new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        return volume + (interval == null ? "" : " (" + interval + ")");
    }
}
