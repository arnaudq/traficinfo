package org.traficinfo.parsers;

import org.traficinfo.parsers.dpt34.DPT34Comptage;
import org.traficinfo.parsers.dpt34.DPT34ComptageParser;
import org.traficinfo.parsers.mrn.MRNComptage;
import org.traficinfo.parsers.mrn.MRNComptageParser;
import org.traficinfo.parsers.nim.NIMComptage;
import org.traficinfo.parsers.nim.NIMComptageParser;
import org.traficinfo.parsers.dpt30.DPT30Comptage;
import org.traficinfo.parsers.dpt30.DPT30ComptageParser;
import org.traficinfo.parsers.dpt76.DPT76Comptage;
import org.traficinfo.parsers.dpt76.DPT76ComptageParser;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.traficinfo.parsers.m3m.M3MComptageParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.geojson.Feature;
import org.geojson.Point;
import org.geojson.FeatureCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;

public class GeoJsonParser {

    public enum DataSource {
        M3MV2("MMM", new M3MComptageParser(), M3MComptage.class),
        DPT34("DPT34", new DPT34ComptageParser(), DPT34Comptage.class),
        DPT30("DPT30", new DPT30ComptageParser(), DPT30Comptage.class),
        DPT76("DPT76", new DPT76ComptageParser(), DPT76Comptage.class),
        MRN("MRN", new MRNComptageParser(), MRNComptage.class),
        NIM("NIM", new NIMComptageParser(), NIMComptage.class);

        private DataSource(String id, ComptageParser parser, Class<? extends Comptage> dsComptageClass) {
            this.id = id;
            this.parser = parser;
            this.dsComptageClass = dsComptageClass;
        }

        private final String id;
        private final ComptageParser parser;
        private final Class<? extends Comptage> dsComptageClass;

        public String getId() {
            return id;
        }

        /** Resolves published source IDs or enum names, without case sensitivity. */
        public static DataSource fromId(String identifier) {
            return Arrays.stream(values())
                .filter(source -> source.id.equalsIgnoreCase(identifier)
                    || source.name().equalsIgnoreCase(identifier))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                    "Unknown data source: " + identifier + "; expected "
                        + Arrays.stream(values()).map(DataSource::getId).collect(Collectors.joining(", "))));
        }

        public Class<? extends Comptage> getDsComptageClass() {
            return dsComptageClass;
        }

        public ComptageParser getParser() {
            return parser;
        }
    }

    private static final String UMAP_OPTIONS = "_umap_options";
    /**
     * Maximum number of comptage (history) to show
     */
    private static final int NB_COMPTAGES_HIST = 3;
    /**
     * Minimum average vehicules per day to appear on the map (0 to show all).
     */
    private static final int MIN_AVERAGE_PER_DAY = 0;
    /**
     * Maximum average vehicules per day to appear on the map (mostly to filter out bogus counters).
     */
    private static final int MAX_AVERAGE_PERDAY = 300000;
    /**
     * Filter by City (empty set to get all cities).
     */
