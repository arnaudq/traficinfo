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

import java.net.URI;
import java.time.Year;
import java.util.HashSet;
import java.util.List;

/** Settings for either the deployed active dataset or an explicitly requested archive import. */
public record PreprocessingSettings(DataSourceSettings source, String datasetUrl, List<Integer> years) {

    public PreprocessingSettings {
        java.util.Objects.requireNonNull(source, "source");
        URI uri = URI.create(datasetUrl);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("datasetUrl must be an absolute HTTP(S) URL");
        }
        years = List.copyOf(years);
        if (years.isEmpty() || new HashSet<>(years).size() != years.size()
                || years.stream().anyMatch(year -> year < 1900 || year > Year.now().getValue())) {
            throw new IllegalArgumentException("Expected distinct, explicit preprocessing years");
        }
        PreprocessingSource parser = PreprocessingSource.forId(source.dataSourceId());
        long countsInputs = source.inputs().stream()
                .filter(input -> input.contentType().equals(parser.countsContentType)).count();
        boolean mmm = source.dataSourceId().equals("MMM");
        if (countsInputs != 1 || source.inputs().size() != (mmm ? 2 : 1)
                || (mmm && source.inputs().stream().noneMatch(input -> input.objectName().endsWith(".json")))) {
            throw new IllegalArgumentException("Expected one counts input of type " + parser.countsContentType
                    + " and, for MMM, one companion geometry input");
        }
    }
}
