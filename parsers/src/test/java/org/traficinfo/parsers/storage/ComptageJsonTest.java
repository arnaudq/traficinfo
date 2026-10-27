package org.traficinfo.parsers.storage;

import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.geojson.Point;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class ComptageJsonTest {
    @Test
    public void roundTripsStoredFieldsForEveryDatasourceWithoutLosingNullMeasures() throws Exception {
        ComptageJson json = new ComptageJson();
        for (GeoJsonParser.DataSource source : GeoJsonParser.DataSource.values()) {
            String fixture = source.getId().equals("MMM") ? "M3MSample.csv" : source.getId() + "Sample.csv";
            try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/" + fixture), StandardCharsets.UTF_8)) {
                List<? extends Comptage> counts = source.getParser().parseComptages(reader);
                assertFalse(source.getId(), counts.isEmpty());
                for (Comptage count : counts) {
                    if (count instanceof M3MComptage mmm) mmm.setGeometry(new Point(3.8, 43.6));
                    byte[] original = json.write(count);
                    byte[] array = ("[" + new String(original, StandardCharsets.UTF_8) + "]").getBytes(StandardCharsets.UTF_8);
                    Comptage restored = json.read(new ByteArrayInputStream(array), source.getDsComptageClass()).get(0);
                    assertArrayEquals(source.getId(), original, json.write(restored));
                    assertEquals(count.getMoyenneJourAnnuelle(), restored.getMoyenneJourAnnuelle());
                    assertEquals(count.getDirectionType(), restored.getDirectionType());
                    assertEquals(count.getGeometry(), restored.getGeometry());
                    assertEquals(count.getDisplayName(), restored.getDisplayName());
                }
            }
        }
    }
}