//    private static final Set<String> CITIES_FILTER = new HashSet<>(Arrays.asList("Montpellier"));
    private static final Set<String> CITIES_FILTER = new HashSet<>();

    /**
     * Number of years to look back from the current year, including both endpoints.
     */
    private static final int NB_YEARS = 10;
    //private static Set<String> GID_CSVFILTER = new HashSet<>(Arrays.asList("4465"));
    private static final Set<String> GID_CSVFILTER = new HashSet<>();
    private static Set<String> PRESERVED_GEOJSON_PROPERTIES = new HashSet<>(Arrays.asList("gid", "code_centrale", "name:q"));

    private static final Logger LOG = LoggerFactory.getLogger(GeoJsonParser.class);


    public static void parseAndMerge(DataSource dataSource, Reader csvReader,
                                     Writer geojsonMergedWriter) throws Exception {
        parseAndMerge(dataSource, csvReader, new FeatureCollection(), geojsonMergedWriter);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader,
                                     Writer geojsonMergedWriter, OSMInfoProvider osmInfoProvider) throws Exception {
        parseAndMerge(dataSource, csvReader, new FeatureCollection(), geojsonMergedWriter,
                osmInfoProvider);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader, Reader geojsonReader,
                                     Writer geojsonMergedWriter) throws Exception {
        parseAndMerge(dataSource, csvReader, parseGeoJSON(geojsonReader), geojsonMergedWriter);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader, Reader geojsonReader,
                                     Writer geojsonMergedWriter, OSMInfoProvider osmInfoProvider) throws Exception {
        parseAndMerge(dataSource, csvReader, parseGeoJSON(geojsonReader), geojsonMergedWriter,
                osmInfoProvider);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader,
                                     FeatureCollection geoJsonColl, Writer geojsonMergedWriter)
            throws Exception {
        parseAndMerge(dataSource, csvReader, geoJsonColl, geojsonMergedWriter,
                wayId -> null);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader, FeatureCollection geoJsonColl,
                                     Writer geojsonMergedWriter, OSMInfoProvider osmInfoProvider) throws Exception {

        ComptageParser comptageParser = dataSource.getParser();
        List<? extends Comptage> comptages = comptageParser.parseComptages(csvReader);
        writeGeoJson(dataSource, comptages, geoJsonColl, geojsonMergedWriter, osmInfoProvider);
    }

    /**
     * Enriches and renders already parsed records using the existing filtering and aggregation rules.
     * An empty feature collection uses the records' geometry. Records and supplied features may be modified.
     */
    public static void writeGeoJson(DataSource dataSource, List<? extends Comptage> comptages,
                                    FeatureCollection geoJsonColl, Writer geojsonMergedWriter,
                                    OSMInfoProvider osmInfoProvider) throws IOException {
        writeGeoJson(dataSource, comptages, geoJsonColl, geojsonMergedWriter, osmInfoProvider,
                Comptage.Type.VEHICULES);
    }

    /** Writes one population; the default overload exports motor vehicles. */
    public static void writeGeoJson(DataSource dataSource, List<? extends Comptage> comptages,
                                    FeatureCollection geoJsonColl, Writer geojsonMergedWriter,
                                    OSMInfoProvider osmInfoProvider, Comptage.Type type) throws IOException {
        if (type != Comptage.Type.VEHICULES && type != Comptage.Type.BICYCLES) {
            throw new IllegalArgumentException("Unsupported GeoJSON population: " + type);
        }
        List<? extends Comptage> selected = comptages.stream()
                .filter(comptage -> comptage.getType() == type).collect(Collectors.toList());
        logSpeedCoverage(dataSource, "input records " + type, selected.size(),
                selected.stream().filter(comptage -> comptage.getV85Speed() > 0).count(),
                selected.stream().filter(comptage -> comptage.getMeanSpeed() > 0).count());
        applyOsmInfo(selected, osmInfoProvider);
        FeatureCollection outCol = buildForType(geoJsonColl.getFeatures(), selected, EnumSet.of(type));
        outCol.getFeatures().forEach(feature -> feature.setProperty("data_source", dataSource.getId()));
        logSpeedCoverage(dataSource, "GeoJSON features " + type, outCol.getFeatures().size(),
                outCol.getFeatures().stream().filter(feature -> feature.getProperties().containsKey("v85_speed")).count(),
                outCol.getFeatures().stream().filter(feature -> feature.getProperties().containsKey("mean_speed")).count());
        new ObjectMapper().writeValue(geojsonMergedWriter, outCol);
    }

    private static void logSpeedCoverage(DataSource dataSource, String stage, int total,
                                         long v85Count, long meanCount) {
        LOG.info("{} speed coverage ({}): total={}, v85={} ({}%), mean={} ({}%)",
                dataSource.getId(), stage, total,
                v85Count, String.format(Locale.ROOT, "%.1f", total == 0 ? 0.0 : 100.0 * v85Count / total),
                meanCount, String.format(Locale.ROOT, "%.1f", total == 0 ? 0.0 : 100.0 * meanCount / total));
    }

    static void applyOsmInfo(List<? extends Comptage> comptages, OSMInfoProvider osmInfoProvider) {
        List<Comptage> chronological = new ArrayList<>(comptages);
        chronological.sort(Comparator.comparingInt(Comptage::getAnnee));
        for (Comptage comptage : chronological) {
            Long wayId = comptage.getOsmWayId();
            if (wayId == null || wayId <= 0) {
                wayId = osmInfoProvider.getWayId(comptage.getCodeCentrale(),
                        comptage.getGeometry() instanceof Point point ? point : null, comptage.getRoadReference());
                comptage.setOsmWayId(wayId);
            }
            if (wayId == null || wayId <= 0) {
                continue;
            }
            OSMWayInfo info = osmInfoProvider.getWayInfo(wayId);
            comptage.setMaximumSpeed(info == null ? -1 : info.getMaximumSpeed());
        }
    }

    static FeatureCollection buildForType(final List<Feature> unfilteredFeatures, final List<? extends Comptage> comptages,
                                          final EnumSet<Comptage.Type> typeFilter) {
        int currentYear = Year.now().getValue();
        Map<String, List<Comptage>> idToComptagesMap = comptages.stream()
          .filter(comptage -> comptage.getAnnee() >= currentYear - NB_YEARS
                  && comptage.getAnnee() <= currentYear)
          .filter(comptage -> GID_CSVFILTER.isEmpty() || GID_CSVFILTER.contains(comptage.getCodeCentrale()))
          .filter (comptage -> CITIES_FILTER.isEmpty() || CITIES_FILTER.contains(comptage.getCityName()))
          .filter(comptage -> typeFilter.contains(comptage.getType()))
          .filter(GeoJsonParser::isInRange)
          .collect(Collectors.groupingBy(Comptage::getCodeCentrale));
        LOG.info("Nb comptages for type {} after filtering: {}", typeFilter, idToComptagesMap.size());

        List<Feature> features;
        if (unfilteredFeatures.isEmpty()) {
            features = new ArrayList<>();
            idToComptagesMap.keySet().stream().sorted().forEach(codeCentrale ->
                idToComptagesMap.get(codeCentrale).stream()
                    .filter(comptage -> comptage.getGeometry() != null)
                    .max(Comparator.comparingInt(Comptage::getAnnee)
                            .thenComparingInt(Comptage::getMoyenneJourAnnuelle))
                    .ifPresent(comptage -> {
                        Feature feature = new Feature();
                        feature.setGeometry(comptage.getGeometry());
                        feature.setProperty("code_centrale", codeCentrale);
                        features.add(feature);
                    }));
        } else {
            // Reused features are cleaned of properties from previous types.
            Set<String> uniqueIds = new HashSet<>();
            features = unfilteredFeatures.stream()
                .filter(feature -> {
                    String codeCentrale = getFeatureCodeCentrale(feature);
                    return uniqueIds.add(codeCentrale) && idToComptagesMap.containsKey(codeCentrale);
                })
                .map(GeoJsonParser::cleanupProperties)
                .collect(Collectors.toList());
        }

        features.forEach(feature -> {
            List<? extends Comptage> lc = idToComptagesMap.get(getFeatureCodeCentrale(feature));
            merge(feature, lc, NB_COMPTAGES_HIST);
        });
        LOG.info("Nb filtered GeoJson: {}", features.size());

        FeatureCollection col = new FeatureCollection();
        col.setFeatures(features);

        return col;
    }

    private static boolean isInRange(Comptage comptage) {
        return comptage.getMoyenneJourAnnuelle() > MIN_AVERAGE_PER_DAY
          && comptage.getMoyenneJourAnnuelle() < MAX_AVERAGE_PERDAY;
    }

    /**
     * Starting with 202210 data set, gid is no longer present and replaced by code_centrale
     * @param feature
     * @return
     */
    private static String getFeatureCodeCentrale(Feature feature) {
        if (feature.getProperty("code_centrale") != null) {
            return feature.getProperty("code_centrale").toString();
        } else if (feature.getProperty("gid") != null) {
            return feature.getProperty("gid").toString();
        } else {
            LOG.warn("No code central found for {}", feature);
            return "### BOGUS ###";
        }
    }

    static FeatureCollection parseGeoJSON(Reader geojsonReader) throws IOException {
        return new ObjectMapper().readValue(geojsonReader, FeatureCollection.class);
    }

    private static Feature cleanupProperties(Feature feature) {
        Map<String, Object> props = feature.getProperties();
        props.entrySet().removeIf(prop -> !PRESERVED_GEOJSON_PROPERTIES.contains(prop.getKey()));
        return feature;
    }

    /**
     * Add MMComptage specific properties to the feature.
     * @param feature feature to augment.
     * @param comptages list of MMComptage.
     * @param nbComptageHist max number of historic values to show from comptages.
     */
    private static void merge(Feature feature, List<? extends Comptage> comptages, int nbComptageHist) {
        // Sort by Year decreasing + average speed
        sortComptages(comptages);

        Comptage comptage = comptages.get(0);
        String featureOrigName = feature.getProperty("name");

        // Set name as moyenne
        // Right arrow over left arrow https://www.fileformat.info/info/unicode/char/21c4/index.htm
        feature.setProperty("name", comptage.getMoyenneJourAnnuelle()
          + (comptage.getDirectionType() == Comptage.DirectionType.BIDIRECTIONAL ? " \u21C4" : ""));

        // Set description as name from displayname or feature if not available
        String description;
        if (comptage.getDisplayName() != null && !comptage.getDisplayName().isEmpty()) {
            description = comptage.getDisplayName();
        } else {
            if (featureOrigName != null && !featureOrigName.isEmpty()) {
                LOG.warn("Using featureName en lieu of nomvoie for {} : {}\n",
                  comptage.getCodeCentrale(), featureOrigName);
                description = featureOrigName;
            } else {
                LOG.warn("No featureName or nomvoie for {}\n", comptage.getCodeCentrale());
                description = comptage.getCodeCentrale();
            }
        }
        feature.setProperty("description", description);

        feature.setProperty("annee", comptage.getAnnee());
        feature.setProperty("type", comptage.getType().name());
        feature.setProperty("moyenne_jour_annuelle", comptage.getMoyenneJourAnnuelle());
        setPropertyIfPresent(feature, "moyenne_pointe_matin", comptage.getMoyenneHeureDePointeMatin());
        setPropertyIfPresent(feature, "moyenne_pointe_soir", comptage.getMoyenneHeureDePointeSoir());
        setPropertyIfPresent(feature, "v85_speed", comptage.getV85Speed());
        setPropertyIfPresent(feature, "mean_speed", comptage.getMeanSpeed());
        setPropertyIfPresent(feature, "maximum_speed", comptage.getMaximumSpeed());

        feature.setProperty("moyenne_annuelle_history", buildHistory(comptages, nbComptageHist));

        GeoJsonUmapOptions umapOptions = new GeoJsonUmapOptions();
        if (comptage.getMoyenneJourAnnuelle() <= 1000) {
            umapOptions.setColor(GeoJsonUmapOptions.Color.Green);
        } else if (comptage.getMoyenneJourAnnuelle() <= 5000) {
            umapOptions.setColor(GeoJsonUmapOptions.Color.Blue);
        } else {
            umapOptions.setColor(GeoJsonUmapOptions.Color.Red);
        }
        feature.setProperty(UMAP_OPTIONS, umapOptions);
    }

    static void sortComptages(List<? extends Comptage> comptages) {
        // Sort by year decending and average decending
        comptages.sort(new Comparator<Comptage>() {
            @Override
            public int compare(Comptage c1, Comptage c2) {
                if (c1.getAnnee() == c2.getAnnee()) {
                    return c1.getMoyenneJourAnnuelle() > c2.getMoyenneJourAnnuelle() ? -1 : 1;
                } else {
                    return c1.getAnnee() > c2.getAnnee() ? -1 : 1;
                }
            }
        });
        // Use maximum V85 and mean speeds across records for the latest year.
        Comptage top = comptages.get(0);
        for (int i = 1; i < comptages.size(); i++) {
            Comptage current = comptages.get(i);
            if (current.getAnnee() != top.getAnnee()) {
                break;
            }
            if (current.getV85Speed() > top.getV85Speed()) {
                top.setV85Speed(current.getV85Speed());
            }
            if (current.getMeanSpeed() > top.getMeanSpeed()) {
                top.setMeanSpeed(current.getMeanSpeed());
            }
        }
    }

    static final String buildHistory(List<? extends Comptage> comptages, int nbComptageHist) {
        StringBuilder builder = new StringBuilder();
        int nbShown = 0;
        int currentYear = -1;
        for (int i = 0; i < comptages.size() && nbShown < nbComptageHist; i++) {
            Comptage comptage = comptages.get(i);
            if (comptage.getAnnee() != currentYear) {
                builder.append(comptage.getMoyenneJourAnnuelle()).append(" (").append(comptage.getAnnee()).append(") ");
                currentYear = comptage.getAnnee();
                nbShown++;
            }
        }
        return builder.toString();
    }

    private static void setPropertyIfPresent(Feature feature, String property, int value) {
        if (value > 0) {
            feature.setProperty(property, Integer.toString(value));
        }
    }

    private static void setPropertyIfPresent(Feature feature, String property, String value) {
        if (value != null && !value.isEmpty()) {
            feature.setProperty(property, value);
        }
    }
}
