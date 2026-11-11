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

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.storage.ComptageJson;
import org.traficinfo.parsers.storage.DataManifest;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Shared checks for manifest publishers and consumers. */
final class ManifestValidation {
    private ManifestValidation() {}

    static void validateManifest(DataManifest manifest, String dataSourceId) throws IOException {
        if (manifest == null || !dataSourceId.equals(manifest.dataSourceId())
                || manifest.revision() == null || manifest.revision().isBlank()
                || manifest.publishedAt() == null || !manifest.publishedAt().endsWith("Z")
                || manifest.activeDatasetId() == null || !manifest.datasets().containsKey(manifest.activeDatasetId())) {
            throw new IOException("Invalid manifest identity");
        }
        try { Instant.parse(manifest.publishedAt()); }
        catch (RuntimeException invalid) { throw new IOException("Invalid manifest timestamp", invalid); }
        for (DataManifest.Dataset dataset : manifest.datasets().values()) {
            if (dataset.years().isEmpty() || dataset.inputs().isEmpty()) {
                throw new IOException("Incomplete manifest dataset scope");
            }
        }
        for (var entry : manifest.years().entrySet()) {
            DataManifest.YearData year = entry.getValue();
            DataManifest.Dataset dataset = year.datasetId() == null ? null : manifest.datasets().get(year.datasetId());
            if (dataset == null || !dataset.years().contains(entry.getKey()) || year.recordCount() <= 0
                    || year.sha256() == null || !year.sha256().matches("[0-9a-f]{64}")
                    || year.object() == null || !year.object().matches("data/[A-Za-z0-9_-]+/" + dataSourceId + "_Comptage_" + entry.getKey() + "\\.json")
                    || year.parserVersion() == null || year.parserVersion().isBlank()) {
                throw new IOException("Invalid manifest year: " + entry.getKey());
            }
        }
    }

    /**
     * Reads a stored yearly Object as the datasource's Comptage class and checks its record count,
     * each record's year and the SHA-256 checksum of the exact decompressed bytes against the manifest.
     *
     * @throws IOException if reading, deserialization or any consistency check fails
     */
    static List<? extends Comptage> readYearlyObject(ObjectStoreClient storage, int year,
                                                   DataManifest.YearData data, Class<? extends Comptage> countClass) throws IOException {
        MessageDigest digest = digest();
        try (InputStream raw = storage.getResource(data.object()); DigestInputStream input = new DigestInputStream(raw, digest)) {
            List<? extends Comptage> counts = new ComptageJson().read(input, countClass);
            input.transferTo(java.io.OutputStream.nullOutputStream());
            if (counts == null || counts.size() != data.recordCount()
                    || counts.stream().anyMatch(count -> count == null || count.getAnnee() != year)
                    || !HexFormat.of().formatHex(digest.digest()).equals(data.sha256())) {
                throw new IOException("Yearly Object verification failed: " + data.object());
            }
            return counts;
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
