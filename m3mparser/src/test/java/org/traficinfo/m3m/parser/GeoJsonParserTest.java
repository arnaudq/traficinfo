package org.traficinfo.m3m.parser;

import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class GeoJsonParserTest extends ParserBaseTest {

    @Test
    public void testParseAndMerge() throws Exception {

        StringWriter writer = new StringWriter();
        try (Reader csvReader = getReaderFromResource("M3MSample.csv");
             Reader geojsonReader = getReaderFromResource("M3MSample.json")) {

            GeoJsonParser.parseAndMerge(csvReader, geojsonReader, writer);
        }

        FeatureCollection featureCollection = GeoJsonParser.parseGeoJSON(new StringReader(writer.toString()));
        List<Feature> featureList = featureCollection.getFeatures();
        assertEquals(1, featureList.size());
        Feature feature = featureList.get(0);
        assertEquals("100457", feature.getProperty("code_centrale"));
    }
}
