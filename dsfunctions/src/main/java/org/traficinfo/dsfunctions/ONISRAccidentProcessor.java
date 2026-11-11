/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.dsfunctions;

import com.oracle.bmc.model.BmcException;
import org.traficinfo.parsers.onisr.ONISRAccident;
import org.traficinfo.parsers.onisr.ONISRAccidentJson;
import org.traficinfo.parsers.onisr.ONISRAccidentParser;
import org.traficinfo.parsers.onisr.ONISRAccidentParticipantParser;
import org.traficinfo.parsers.onisr.ONISRAccidentVehicleParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Local ONISR import/publication logic; clients can use real storage or an in-memory fake. */
public final class ONISRAccidentProcessor {
    private final ObjectStoreClient accidentStore;
    private final Function<String, OSMInfoProviderImpl> providers;
    private final Set<String> regionalIds;
    private final ONISRAccidentJson json = new ONISRAccidentJson();

    /** Clients target onisrinfo and osminfo respectively; ownership stays with the caller. */
    public ONISRAccidentProcessor(ObjectStoreClient accidentStore, ObjectStoreClient osmStore) {
        this(accidentStore, osmStore, Set.of("30", "34", "76"));
    }

    public ONISRAccidentProcessor(ObjectStoreClient accidentStore, ObjectStoreClient osmStore, Set<String> regionalIds) {
        this(accidentStore, prefix -> new OSMInfoProviderImpl(osmStore, new OpenStreetMapClient(),
                prefix + "_OSMInfo.json", prefix + "_OSMWayMapping.json"), regionalIds);
        Objects.requireNonNull(osmStore);
    }

    ONISRAccidentProcessor(ObjectStoreClient accidentStore, Function<String, OSMInfoProviderImpl> providers,
                           Set<String> regionalIds) {
        this.accidentStore = Objects.requireNonNull(accidentStore);
        this.providers = Objects.requireNonNull(providers);
        this.regionalIds = Set.copyOf(regionalIds);
        if (this.regionalIds.isEmpty() || this.regionalIds.stream()
                .anyMatch(id -> !id.matches("(?:[0-9]{2,3}|2[AB])"))) {
            throw new IllegalArgumentException("At least one normalized regional ID is required");
        }
    }

    /** Replace this year for every selected region, including regions with no matching rows. */
    public Map<String, Statistics> importCsv(String objectName, int year) throws IOException {
        return importCsv(objectName, null, year);
    }

    /** Import characteristics and only participants belonging to the filtered accidents. */
    public Map<String, Statistics> importCsv(String objectName, String participantsObject, int year) throws IOException {
        return importCsv(objectName, participantsObject, null, year);
    }

    /** Import selected accidents with their participants and vehicles before replacing any regional year. */
    public Map<String, Statistics> importCsv(String objectName, String participantsObject, String vehiclesObject, int year)
            throws IOException {
        storeYear(objectName, participantsObject, vehiclesObject, year);
        return refresh();
    }

    /** Import each selected publication, then rebuild combined outputs once. Years are not a transaction. */
    public Map<String, Statistics> importYears(List<ONISRYearInputs> inputs) throws IOException {
        List<ONISRYearInputs> selected = List.copyOf(inputs);
        if (selected.isEmpty()) throw new IllegalArgumentException("At least one ONISR year is required");
        for (ONISRYearInputs year : selected) {
            storeYear(year.accidentObject(), year.participantsObject(), year.vehiclesObject(), year.year());
        }
        return refresh();
    }

