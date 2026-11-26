package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.traficinfo.parsers.AccidentParticipant.*;
import org.traficinfo.parsers.AccidentVehicle.Motorization;
import org.traficinfo.parsers.onisr.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class AccidentPublicationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static ONISRAccident accident(int year, int id, long way) throws Exception {
        var record = new ONISRAccidentParser().parseAccidents(new StringReader(
                "Num_Acc;an;mois;jour;hrmn;dep;com;lat;long\n"
                + String.format(Locale.ROOT, "%d%08d;%d;1;2;12:30;34;34172;43,6;3,8\n", year, id, year))).get(0);
        record.setOsmWayId(way);
        return record;
    }
    static ONISRAccidentParticipant person(String id, String vehicle, Role role, InjurySeverity severity) {
        return new ONISRAccidentParticipant(id, vehicle, role, severity, 1990, Sex.FEMALE);
    }

    @Test public void countsPeopleByCategorySeverityAndYearAndPublishesPoints() throws Exception {
        var a = accident(2024, 1, 77).withVehicles(List.of(
                new ONISRAccidentVehicle("1", 7, Motorization.ELECTRIC),
                new ONISRAccidentVehicle("2", 80, Motorization.ELECTRIC),
                new ONISRAccidentVehicle("3", 50, Motorization.ELECTRIC),
                new ONISRAccidentVehicle("4", 60, Motorization.HUMAN),
                new ONISRAccidentVehicle("5", 99, Motorization.OTHER)));
        a = a.withParticipants(List.of(person("1", "1", Role.PEDESTRIAN, InjurySeverity.KILLED),
                person("2", "1", Role.DRIVER, InjurySeverity.HOSPITALIZED),
                person("3", "1", Role.PASSENGER, InjurySeverity.HOSPITALIZED),
                person("4", "2", Role.DRIVER, InjurySeverity.LIGHTLY_INJURED),
                person("5", "3", Role.DRIVER, InjurySeverity.KILLED),
                person("6", "4", Role.PASSENGER, InjurySeverity.UNINJURED),
                person("7", "5", Role.PASSENGER, InjurySeverity.UNKNOWN),
                person("8", null, Role.UNKNOWN, InjurySeverity.UNKNOWN)));
        var b = accident(2025, 1, 77);
        var c = accident(2024, 2, 88).withParticipants(List.of(person("9", "999", Role.DRIVER, InjurySeverity.UNKNOWN)));
        var output = new AccidentPublication().render("34", Map.of("77", List.of(b, a), "88", List.of(c)));
        var summary = JSON.readTree(output.summary());
        assertFalse(summary.has("scope"));
        assertFalse(summary.has("ways"));
        assertEquals(2, summary.at("/years/2024/accidentCount").asInt());
        assertEquals(1, summary.at("/years/2025/accidentCount").asInt());
        var counts = summary.at("/years/2024/participantCounts");
        assertEquals(1, counts.at("/PEDESTRIAN/KILLED").asInt());
        assertEquals(2, counts.at("/MOTORIZED_VEHICLE_OCCUPANT/HOSPITALIZED").asInt());
        assertEquals(1, counts.at("/CYCLIST/LIGHTLY_INJURED").asInt());
        assertEquals(1, counts.at("/PERSONAL_MOBILITY/KILLED").asInt());
        assertEquals(1, counts.at("/PERSONAL_MOBILITY/UNINJURED").asInt());
        assertEquals(1, counts.at("/OTHER/UNKNOWN").asInt());
        assertEquals(2, counts.at("/UNKNOWN/UNKNOWN").asInt());
        var geo = JSON.readTree(output.geojson());
        assertEquals(3, geo.get("features").size());
        assertEquals(3.8, geo.at("/features/0/geometry/coordinates/0").asDouble(), 0);
        assertEquals(43.6, geo.at("/features/0/geometry/coordinates/1").asDouble(), 0);
        assertFalse(geo.at("/features/0/properties").has("participants"));
        assertFalse(geo.at("/features/0/properties").has("sourceFields"));
        int featurePeople = 0;
        for (var f : geo.get("features")) for (var category : f.at("/properties/participantCounts"))
            for (var value : category) featurePeople += value.asInt();
        assertEquals(9, featurePeople);
        assertArrayEquals(output.summary(), new AccidentPublication().render("34", Map.of("88", List.of(c), "77", List.of(a,b))).summary());
    }

    @Test public void supportsEmptyInputAndRejectsCorruptRecordsBeforePublication() throws Exception {
        var empty = new AccidentPublication().render("34", Map.of());
        assertEquals(0, JSON.readTree(empty.geojson()).get("features").size());
        assertEquals(0, JSON.readTree(empty.summary()).get("years").size());
        var record = accident(2025, 1, 77);
        Store data = new Store(), out = new Store();
        String name = "ONISR_DPT34_Accidents.json";
        var codec = new ONISRAccidentJson();
        data.objects.put(name, codec.writeByWay(Map.of(77L,List.of(record))));
        var publisher = new AccidentPublicationPublisher(data,out);
        publisher.publish("ONISR_DPT34");
        assertEquals(Set.of("ONISR_DPT34_Accidents.geojson", "ONISR_DPT34_Accidents_Summary.json"), out.objects.keySet());
        assertEquals("application/geo+json", out.types.get("ONISR_DPT34_Accidents.geojson"));
        assertEquals("application/json", out.types.get("ONISR_DPT34_Accidents_Summary.json"));
        var previous = new HashMap<>(out.objects);
        for (byte[] invalid : List.of("null".getBytes(), "[]".getBytes(), "{} {}".getBytes(),
                codec.writeByWay(Map.of(88L,List.of(record))), codec.writeByWay(Map.of(77L,List.of(record, record))))) {
            data.objects.put(name, invalid);
            try { publisher.publish("ONISR_DPT34"); fail("Expected corrupt source to fail"); }
            catch (IOException expected) { assertEquals(previous, out.objects); }
        }
        out.fail = true;
        data.objects.put(name, codec.writeByWay(Map.of(77L,List.of(record))));
        try { publisher.publish("ONISR_DPT34"); fail(); } catch(IOException expected) { assertEquals(previous,out.objects); }
    }

    static class Store extends StubObjectStore {
        final Map<String,byte[]> objects = new HashMap<>();
        final Map<String,String> types = new HashMap<>();
        boolean fail;
        Store(){super();}
        @Override public InputStream getResource(String name) {
            if(!objects.containsKey(name)) throw new ObjectNotFoundException("missing", null, "test");
            return new ByteArrayInputStream(objects.get(name));
        }
        @Override public void putResource(String name,String type,ObjectMetadata meta,InputStream input) throws IOException {
            if(fail)throw new IOException("Injected failure");
            objects.put(name,input.readAllBytes());types.put(name,type);
        }
    }
}
