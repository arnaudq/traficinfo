package org.traficinfo.parsers;

import org.traficinfo.parsers.dpt34.DPT34ComptageParser;
import org.traficinfo.parsers.dpt30.DPT30ComptageParser;
import org.traficinfo.parsers.m3m.M3MComptageParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class GeoJsonParser {

    public enum DataSource {
        M3MV2(new M3MComptageParser()),
        DPT34(new DPT34ComptageParser()),
        DPT30(new DPT30ComptageParser());

        private DataSource(ComptageParser parser) {
            this.parser = parser;
        }

        private final ComptageParser parser;

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
//    private static final Set<EnumSet<Comptage.Type>> COMPTAGE_TYPES_FILTERS = new HashSet<>(Arrays.asList(
//      EnumSet.of(Comptage.Type.VEHICULES), EnumSet.of(Comptage.Type.VELOS, Comptage.Type.PIETONS_VELOS)));
//    private static final Set<EnumSet<Comptage.Type>> COMPTAGE_TYPES_FILTERS =
//        Collections.singleton(EnumSet.of(Comptage.Type.VELOS, Comptage.Type.PIETONS_VELOS));
    private static final Set<EnumSet<Comptage.Type>> COMPTAGE_TYPES_FILTERS =
      Collections.singleton(EnumSet.of(Comptage.Type.VEHICULES, Comptage.Type.BICYCLES));
    private static Set<String> PRESERVED_GEOJSON_PROPERTIES = new HashSet<>(Arrays.asList("gid", "code_centrale", "name:q"));

    private static final Logger LOG = LoggerFactory.getLogger(GeoJsonParser.class);


    public static void parseAndMerge(DataSource dataSource, Reader csvReader,
                                     Writer geojsonMergedWriter) throws Exception {
        parseAndMerge(dataSource, csvReader, new FeatureCollection(), geojsonMergedWriter);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader,
                                     Writer geojsonMergedWriter, OSMInfoProvider osmInfoProvider,
                                     Set<Long> waysToRefresh) throws Exception {
        parseAndMerge(dataSource, csvReader, new FeatureCollection(), geojsonMergedWriter,
                osmInfoProvider, waysToRefresh);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader, Reader geojsonReader,
                                     Writer geojsonMergedWriter) throws Exception {
        parseAndMerge(dataSource, csvReader, parseGeoJSON(geojsonReader), geojsonMergedWriter);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader, Reader geojsonReader,
                                     Writer geojsonMergedWriter, OSMInfoProvider osmInfoProvider,
                                     Set<Long> waysToRefresh) throws Exception {
        parseAndMerge(dataSource, csvReader, parseGeoJSON(geojsonReader), geojsonMergedWriter,
                osmInfoProvider, waysToRefresh);
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader,
                                     FeatureCollection geoJsonColl, Writer geojsonMergedWriter)
            throws Exception {
        parseAndMerge(dataSource, csvReader, geoJsonColl, geojsonMergedWriter,
                wayId -> null, new LinkedHashSet<>());
    }

    public static void parseAndMerge(DataSource dataSource, Reader csvReader, FeatureCollection geoJsonColl,
                                     Writer geojsonMergedWriter, OSMInfoProvider osmInfoProvider,
                                     Set<Long> waysToRefresh) throws Exception {

        Properties idToVoiesProps = new Properties();
//        try (FileInputStream fis = new FileInputStream(new File(DATADIR, "idToVoies.properties"))) {
//            idToVoiesProps.load(new InputStreamReader(fis, StandardCharsets.UTF_8));
//        }

        ComptageParser comptageParser = dataSource.getParser();
        List<? extends Comptage> comptages = comptageParser.parseComptages(csvReader, idToVoiesProps);
        applyOsmInfo(comptages, osmInfoProvider, waysToRefresh, Instant.now());
//        writeComptagesIdAndVoie(comptages, new File(OUTDIR,
//          String.format("idToVoies-%s.properties", TIMESTAMP)));

        LOG.info("Nb comptages: {}", comptages.size());

        LOG.info("Nb GeoJson: {}", geoJsonColl.getFeatures().size());

        for (EnumSet<Comptage.Type> types : COMPTAGE_TYPES_FILTERS) {
            FeatureCollection outCol = buildForType(geoJsonColl.getFeatures(), comptages, types);
            LOG.info("Nb filtered GeoJson: {}", outCol.getFeatures().size());

            StringBuilder typesStr = new StringBuilder();
            types.forEach(type -> typesStr.append(type.name()).append('_'));
            typesStr.deleteCharAt(typesStr.length() -1);
            new ObjectMapper().writeValue(geojsonMergedWriter, outCol);
        }
    }

    static void applyOsmInfo(List<? extends Comptage> comptages, OSMInfoProvider osmInfoProvider,
                             Set<Long> waysToRefresh, Instant now) {
        for (Comptage comptage : comptages) {
            Long wayId = comptage.getOsmWayId();
            if (wayId == null || wayId <= 0) {
                continue;
            }
            OSMWayInfo info = osmInfoProvider.get(wayId);
            comptage.setMaximumSpeed(info == null ? -1 : info.getMaximumSpeed());
            if (info == null || info.needsRefresh(now)) {
                waysToRefresh.add(wayId);
            }
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

//    private static void writeComptagesIdAndVoie(List<MMMComptage> comptages, File propertiesFile)
//      throws IOException {
//        Map<String, List<Comptage>> idToComptagesMap = comptages.stream()
//          .collect(Collectors.groupingBy(Comptage::getCodeCentrale));
//        Properties props = new Properties() {
//            @Override
//            public synchronized Enumeration<Object> keys() {
//                return Collections.enumeration(new TreeSet<Object>(super.keySet()));
//            }
//        };
//        for (Map.Entry<String, List<Comptage>> anId : idToComptagesMap.entrySet()) {
//            String streetName = "";
//            for (Comptage comptage : anId.getValue()) {
//                if (comptage.getStreetName() != null) {
//                    streetName = comptage.getStreetName();
//                    break;
//                }
//            }
//            if (!streetName.isEmpty()) {
//                // check that values are consistent for a given id
//                for (Comptage comptage : anId.getValue()) {
//                    if (!streetName.equals(comptage.getStreetName())) {
//                        System.err.printf("Mismatch in nom voie for %s: %s versus %s%n",
//                          comptage.getCodeCentrale(), streetName, comptage.getStreetName());
//                    }
//                }
//            }
//            props.setProperty(anId.getKey(), streetName);
//        }
//        props.store(new FileWriter(propertiesFile), null);
//    }

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
          + (comptage.isBidirectionnelle() ? " \u21C4" : ""));

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
        // use max value for vitesse
        Comptage top = comptages.get(0);
        for (int i = 1; i < comptages.size(); i++) {
            Comptage current = comptages.get(i);
            if (current.getAnnee() != top.getAnnee()) {
                break;
            }
            if (current.getV85Speed() > top.getV85Speed()) {
                top.setV85Speed(current.getV85Speed());
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
