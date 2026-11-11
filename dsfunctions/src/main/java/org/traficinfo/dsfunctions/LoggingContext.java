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

import org.slf4j.MDC;

import java.util.Map;

/** Invocation-scoped logging fields, restored when a reused thread leaves the scope. */
final class LoggingContext implements AutoCloseable {
    private final Map<String, String> previous = MDC.getCopyOfContextMap();

    private LoggingContext(String stage, String datasourceId) {
        MDC.put("stage", stage);
        if (datasourceId == null) {
            MDC.remove("datasourceId");
        } else {
            MDC.put("datasourceId", datasourceId);
        }
    }

    static LoggingContext forSource(String stage, String datasourceId) {
        return new LoggingContext(stage, datasourceId);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
