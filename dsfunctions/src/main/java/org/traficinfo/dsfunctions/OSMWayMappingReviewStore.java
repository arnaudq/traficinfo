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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.model.BmcException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

/** The global manual-review document, grouped by data source prefix and then counter ID. */
class OSMWayMappingReviewStore {
    static final String OBJECT_NAME = "OSMWayMapping_NEEDS_REVIEW.json";
    static final String NOT_FOUND_OBJECT_NAME = "OSMWayMapping_NOT_FOUND.json";
    private final String objectName;
    private final ObjectStoreClient client;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Map<String, Map<String, OSMWayMapping>> previousReview;

    record SaveResult(boolean written, int added, int changed, int removed) { }

    OSMWayMappingReviewStore(ObjectStoreClient client) {
        this(client, OSMWayMappingStatus.NEEDS_REVIEW);
    }

    OSMWayMappingReviewStore(ObjectStoreClient client, OSMWayMappingStatus status) {
        this.client = client;
        this.objectName = "OSMWayMapping_" + status.name() + ".json";
    }

    String getObjectName() {
        return objectName;
    }

    Map<String, Map<String, OSMWayMapping>> load() throws IOException {
        try (InputStream input = client.getResource(objectName)) {
            Map<String, Map<String, OSMWayMapping>> review = mapper.readValue(input,
                    new TypeReference<TreeMap<String, Map<String, OSMWayMapping>>>() { });
            if (review == null || review.values().stream().anyMatch(group -> group == null
                    || group.values().stream().anyMatch(record -> record == null || record.getStatus() == null))) {
                throw new IOException("OSM review must contain data source objects with non-null mapping records and statuses");
            }
            // Snapshot before approvals or cache refreshes can change any mapping records.
            previousReview = snapshot(review);
            return review;
        } catch (BmcException failure) {
            if (failure.getStatusCode() == 404) {
                previousReview = null;
                return new TreeMap<>();
            }
            throw failure;
        }
    }

    SaveResult save(Map<String, Map<String, OSMWayMapping>> review) throws IOException {
        Map<String, Map<String, OSMWayMapping>> current = snapshot(review);
        Map<String, Map<String, OSMWayMapping>> previous = previousReview == null ? Map.of() : previousReview;
        int added = 0;
        int changed = 0;
        int removed = 0;
        for (var source : current.entrySet()) {
            for (var record : source.getValue().entrySet()) {
                OSMWayMapping oldRecord = previous.getOrDefault(source.getKey(), Map.of()).get(record.getKey());
                if (oldRecord == null) added++;
                else if (!oldRecord.equals(record.getValue())) changed++;
            }
        }
        for (var source : previous.entrySet()) {
            for (String counterId : source.getValue().keySet()) {
                if (!current.getOrDefault(source.getKey(), Map.of()).containsKey(counterId)) removed++;
            }
        }
        if (previousReview != null && previousReview.equals(current)) {
            return new SaveResult(false, added, changed, removed);
        }
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(review);
        try (InputStream input = new ByteArrayInputStream(json)) {
            client.putResource(objectName, "application/json", new OOSResourceMetadata(), input);
        }
        previousReview = current;
        return new SaveResult(true, added, changed, removed);
    }

    private static Map<String, Map<String, OSMWayMapping>> snapshot(
            Map<String, Map<String, OSMWayMapping>> review) {
        Map<String, Map<String, OSMWayMapping>> copy = new TreeMap<>();
        review.forEach((source, records) -> {
            Map<String, OSMWayMapping> group = new TreeMap<>();
            records.forEach((id, mapping) -> group.put(id, new OSMWayMapping(mapping)));
            copy.put(source, group);
        });
        return copy;
    }
}
