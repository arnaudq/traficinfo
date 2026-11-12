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
            Map.entry("MONTEE", "MONTEE"),
            Map.entry("QU", "QUAI"), Map.entry("QUAI", "QUAI"),
            Map.entry("RPT", "ROND POINT"), Map.entry("ROND POINT", "ROND POINT"),
            Map.entry("SENTE", "SENTE"), Map.entry("SENTIER", "SENTIER"));
    private static final Pattern TRAILING_TYPE = Pattern.compile("^(.+?)\\s*\\(([^()]*)\\)\\s*$");
    private static final Pattern TYPE_WITH_PREPOSITION = Pattern.compile(
            "^(ROND POINT|[A-Z]+)( (?:DE(?: LA| L)?|DU|DES|D|AU|AUX|A LA|A L))?$");
    private static final Pattern HOUSE_NUMBER = Pattern.compile("^\\d+(?:\\s*(?:BIS|TER|QUATER|[A-Z]))?\\s+(.+)$");
    private static final Pattern ROAD_REFERENCE = Pattern.compile("^[ADMN]\\s*\\d+(?:\\s*[A-Z](?:\\s*\\d+)?)?$");

    private StreetNameNormalizer() { }

    static boolean sameName(String source, String osmName) {
        String normalized = normalize(source);
        return !normalized.isEmpty() && normalized.equals(normalize(osmName));
    }

    static boolean isRoadReference(String name) {
        return ROAD_REFERENCE.matcher(normalize(name)).matches();
    }

    static String normalize(String name) {
        if (name == null) return "";
        String folded = Normalizer.normalize(name.toUpperCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace("Œ", "OE").replace("Æ", "AE").trim();
        var suffix = TRAILING_TYPE.matcher(folded);
        if (suffix.matches()) {
            var trailingType = TYPE_WITH_PREPOSITION.matcher(clean(suffix.group(2)));
            if (trailingType.matches()) {
                String type = STREET_TYPES.get(trailingType.group(1));
                if (type != null) {
                    // Keep the preposition with the type: CARENAGE (QUAI DU) -> QUAI DU CARENAGE.
                    folded = type + (trailingType.group(2) == null ? "" : trailingType.group(2))
                            + " " + suffix.group(1);
                }
            }
        }
        String result = clean(folded);
        result = result.replaceFirst("^(?:ROUTE )?DEPARTEMENTALE (?=[0-9])", "D");
        result = result.replaceFirst("^R\\s*D\\s*(?=[0-9])", "D");
        // A street-name column can contain a numbered road, e.g. D210a versus D 210 A.
        if (ROAD_REFERENCE.matcher(result).matches()) {
            return result.replace(" ", "").replaceAll("([A-Z])0+(?=[0-9])", "$1");
        }
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
