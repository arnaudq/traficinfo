package org.traficinfo.parsers;

import org.traficinfo.parsers.m3m.M3MComptage;
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
    public void testFeatureCollectionOverloadMatchesReaderOutput() throws Exception {
        StringWriter readerOutput = new StringWriter();
        StringWriter collectionOutput = new StringWriter();
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
    }

    @Test
    public void testNoGeoJsonReaderBuildsDpt34Features() throws Exception {
        StringWriter writer = new StringWriter();
        int year = Year.now().getValue();
        String csv = "Identifiant du compteur;Année;Localisation;Commune;Sens de la route;mja_tv_tcj;Latitude;Longitude\n"
                + "Per.034.0001.00.3;" + (year - 1) + ";D11;OLONZAC;3;400;43;3\n"
                + "Per.034.0001.00.3;" + year + ";D11;OLONZAC;3;500;43.5;3.5\n";
        GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.DPT34, new StringReader(csv), writer);
        FeatureCollection result = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString()));
        assertEquals(1, result.getFeatures().size());
        Feature feature = result.getFeatures().get(0);
        assertEquals("DPT34_Per.034.0001.00.3", feature.getProperty("code_centrale"));
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
        Set<Long> waysToRefresh = new LinkedHashSet<>();
        try (Reader csv = getReaderFromResource("M3MSample.csv");
             Reader geojson = getReaderFromResource("M3MSample.json")) {
            GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2, csv,
                    GeoJsonParser.parseGeoJSON(geojson), writer,
                    wayId -> null, waysToRefresh);
        }
        assertEquals(Set.of(370743659L), waysToRefresh);
        assertEquals(1, GeoJsonParser.parseGeoJSON(
                new StringReader(writer.toString())).getFeatures().size());
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
