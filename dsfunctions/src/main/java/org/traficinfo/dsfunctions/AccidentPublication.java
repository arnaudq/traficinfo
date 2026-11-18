package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.traficinfo.parsers.AccidentParticipant;
import org.traficinfo.parsers.AccidentVehicle;
import org.traficinfo.parsers.Accident;
import org.geojson.Point;

import java.io.IOException;
import java.util.*;

/** Pure conversion and person-count aggregation of a source's way-matched accidents. */
public final class AccidentPublication {
    public enum Category { PEDESTRIAN, CYCLIST, MOTORIZED_VEHICLE_OCCUPANT, PERSONAL_MOBILITY, OTHER, UNKNOWN }
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Output(byte[] geojson, byte[] summary) {}

    public Output render(String sourceId, Map<String, ? extends List<? extends Accident>> input) throws IOException {
        ObjectNode geojson = JSON.createObjectNode().put("type", "FeatureCollection");
        var features = geojson.putArray("features");
        ObjectNode summary = JSON.createObjectNode().put("schemaVersion", 1)
                .put("sourceId", sourceId);
        ObjectNode years = summary.putObject("years");
        Set<String> identities = new HashSet<>();
        for (String key : new TreeSet<>(input.keySet())) {
            long way;
            try {
                way = Long.parseLong(key);
                if (way <= 0 || !Long.toString(way).equals(key)) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid OSM way key: " + key, invalid);
            }
            List<Accident> rows = new ArrayList<>(input.get(key));
            for (var accident : rows) validate(accident, sourceId, way, identities);
            rows.sort(Comparator.comparing(Accident::getDate).thenComparing(Accident::getAccidentId));
            if (rows.isEmpty()) continue;
            for (var accident : rows) {
                Map<String, AccidentVehicle> vehicles = new HashMap<>();
                for (var vehicle : accident.getVehicles()) {
                    if (vehicles.putIfAbsent(vehicle.getVehicleId(), vehicle) != null) throw new IOException("Duplicate vehicle ID");
                }
                ObjectNode counts = counts();
                Set<String> people = new HashSet<>();
                for (var participant : accident.getParticipants()) {
                    if (!people.add(participant.getParticipantId())) throw new IOException("Duplicate participant ID");
                    Category category = category(participant, vehicles.get(participant.getVehicleId()));
                    ObjectNode cell = (ObjectNode) counts.get(category.name());
                    String severity = participant.getInjurySeverity().name();
                    cell.put(severity, cell.get(severity).asInt() + 1);
                }
                String year = Integer.toString(accident.getDate().getYear());
                accumulate(years, year, counts);
                ObjectNode feature = features.addObject().put("type", "Feature").put("id", accident.getAccidentId());
                var coordinates = feature.putObject("geometry").put("type", "Point").putArray("coordinates");
                coordinates.add(point(accident).getCoordinates().getLongitude()).add(point(accident).getCoordinates().getLatitude());
                ObjectNode properties = feature.putObject("properties").put("accidentId", accident.getAccidentId())
                        .put("date", accident.getDate().toString()).put("time", accident.getTime() == null ? null : accident.getTime().toString())
                        .put("year", accident.getDate().getYear()).put("regionalId", accident.getRegionalId())
                        .put("municipalityId", accident.getMunicipalityId()).put("osmWayId", way);
                properties.set("participantCounts", counts);
            }
        }
        return new Output(JSON.writeValueAsBytes(geojson), JSON.writeValueAsBytes(summary));
    }

    static Category category(AccidentParticipant person, AccidentVehicle vehicle) {
        if (person.getRole() == AccidentParticipant.Role.PEDESTRIAN) return Category.PEDESTRIAN;
        if (person.getRole() == AccidentParticipant.Role.UNKNOWN || vehicle == null) return Category.UNKNOWN;
        return switch (vehicle.getType()) {
            case BICYCLE, ELECTRIC_BICYCLE -> Category.CYCLIST;
            case MOTORIZED_PERSONAL_MOBILITY, NON_MOTORIZED_PERSONAL_MOBILITY -> Category.PERSONAL_MOBILITY;
            case UNKNOWN -> Category.UNKNOWN;
            case OTHER -> Category.OTHER;
            default -> Category.MOTORIZED_VEHICLE_OCCUPANT;
        };
    }

    private static ObjectNode counts() {
        ObjectNode counts = JSON.createObjectNode();
        for (Category category : Category.values()) {
            ObjectNode cell = counts.putObject(category.name());
            for (var severity : AccidentParticipant.InjurySeverity.values()) cell.put(severity.name(), 0);
        }
        return counts;
    }

    private static void accumulate(ObjectNode years, String year, ObjectNode counts) {
        ObjectNode bucket = (ObjectNode) years.get(year);
        if (bucket == null) {
            bucket = years.putObject(year).put("accidentCount", 0);
            bucket.set("participantCounts", counts());
        }
        bucket.put("accidentCount", bucket.get("accidentCount").asInt() + 1);
        for (Category category : Category.values()) {
            ObjectNode cell = (ObjectNode) bucket.get("participantCounts").get(category.name());
            for (var severity : AccidentParticipant.InjurySeverity.values()) {
                String name = severity.name();
                cell.put(name, cell.get(name).asInt() + counts.get(category.name()).get(name).asInt());
            }
        }
    }

    private static Point point(Accident accident) { return (Point) accident.getGeometry(); }

    private static void validate(Accident accident, String sourceId, long way, Set<String> ids) throws IOException {
        if (accident == null || accident.getDate() == null || accident.getAccidentId() == null
                || accident.getAccidentId().isBlank()
                || !Objects.equals(accident.getOsmWayId(), way)
                || !ids.add(accident.getAccidentId()) || !(accident.getGeometry() instanceof Point)
                || !Double.isFinite(point(accident).getCoordinates().getLatitude()) || Math.abs(point(accident).getCoordinates().getLatitude()) > 90
                || !Double.isFinite(point(accident).getCoordinates().getLongitude()) || Math.abs(point(accident).getCoordinates().getLongitude()) > 180) {
            throw new IOException("Invalid or duplicate accident for source " + sourceId + " and way " + way);
        }
    }
}
