package org.traficinfo.dsfunctions;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact street-name comparison after common French address formatting is canonicalized. */
final class StreetNameNormalizer {
    private static final Map<String, String> STREET_TYPES = Map.ofEntries(
            Map.entry("AV", "AVENUE"), Map.entry("AVE", "AVENUE"), Map.entry("AVENUE", "AVENUE"),
            Map.entry("BD", "BOULEVARD"), Map.entry("BLVD", "BOULEVARD"), Map.entry("BOUL", "BOULEVARD"),
            Map.entry("BOULEVARD", "BOULEVARD"), Map.entry("R", "RUE"), Map.entry("RUE", "RUE"),
            Map.entry("RTE", "ROUTE"), Map.entry("ROUTE", "ROUTE"),
            Map.entry("CH", "CHEMIN"), Map.entry("CHE", "CHEMIN"), Map.entry("CHEM", "CHEMIN"), Map.entry("CHEMIN", "CHEMIN"),
            Map.entry("IMP", "IMPASSE"), Map.entry("IMPASSE", "IMPASSE"),
            Map.entry("ALL", "ALLEE"), Map.entry("ALLEE", "ALLEE"),
            Map.entry("PL", "PLACE"), Map.entry("PLACE", "PLACE"),
            Map.entry("SQ", "SQUARE"), Map.entry("SQUARE", "SQUARE"),
            Map.entry("CRS", "COURS"), Map.entry("COURS", "COURS"),
            Map.entry("PAS", "PASSAGE"), Map.entry("PASSAGE", "PASSAGE"),
            Map.entry("QU", "QUAI"), Map.entry("QUAI", "QUAI"),
            Map.entry("RPT", "ROND POINT"), Map.entry("ROND POINT", "ROND POINT"),
            Map.entry("SENTE", "SENTE"), Map.entry("SENTIER", "SENTIER"));
    private static final Pattern TRAILING_TYPE = Pattern.compile("^(.+?)\\s*\\(([^()]*)\\)\\s*$");
    private static final Pattern HOUSE_NUMBER = Pattern.compile("^\\d+(?:\\s*(?:BIS|TER|QUATER|[A-Z]))?\\s+(.+)$");

    private StreetNameNormalizer() { }

    static boolean sameName(String source, String osmName) {
        String normalized = normalize(source);
        return !normalized.isEmpty() && normalized.equals(normalize(osmName));
    }

    static String normalize(String name) {
        if (name == null) return "";
        String folded = Normalizer.normalize(name.toUpperCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace("Œ", "OE").replace("Æ", "AE").trim();
        var suffix = TRAILING_TYPE.matcher(folded);
        if (suffix.matches()) {
            String type = STREET_TYPES.get(clean(suffix.group(2)));
            if (type != null) folded = type + " " + suffix.group(1);
        }
        String result = clean(folded);
        var houseNumber = HOUSE_NUMBER.matcher(result);
        if (houseNumber.matches() && beginsWithStreetType(houseNumber.group(1))) result = houseNumber.group(1);
        result = expandStreetType(result);
        return result.replaceAll("\\bSTE\\b", "SAINTE").replaceAll("\\bST\\b", "SAINT");
    }

    private static String clean(String value) {
        return value.replaceAll("[^A-Z0-9]+", " ").trim();
    }

    private static boolean beginsWithStreetType(String value) {
        int space = value.indexOf(' ');
        return value.equals("ROND POINT") || value.startsWith("ROND POINT ")
                || STREET_TYPES.containsKey(space < 0 ? value : value.substring(0, space));
    }

    private static String expandStreetType(String value) {
        int space = value.indexOf(' ');
        String first = space < 0 ? value : value.substring(0, space);
        return STREET_TYPES.getOrDefault(first, first) + (space < 0 ? "" : value.substring(space));
    }
}
