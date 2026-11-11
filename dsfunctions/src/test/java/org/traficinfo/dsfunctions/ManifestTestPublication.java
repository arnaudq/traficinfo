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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.storage.ComptageJson;
import org.traficinfo.parsers.storage.DataManifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Small published fixtures; CSV is used only to prepare stored records before handler invocation. */
public final class ManifestTestPublication {
    private ManifestTestPublication() {}

    public static String yearObjectName(GeoJsonDataSource source, int year) {
        return "data/revision-" + year + "/" + source.getDataSourceId() + "_Comptage_" + year + ".json";
    }

    public static Map<String, byte[]> fromCsv(GeoJsonDataSource source, String csv) throws IOException {
        return fromRecords(source, source.getParserDataSource().getParser().parseComptages(
                new StringReader(csv.startsWith("\uFEFF") ? csv.substring(1) : csv)));
    }

    public static Map<String, byte[]> fromRecords(GeoJsonDataSource source, List<? extends Comptage> counts) throws IOException {
        ComptageJson json = new ComptageJson();
        Map<Integer, List<byte[]>> byYear = new TreeMap<>();
        for (var count : counts) {
            byYear.computeIfAbsent(count.getAnnee(), ignored -> new ArrayList<>()).add(json.write(count));
        }
        Map<String, byte[]> objects = new HashMap<>();
        Map<String, DataManifest.Dataset> datasets = new HashMap<>();
        Map<Integer, DataManifest.YearData> years = new HashMap<>();
        String active = "empty";
        for (var entry : byYear.entrySet()) {
            int year = entry.getKey();
            active = "dataset-" + year;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write('[');
            for (int index = 0; index < entry.getValue().size(); index++) {
                if (index > 0) output.write(',');
                output.write(entry.getValue().get(index));
            }
            output.write(']');
            byte[] bytes = output.toByteArray();
            String name = yearObjectName(source, year);
            objects.put(name, bytes);
            String checksum;
            try { checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            years.put(year, new DataManifest.YearData(name, checksum, entry.getValue().size(), active, "1"));
            datasets.put(active, new DataManifest.Dataset("https://example.invalid/" + active, List.of(year),
                    Map.of(DataSourceTestSettings.settings(source.getDataSourceId()).inputs().getLast().objectName(), new DataManifest.Input("https://example.invalid/counts",
                            source == GeoJsonDataSource.DPT76 ? "GEOJSON" : "CSV",
                            source == GeoJsonDataSource.DPT76 ? null : List.of("year")))));
        }
        if (byYear.isEmpty()) {
            datasets.put(active, new DataManifest.Dataset("https://example.invalid/empty", List.of(java.time.Year.now().getValue()),
                    Map.of(DataSourceTestSettings.settings(source.getDataSourceId()).inputs().getLast().objectName(), new DataManifest.Input("https://example.invalid/counts",
                            source == GeoJsonDataSource.DPT76 ? "GEOJSON" : "CSV",
                            source == GeoJsonDataSource.DPT76 ? null : List.of("year")))));
        }
        objects.put(source.getDataSourceId() + "_Manifest.json", new ObjectMapper().writeValueAsBytes(
                new DataManifest(1, source.getDataSourceId(), "publication", "2026-10-29T00:00:00Z", active, datasets, years)));
        return objects;
    }
}
