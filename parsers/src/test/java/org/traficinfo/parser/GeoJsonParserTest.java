package org.traficinfo.parser;

import org.traficinfo.parser.m3m.M3MComptage;

import org.geojson.Feature;
import org.geojson.FeatureCollection;
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
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class GeoJsonParserTest extends ParserBaseTest {

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
    }
}