    private void storeYear(String objectName, String participantsObject, String vehiclesObject, int year)
            throws IOException {
        if (objectName == null || !objectName.endsWith(".csv") || year < 2005 || year > 9999) {
            throw new IllegalArgumentException("A CSV object name and a year from 2005 to 9999 are required");
        }
        List<ONISRAccident> parsed;
        Path staged = Files.createTempFile("onisr-", ".csv");
        try {
            try (InputStream input = accidentStore.getResource(objectName)) {
                Files.copy(input, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            parsed = new ONISRAccidentParser(regionalIds).parseAccidents(staged.toFile());
        } finally {
            Files.deleteIfExists(staged);
        }
        // Validate the complete import before replacing any stored regional year.
        for (ONISRAccident accident : parsed) validate(accident, accident.getRegionalId(), year);
        Set<String> accidentIds = new HashSet<>();
        parsed.forEach(accident -> accidentIds.add(accident.getAccidentId()));
        if (participantsObject != null) {
            try (InputStream input = accidentStore.getResource(participantsObject);
                 var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                var participants = new ONISRAccidentParticipantParser().parseParticipants(reader, accidentIds, year);
                parsed = parsed.stream().map(accident -> accident.withParticipants(
                        participants.getOrDefault(accident.getAccidentId(), List.of()))).toList();
            }
        }
        if (vehiclesObject != null) {
            try (InputStream input = accidentStore.getResource(vehiclesObject);
                 var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                var vehicles = new ONISRAccidentVehicleParser().parseVehicles(reader, accidentIds, year);
                parsed = parsed.stream().map(accident -> accident.withVehicles(
                        vehicles.getOrDefault(accident.getAccidentId(), List.of()))).toList();
            }
            for (var accident : parsed) validateVehicleReferences(accident);
        }
        for (String region : new TreeSet<>(regionalIds)) {
            List<ONISRAccident> rows = parsed.stream().filter(row -> region.equals(row.getRegionalId()))
                    .sorted(Comparator.comparing(ONISRAccident::getAccidentId)).toList();
            putIfChanged(yearObject(region, year), json.write(rows));
        }
    }

    /** Rebuild all selected regions from stored years and current cached OSM assignments. No HTTP lookups. */
    public Map<String, Statistics> refresh() throws IOException {
        List<String> objects = accidentStore.listResources();
        Map<String, Statistics> result = new LinkedHashMap<>();
        for (String region : new TreeSet<>(regionalIds)) {
            List<ONISRAccident> records = readYears(region, objects);
            String prefix = cachePrefix(region);
            OSMInfoProviderImpl provider = providers.apply(prefix);
            provider.loadCache();
            OSMRequestCollector osmRequests = new OSMRequestCollector(provider);
            Map<Long, List<ONISRAccident>> byWay = new TreeMap<>();
            int matched = 0;
            for (ONISRAccident accident : records) {
                // Stored source records never take precedence over the current mapping cache.
                Long way = osmRequests.getWayId(accident.getAccidentId(), accident.getGeometry(), accident.getRoadReference());
                accident.setOsmWayId(way);
                if (way != null && way > 0 && accident.getGeometry() != null) {
                    byWay.computeIfAbsent(way, ignored -> new ArrayList<>()).add(accident);
                    matched++;
                }
            }
            putIfChanged(outputObject(region), json.writeByWay(byWay));
            osmRequests.publish(prefix);
            result.put(region, new Statistics(records.size(), matched, records.size() - matched, byWay.size()));
        }
        return Map.copyOf(result);
    }

    private List<ONISRAccident> readYears(String region, List<String> objects) throws IOException {
        String directory = "data/" + cachePrefix(region) + "/";
        Pattern pattern = Pattern.compile(Pattern.quote(directory) + "([0-9]{4})\\.json");
        List<ONISRAccident> records = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String name : new TreeSet<>(objects)) {
            var match = pattern.matcher(name);
            if (!match.matches()) continue;
            int year = Integer.parseInt(match.group(1));
            try (InputStream input = accidentStore.getResource(name)) {
                for (ONISRAccident accident : json.read(input)) {
                    validate(accident, region, year);
                    if (!ids.add(accident.getAccidentId())) throw new IOException("Duplicate stored accident: " + accident.getAccidentId());
                    records.add(accident);
                }
            }
        }
        records.sort(Comparator.comparing(ONISRAccident::getDate).thenComparing(ONISRAccident::getAccidentId));
        return records;
    }

    private static void validate(ONISRAccident accident, String region, int year) throws IOException {
        if (accident.getDate() == null || accident.getDate().getYear() != year
                || !region.equals(accident.getRegionalId()) || accident.getAccidentId() == null
                || !accident.getAccidentId().matches(year + "[0-9]{8}")) {
            throw new IOException("Invalid accident identity, regional ID or year for " + region + "/" + year);
        }
        Double latitude = accident.getLatitude();
        Double longitude = accident.getLongitude();
        if ((latitude != null && (!Double.isFinite(latitude) || Math.abs(latitude) > 90))
                || (longitude != null && (!Double.isFinite(longitude) || Math.abs(longitude) > 180))) {
            throw new IOException("Invalid stored accident coordinates: " + accident.getAccidentId());
        }
        Set<String> participantIds = new HashSet<>();
        for (var participant : accident.getParticipants()) {
            if (!participantIds.add(participant.getParticipantId())
                    || (participant.getBirthYear() != null && participant.getBirthYear() > year)) {
                throw new IOException("Invalid stored participant for accident: " + accident.getAccidentId());
            }
        }
        Set<String> vehicleIds = new HashSet<>();
        for (var vehicle : accident.getVehicles()) {
            if (!vehicleIds.add(vehicle.getVehicleId())) throw new IOException("Duplicate stored vehicle ID: " + vehicle.getVehicleId());
        }
        if (!accident.getVehicles().isEmpty()) validateVehicleReferences(accident);
    }

    private static void validateVehicleReferences(ONISRAccident accident) throws IOException {
        Set<String> vehicleIds = new HashSet<>();
        accident.getVehicles().forEach(vehicle -> vehicleIds.add(vehicle.getVehicleId()));
        for (var participant : accident.getParticipants()) {
            if (participant.getVehicleId() != null && !vehicleIds.contains(participant.getVehicleId())) {
                throw new IOException("Participant vehicle not found in accident " + accident.getAccidentId()
                        + ": " + participant.getVehicleId());
            }
        }
    }

    private void putIfChanged(String name, byte[] bytes) throws IOException {
        try (InputStream existing = accidentStore.getResource(name)) {
            if (Arrays.equals(existing.readAllBytes(), bytes)) return;
        } catch (BmcException missing) {
            if (missing.getStatusCode() != 404) throw missing;
        }
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            accidentStore.putResource(name, "application/json", new OOSResourceMetadata(), input);
        }
    }

    public static String cachePrefix(String region) { return "ONISR_DPT" + region; }
    public static String yearObject(String region, int year) { return "data/" + cachePrefix(region) + "/" + year + ".json"; }
    public static String outputObject(String region) { return cachePrefix(region) + "_Accidents.json"; }

    public record Statistics(int accidents, int matched, int unmatched, int ways) {}
}
