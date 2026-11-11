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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Supplies expected retrieval settings from the repository config, without network requests. */
public final class DataSourceTestSettings {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SOURCES = readSources();

    private DataSourceTestSettings() {}

    public static DataSourceSettings settings(String id) {
        JsonNode source = SOURCES.get(id);
        String datasetId = source.get("activeDatasetId").asText();
        JsonNode dataset = source.get("datasets").get(datasetId);
        ObjectNode settings = JSON.createObjectNode();
        settings.put("dataSourceId", id);
        settings.put("datasetId", datasetId);
        ArrayNode inputs = settings.putArray("inputs");
        for (JsonNode original : dataset.get("inputs")) {
            ObjectNode input = original.deepCopy();
            if (!input.has("objectName")) {
                input.put("objectName", source.path("csvObject").asText(id + "_Comptage.csv"));
            }
            inputs.add(input);
        }
        try {
            return JSON.treeToValue(settings, DataSourceSettings.class);
        } catch (IOException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static JsonNode readSources() {
        Path config = Path.of(System.getProperty("basedir", ".")).resolve("../dsconfig.json");
        try {
            return JSON.readTree(Files.readAllBytes(config));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read test settings from " + config, failure);
        }
    }
}
