package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import org.geojson.Point;
import org.traficinfo.parsers.*;
import java.io.*;
import java.time.*;
import java.util.*;

/** Reads the common accident/participant/vehicle projection, without publisher-specific models. */
final class AccidentPublicationJson {
    Map<String, List<Entry>> readByWay(InputStream input) throws IOException {
        var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var type = mapper.getTypeFactory().constructMapType(TreeMap.class,
                mapper.getTypeFactory().constructType(String.class),
                mapper.getTypeFactory().constructCollectionType(List.class, Entry.class));
        Map<String, List<Entry>> records = mapper.readerFor(type).without(JsonParser.Feature.AUTO_CLOSE_SOURCE).readValue(input);
        if (records == null) throw new IOException("Expected a way-to-accident object");
        try {
            for (var rows : records.values()) {
                Objects.requireNonNull(rows);
                for (Entry row : rows) {
                    Objects.requireNonNull(row);
                    Objects.requireNonNull(row.getDate());
                    row.getTime();
                    Objects.requireNonNull(row.participants);
                    Objects.requireNonNull(row.vehicles);
                    for (Participant participant : row.participants) {
                        Objects.requireNonNull(participant);
                        Objects.requireNonNull(participant.participantId);
                    }
                    for (Vehicle vehicle : row.vehicles) {
                        Objects.requireNonNull(vehicle);
                        Objects.requireNonNull(vehicle.vehicleId);
                    }
                }
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid accident publication input", invalid);
        }
        return records;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry implements Accident {
        public String accidentId, date, time, regionalId, municipalityId, address;
        public Double latitude, longitude;
        public Long osmWayId;
        public List<Participant> participants = new ArrayList<>();
        public List<Vehicle> vehicles = new ArrayList<>();
        public String getAccidentId() { return accidentId; }
        public LocalDate getDate() { return date == null ? null : LocalDate.parse(date); }
        public LocalTime getTime() { return time == null ? null : LocalTime.parse(time); }
        public String getRegionalId() { return regionalId; }
        public String getMunicipalityId() { return municipalityId; }
        public String getAddress() { return address; }
        public Long getOsmWayId() { return osmWayId; }
        public void setOsmWayId(Long id) { osmWayId = id; }
        public Point getGeometry() { return latitude == null || longitude == null ? null : new Point(longitude, latitude); }
        public List<Participant> getParticipants() { return participants; }
        public List<Vehicle> getVehicles() { return vehicles; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Participant implements AccidentParticipant {
        public String participantId, vehicleId;
        public Role role = Role.UNKNOWN;
        public InjurySeverity injurySeverity = InjurySeverity.UNKNOWN;
        public Sex sex = Sex.UNKNOWN;
        public Integer birthYear;
        public String getParticipantId() { return participantId; }
        public String getVehicleId() { return vehicleId; }
        public Role getRole() { return role == null ? Role.UNKNOWN : role; }
        public InjurySeverity getInjurySeverity() { return injurySeverity == null ? InjurySeverity.UNKNOWN : injurySeverity; }
        public Sex getSex() { return sex == null ? Sex.UNKNOWN : sex; }
        public Integer getBirthYear() { return birthYear; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vehicle implements AccidentVehicle {
        public String vehicleId;
        public Type type = Type.UNKNOWN;
        public Motorization motorization = Motorization.UNKNOWN;
        public String getVehicleId() { return vehicleId; }
        public Type getType() { return type == null ? Type.UNKNOWN : type; }
        public Motorization getMotorization() { return motorization == null ? Motorization.UNKNOWN : motorization; }
    }
}
