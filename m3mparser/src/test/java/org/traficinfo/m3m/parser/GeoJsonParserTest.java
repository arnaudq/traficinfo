package org.traficinfo.m3m.parser;

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
