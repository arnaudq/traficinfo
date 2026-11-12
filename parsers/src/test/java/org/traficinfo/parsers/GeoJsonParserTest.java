package org.traficinfo.parsers;

import org.traficinfo.parsers.m3m.M3MComptage;
import org.traficinfo.parsers.mrn.MRNComptage;
import org.traficinfo.parsers.dpt34.DPT34ComptageParser;

import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.Point;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class GeoJsonParserTest extends ParserBaseTest {

    @Test
    public void testMmmCounterTypesInSeparateGeoJson() throws Exception {
        int year = Year.now().getValue();
        String csv = "code_centrale,annee,type_compteur,mja_jo\n"
                + "146966," + year + ",compteur permanent vélo,500\n"
                + "146966," + (year - 1) + ",compteur permanent vélo,900\n"
                + "472," + year + ",compteur permanent,1000\n"
                + "unknown," + year + ",ne pas tenir compte,500\n";
        FeatureCollection supplied = new FeatureCollection();
        for (String id : List.of("146966", "472", "unknown")) {
            Feature feature = new Feature();
            feature.setProperty("code_centrale", id);
            // Existing map properties must be replaced with the source's classification.
            feature.setProperty("type", "UNKNOWN");
            feature.setProperty("shape-icon", "car");
            feature.setGeometry(new Point(3, 43));
            supplied.add(feature);
        }
        List<? extends Comptage> records = GeoJsonParser.DataSource.M3MV2.getParser()
                .parseComptages(new StringReader(csv));
        for (Comptage.Type type : List.of(Comptage.Type.VEHICULES, Comptage.Type.BICYCLES)) {
            StringWriter writer = new StringWriter();
            GeoJsonParser.writeGeoJson(GeoJsonParser.DataSource.M3MV2, records, supplied, writer,
                    wayId -> null, type);
            FeatureCollection result = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString()));
            assertEquals(1, result.getFeatures().size());
            Feature feature = result.getFeatures().get(0);
            boolean bicycle = type == Comptage.Type.BICYCLES;
            assertEquals(bicycle ? "146966" : "472", feature.getProperty("code_centrale"));
            assertEquals(type.name(), feature.getProperty("type"));
            assertFalse(feature.getProperties().containsKey("shape-icon"));
            assertEquals(bicycle ? 500 : 1000, (int) feature.getProperty("moyenne_jour_annuelle"));
        }
    }

    @Test
    public void filtersMrnRecordsBeforeRequestingOsmMappingsOrTags() throws Exception {
        int year = Year.now().getValue();
        for (Comptage.Type type : List.of(Comptage.Type.VEHICULES, Comptage.Type.BICYCLES)) {
            List<MRNComptage> records = new ArrayList<>();
            for (String name : List.of("validMapped", "validProvided", "missing", "zero", "negative", "tooLarge", "old", "unknown")) {
                MRNComptage record = new MRNComptage();
                record.setCodeCentrale("MRN_" + name);
                record.setAnnee(name.equals("old") ? year - 100 : year);
                record.setType(name.equals("unknown") ? Comptage.Type.UNKNOWN : type);
                record.setLatitude(49.4);
                record.setLongitude(1.1);
                if (!name.equals("missing")) record.setMoyenneJourAnnuelle(500);
                switch (name) {
                    case "validProvided" -> record.setOsmWayId(123L);
                    case "zero" -> { record.setMoyenneJourAnnuelle(0); record.setOsmWayId(456L); }
                    case "negative" -> record.setMoyenneJourAnnuelle(-1);
                    case "tooLarge" -> { record.setMoyenneJourAnnuelle(300000); record.setOsmWayId(789L); }
                    default -> { }
                }
                records.add(record);
            }
            List<String> mappingRequests = new ArrayList<>();
            Set<Long> tagRequests = new LinkedHashSet<>();
            OSMInfoProvider provider = new OSMInfoProvider() {
                @Override public Long getWayId(String id, Point point, String roadReference, String streetName) {
                    mappingRequests.add(id);
                    return 321L;
                }
                @Override public OSMWayInfo getWayInfo(long id) {
                    tagRequests.add(id);
                    return new OSMWayInfo(java.time.Instant.EPOCH, java.util.Map.of("maxspeed", "50"));
                }
            };
            StringWriter output = new StringWriter();
            var statistics = GeoJsonParser.writeGeoJsonWithStatistics(GeoJsonParser.DataSource.MRN,
                    records, new FeatureCollection(), output, provider, type);
            assertEquals(List.of("MRN_validMapped"), mappingRequests);
            assertEquals(Set.of(123L, 321L), tagRequests);
            assertEquals(java.util.Map.of(year, 6L, year - 100, 1L), statistics.recordsBeforeFiltering());
            assertEquals(java.util.Map.of(year, 2L), statistics.recordsAfterFiltering());
            assertEquals(2, statistics.featureCount());
            FeatureCollection result = GeoJsonParser.parseGeoJSON(new StringReader(output.toString()));
            for (Feature feature : result.getFeatures()) {
                assertEquals("50", feature.getProperty("maximum_speed"));
            }
        }
    }

    @Test
    public void statisticsSeparatePopulationsAndCountRecordsBeforeGrouping() throws Exception {
        int year = Year.now().getValue();
        String csv = "code_centrale,annee,type_compteur,mja_jo\n"
                + "146966," + year + ",compteur permanent vélo,500\n"
                + "146966," + (year - 1) + ",compteur permanent vélo,900\n"
                + "472," + year + ",compteur permanent,1000\n";
        var records = GeoJsonParser.DataSource.M3MV2.getParser().parseComptages(new StringReader(csv));
        FeatureCollection supplied = new FeatureCollection();
        for (String id : List.of("146966", "472")) {
            Feature feature = new Feature();
            feature.setProperty("code_centrale", id);
            feature.setGeometry(new Point(3, 43));
            supplied.add(feature);
        }
        for (Comptage.Type type : List.of(Comptage.Type.VEHICULES, Comptage.Type.BICYCLES)) {
            var result = GeoJsonParser.writeGeoJsonWithStatistics(GeoJsonParser.DataSource.M3MV2,
                    records, supplied, new StringWriter(), wayId -> null, type);
            var expected = type == Comptage.Type.BICYCLES
                    ? java.util.Map.of(year, 1L, year - 1, 1L) : java.util.Map.of(year, 1L);
            assertEquals(expected, result.recordsBeforeFiltering());
            assertEquals(expected, result.recordsAfterFiltering());
            assertEquals(1, result.featureCount());
        }
    }

    @Test
    public void recordStatisticsPrecedeGeometrySelection() throws Exception {
        int year = Year.now().getValue();
        String csv = "Identifiant du compteur;Année;Localisation;Commune;Sens de la route;mja_tv_tcj;Latitude;Longitude\n"
                + "Per.034.0001.00.3;" + year + ";D11;OLONZAC;3;500;;\n"
                + "Per.034.0002.00.3;" + year + ";D11;OLONZAC;3;0;43;3\n";
        var records = new DPT34ComptageParser().parseComptages(new StringReader(csv));
        var result = GeoJsonParser.writeGeoJsonWithStatistics(GeoJsonParser.DataSource.DPT34,
                records, new FeatureCollection(), new StringWriter(), wayId -> null, Comptage.Type.VEHICULES);
        assertEquals(java.util.Map.of(year, 2L), result.recordsBeforeFiltering());
        assertEquals(java.util.Map.of(year, 1L), result.recordsAfterFiltering());
        assertEquals(0, result.featureCount());
    }

    @Test
    public void testFeatureCollectionOverloadMatchesReaderOutput() throws Exception {
        StringWriter readerOutput = new StringWriter();
        StringWriter collectionOutput = new StringWriter();
        StringWriter recordsOutput = new StringWriter();
        try (Reader csv = getReaderFromResource("M3MSample.csv");
             Reader geojson = getReaderFromResource("M3MSample.json")) {
            GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2, csv, geojson, readerOutput);
        }
        try (Reader csv = getReaderFromResource("M3MSample.csv");
             Reader geojson = getReaderFromResource("M3MSample.json")) {
            FeatureCollection collection = GeoJsonParser.parseGeoJSON(geojson);
            GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2, csv, collection, collectionOutput);
        }
        assertEquals(readerOutput.toString(), collectionOutput.toString());
        try (Reader csv = getReaderFromResource("M3MSample.csv");
             Reader geojson = getReaderFromResource("M3MSample.json")) {
            List<? extends Comptage> records = GeoJsonParser.DataSource.M3MV2.getParser().parseComptages(csv);
            GeoJsonParser.writeGeoJson(GeoJsonParser.DataSource.M3MV2, records,
                    GeoJsonParser.parseGeoJSON(geojson), recordsOutput, wayId -> null);
        }
        assertEquals(readerOutput.toString(), recordsOutput.toString());
        FeatureCollection result = GeoJsonParser.parseGeoJSON(new StringReader(readerOutput.toString()));
        assertFalse(result.getFeatures().isEmpty());
        result.getFeatures().forEach(feature -> assertEquals("MMM", feature.getProperty("data_source")));
    }

    @Test
    public void testNoGeoJsonReaderBuildsDpt34Features() throws Exception {
        StringWriter writer = new StringWriter();
        int year = Year.now().getValue();
        String csv = "Identifiant du compteur;Année;Localisation;Commune;Sens de la route;mja_tv_tcj;Latitude;Longitude\n"
                + "Per.034.0001.00.3;" + (year - 1) + ";D11;OLONZAC;3;400;43;3\n"
                + "Per.034.0001.00.3;" + year + ";D11;OLONZAC;3;500;43.5;3.5\n";
        GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.DPT34, new StringReader(csv), writer);
        StringWriter recordsOutput = new StringWriter();
        List<? extends Comptage> records = new DPT34ComptageParser().parseComptages(new StringReader(csv));
        GeoJsonParser.writeGeoJson(GeoJsonParser.DataSource.DPT34, records,
                new FeatureCollection(), recordsOutput, wayId -> null);
        assertEquals(writer.toString(), recordsOutput.toString());
        FeatureCollection result = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString()));
        assertEquals(1, result.getFeatures().size());
        Feature feature = result.getFeatures().get(0);
        assertEquals("DPT34_Per.034.0001.00.3", feature.getProperty("code_centrale"));
        assertEquals("DPT34", feature.getProperty("data_source"));
        assertEquals("VEHICULES", feature.getProperty("type"));
        Point point = (Point) feature.getGeometry();
        assertEquals(3.5, point.getCoordinates().getLongitude(), 0.0);
        assertEquals(43.5, point.getCoordinates().getLatitude(), 0.0);
        assertEquals(year, (int) feature.getProperty("annee"));
        assertEquals(500, (int) feature.getProperty("moyenne_jour_annuelle"));
        assertEquals("D11 - Olonzac", feature.getProperty("description"));
        assertEquals("500 ⇄", feature.getProperty("name"));
        assertEquals("500 (" + year + ") 400 (" + (year - 1) + ") ",
                feature.getProperty("moyenne_annuelle_history"));
        assertEquals("Green", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(writer.toString()).get("features").get(0)
                .get("properties").get("_umap_options").get("color").asText());
    }

    @Test
    public void testGeneratedGeometryUsesOnlyFilteredRecordsAndSkipsMissingGeometry() throws Exception {
        int year = Year.now().getValue();
        String csv = "Identifiant du compteur;Année;mja_tv_tcj;Latitude;Longitude\n"
                + "A;" + (year - 1) + ";400;43;3\n"
                + "A;" + year + ";500;;\n"
                + "A;" + (year + 1) + ";600;44;4\n"
                + "A;" + year + ";0;45;5\n"
                + "B;" + year + ";500;;\n"
                + "C;" + year + ";0;43;3\n"
                + "D;" + (year - 11) + ";500;43;3\n"
                + "E;" + (year + 1) + ";500;43;3\n";
        List<? extends Comptage> rows = new DPT34ComptageParser().parseComptages(new StringReader(csv));
        FeatureCollection result = GeoJsonParser.buildForType(
                new ArrayList<>(), rows, EnumSet.of(Comptage.Type.VEHICULES));
        assertEquals(1, result.getFeatures().size());
        Feature feature = result.getFeatures().get(0);
        assertEquals("DPT34_A", feature.getProperty("code_centrale"));
        Point point = (Point) feature.getGeometry();
        assertEquals(3.0, point.getCoordinates().getLongitude(), 0.0);
        assertEquals(43.0, point.getCoordinates().getLatitude(), 0.0);
        assertEquals(year, (int) feature.getProperty("annee"));
        assertEquals(500, (int) feature.getProperty("moyenne_jour_annuelle"));
        assertEquals(0, GeoJsonParser.buildForType(new ArrayList<>(), rows,
                EnumSet.of(Comptage.Type.BICYCLES)).getFeatures().size());
    }

    @Test
    public void testSuppliedFeaturesKeepGeometryAndDoNotGenerateMissingCounters() throws Exception {
        int year = Year.now().getValue();
        String csv = "Identifiant du compteur;Année;mja_tv_tcj;Latitude;Longitude\n"
                + "A;" + year + ";500;43;3\n"
                + "B;" + year + ";600;44;4\n";
        List<? extends Comptage> rows = new DPT34ComptageParser().parseComptages(new StringReader(csv));
        Feature supplied = new Feature();
        supplied.setProperty("code_centrale", "DPT34_A");
        supplied.setGeometry(new Point(2, 42));
        FeatureCollection result = GeoJsonParser.buildForType(
                Arrays.asList(supplied), rows, EnumSet.of(Comptage.Type.VEHICULES));
        assertEquals(1, result.getFeatures().size());
        Point point = (Point) result.getFeatures().get(0).getGeometry();
        assertEquals(2.0, point.getCoordinates().getLongitude(), 0.0);
        assertEquals(42.0, point.getCoordinates().getLatitude(), 0.0);
    }

    @Test
    public void testFeatureCollectionOverloadPreservesOsmEnrichment() throws Exception {
        StringWriter writer = new StringWriter();
        Set<Long> requestedWays = new LinkedHashSet<>();
        try (Reader csv = getReaderFromResource("M3MSample.csv");
             Reader geojson = getReaderFromResource("M3MSample.json")) {
            GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2, csv,
                    GeoJsonParser.parseGeoJSON(geojson), writer,
                    wayId -> { requestedWays.add(wayId); return null; });
        }
        assertEquals(Set.of(370743659L), requestedWays);
        assertEquals(1, GeoJsonParser.parseGeoJSON(
                new StringReader(writer.toString())).getFeatures().size());
    }

    @Test
    public void testContraflowUsesSelectedYearsMappedWayAndReplacesReusedProperties() throws Exception {
        for (Comptage.Type type : List.of(Comptage.Type.VEHICULES, Comptage.Type.BICYCLES)) {
            for (String oneway : List.of("yes", "-1", "no", "missing-cache", "missing-id")) {
                M3MComptage current = new M3MComptage();
                current.setCodeCentrale("sample");
                current.setAnnee(Year.now().getValue());
                current.setMoyenneJourAnnuelle(500);
                current.setType(type);
                current.setGeometry(new Point(3, 43));
                M3MComptage older = new M3MComptage();
                older.setCodeCentrale("sample");
                older.setAnnee(Year.now().getValue() - 1);
                older.setMoyenneJourAnnuelle(600);
                older.setType(type);
                older.setOsmWayId(456L);
                boolean expected = oneway.equals("yes") || oneway.equals("-1");
                Feature supplied = new Feature();
                supplied.setGeometry(new Point(3, 43));
                supplied.setProperty("code_centrale", "sample");
                supplied.setProperty("bicycle_contraflow_allowed", expected ? "no" : "yes");
                FeatureCollection reused = new FeatureCollection();
                reused.add(supplied);
                OSMInfoProvider provider = new OSMInfoProvider() {
                    @Override public Long getWayId(String id, Point point, String ref, String streetName) {
                        return oneway.equals("missing-id") ? null : 123L;
                    }
                    @Override public OSMWayInfo getWayInfo(long id) {
                        if (id == 456L) {
                            return new OSMWayInfo(java.time.Instant.EPOCH, java.util.Map.of("oneway", "yes", "oneway:bicycle", "no"));
                        }
                        return oneway.equals("missing-cache") ? null : new OSMWayInfo(java.time.Instant.EPOCH,
                                java.util.Map.of("oneway", oneway, "oneway:bicycle", "no"));
                    }
                };
                for (FeatureCollection input : List.of(new FeatureCollection(), reused)) {
                    StringWriter writer = new StringWriter();
                    GeoJsonParser.writeGeoJsonWithCount(GeoJsonParser.DataSource.M3MV2,
                            List.of(current, older), input, writer, provider, type);
                    Feature feature = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString()))
                            .getFeatures().get(0);
                    boolean missing = oneway.equals("missing-cache") || oneway.equals("missing-id");
                    assertEquals(type + "/" + oneway, missing ? null : expected ? "yes" : "no",
                            feature.getProperty("bicycle_contraflow_allowed"));
                    assertEquals(!missing, feature.getProperties().containsKey("bicycle_contraflow_allowed"));
                }
            }
        }
    }

    @Test
    public void testWriteGeoJsonEnrichesParsedRecords() throws Exception {
        M3MComptage record = new M3MComptage();
        record.setCodeCentrale("sample");
        record.setAnnee(Year.now().getValue());
        record.setMoyenneJourAnnuelle(500);
        record.setType(Comptage.Type.VEHICULES);
        record.setGeometry(new Point(3, 43));
        record.setOsmWayId(123L);
        OSMWayInfo info = new OSMWayInfo();
        info.setTags(java.util.Map.of("maxspeed", "50"));
        Set<Long> requestedWays = new LinkedHashSet<>();
        StringWriter writer = new StringWriter();

        GeoJsonParser.writeGeoJson(GeoJsonParser.DataSource.M3MV2, List.of(record),
                new FeatureCollection(), writer, wayId -> {
                    requestedWays.add(wayId);
                    return info;
                });

        assertEquals(Set.of(123L), requestedWays);
        Feature feature = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString())).getFeatures().get(0);
        assertEquals("50", feature.getProperty("maximum_speed"));
        assertEquals(new Point(3, 43), feature.getGeometry());
    }

    @Test
    public void testBuildForTypeIncludesPastTenYearsAndCurrentYear() {
        int currentYear = Year.now().getValue();
        List<Feature> features = new ArrayList<>();
        List<M3MComptage> comptages = new ArrayList<>();
        for (int year : new int[]{currentYear - 16, currentYear - 10, currentYear - 5, currentYear}) {
            String codeCentrale = "counter-" + year;
            Feature feature = new Feature();
            feature.setProperty("code_centrale", codeCentrale);
            features.add(feature);

            M3MComptage comptage = new M3MComptage();
            comptage.setCodeCentrale(codeCentrale);
            comptage.setAnnee(year);
            comptage.setType(Comptage.Type.VEHICULES);
            comptage.setDisplayName("Sample counter " + year);
            comptage.setMoyenneJourAnnuelle(500);
            comptage.setMoyenneHeureDePointeMatin("");
            comptage.setMoyenneHeureDePointeSoir("");
            comptages.add(comptage);
        }

        FeatureCollection result = GeoJsonParser.buildForType(
                features, comptages, EnumSet.of(Comptage.Type.VEHICULES));
        List<Integer> returnedYears = result.getFeatures().stream()
                .map(feature -> (Integer) feature.getProperty("annee"))
                .sorted()
                .collect(Collectors.toList());

        assertEquals(Arrays.asList(currentYear - 10, currentYear - 5, currentYear), returnedYears);
    }

    @Test
    public void testMmmChannelSpeedsUseMaximumV85AndMeanRegardlessOfHighestTrafficRow() throws Exception {
        // Only mja_jo changes: Z, 0 and 1 respectively supply the main properties.
        int[][] trafficCounts = {{500, 400, 900}, {900, 400, 500}, {400, 900, 500}};
        for (int i = 0; i < trafficCounts.length; i++) {
            int[] counts = trafficCounts[i];
            String csv = "code_centrale,annee,type_compteur,code_canal,mja_jo,v85ma_tcj,vtma_tcj\n"
                    + "472,2022,compteur permanent,0," + counts[0] + ",53,34\n"
                    + "472,2022,compteur permanent,1," + counts[1] + ",52,32\n"
                    + "472,2022,compteur permanent,Z," + counts[2] + ",,\n";
            Feature input = new Feature();
            input.setProperty("code_centrale", "472");
            input.setGeometry(new Point(3.88, 43.60));
            FeatureCollection source = new FeatureCollection();
            source.add(input);
            StringWriter writer = new StringWriter();

            GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2,
                    new StringReader(csv), source, writer);

            FeatureCollection result = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString()));
            assertEquals(1, result.getFeatures().size());
            Feature feature = result.getFeatures().get(0);
            assertEquals("472", feature.getProperty("code_centrale"));
            assertEquals(2022, (int) feature.getProperty("annee"));
            assertEquals(900, (int) feature.getProperty("moyenne_jour_annuelle"));
            assertEquals("53", feature.getProperty("v85_speed"));
            assertEquals("34", feature.getProperty("mean_speed"));
            assertEquals(i == 0 ? "900 ⇄" : "900", feature.getProperty("name"));
            assertEquals("900 (2022) ", feature.getProperty("moyenne_annuelle_history"));
        }
    }

    @Test
    public void testMaximumSpeedsIgnoreOlderYears() {
        M3MComptage latest = new M3MComptage();
        latest.setAnnee(2022);
        latest.setMoyenneJourAnnuelle(900);
        M3MComptage sameYear = new M3MComptage();
        sameYear.setAnnee(2022);
        sameYear.setMoyenneJourAnnuelle(500);
        sameYear.setV85Speed(53);
        sameYear.setMeanSpeed(34);
        M3MComptage older = new M3MComptage();
        older.setAnnee(2021);
        older.setMoyenneJourAnnuelle(1000);
        older.setV85Speed(80);
        older.setMeanSpeed(60);

        List<M3MComptage> comptages = new ArrayList<>(Arrays.asList(older, sameYear, latest));
        GeoJsonParser.sortComptages(comptages);

        assertEquals(latest, comptages.get(0));
        assertEquals(53, latest.getV85Speed());
        assertEquals(34, latest.getMeanSpeed());
    }

    @Test
    public void testMeanSpeedFromCsvToGeoJson() throws Exception {
        for (String value : Arrays.asList("35", "", "0", "-1")) {
            String csv = "code_centrale,annee,type_compteur,mja_jo,vtma_tcj,hp_johvs_matin,hp_johvs_soir\n"
                    + "100457," + Year.now().getValue() + ",compteur feux,500," + value + ",,\n";
            StringWriter writer = new StringWriter();
            try (Reader geojsonReader = getReaderFromResource("M3MSample.json")) {
                GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2,
                        new StringReader(csv), geojsonReader, writer);
            }
            FeatureCollection result = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString()));
            assertEquals(1, result.getFeatures().size());
            Feature feature = result.getFeatures().get(0);
            if ("35".equals(value)) {
                assertEquals("35", feature.getProperty("mean_speed"));
            } else {
                assertFalse(feature.getProperties().containsKey("mean_speed"));
            }
        }
    }

    @Test
    public void testOptionalPropertiesAreOmittedUnlessPresent() throws Exception {
        for (int speed : new int[]{-1, 0, 30}) {
            Feature feature = new Feature();
            feature.setProperty("code_centrale", "test-counter");
            M3MComptage comptage = new M3MComptage();
            comptage.setCodeCentrale("test-counter");
            comptage.setAnnee(Year.now().getValue());
            comptage.setMoyenneJourAnnuelle(500);
            comptage.setV85Speed(speed);
            comptage.setMeanSpeed(speed);
            comptage.setMaximumSpeed(speed);
            comptage.setMoyenneHeureDePointeMatin(speed > 0 ? "100" : "");
            comptage.setMoyenneHeureDePointeSoir(speed > 0 ? "200" : null);

            FeatureCollection collection = GeoJsonParser.buildForType(
                    Arrays.asList(feature), new ArrayList<>(Arrays.asList(comptage)),
                    EnumSet.of(Comptage.Type.VEHICULES));
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(collection);
            Feature output = GeoJsonParser.parseGeoJSON(new StringReader(json)).getFeatures().get(0);
            for (String property : Arrays.asList("v85_speed", "mean_speed", "maximum_speed",
                    "moyenne_pointe_matin", "moyenne_pointe_soir")) {
                if (speed > 0) {
                    String expected = property.equals("moyenne_pointe_matin") ? "100"
                            : property.equals("moyenne_pointe_soir") ? "200" : "30";
                    assertEquals(expected, output.getProperty(property));
                } else {
                    assertFalse(property, output.getProperties().containsKey(property));
                }
            }
        }
    }

    @Test
    public void testParseAndMerge() throws Exception {

        StringWriter writer = new StringWriter();
        try (Reader csvReader = getReaderFromResource("M3MSample.csv");
             Reader geojsonReader = getReaderFromResource("M3MSample.json")) {

            GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2, csvReader, geojsonReader, writer);
        }

        FeatureCollection featureCollection = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString()));
        List<Feature> featureList = featureCollection.getFeatures();
        assertEquals(1, featureList.size());
        Feature feature = featureList.get(0);
        assertEquals("100457", feature.getProperty("code_centrale"));
        com.fasterxml.jackson.databind.JsonNode options = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(writer.toString()).get("features").get(0).get("properties").get("_umap_options");
        assertFalse(options.has("showLabel"));
        assertFalse(options.has("labelInteractive"));
        assertEquals("Green", options.get("color").asText());
    }
}
