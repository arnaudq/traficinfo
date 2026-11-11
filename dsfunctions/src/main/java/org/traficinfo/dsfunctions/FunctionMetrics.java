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

import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared runtime wiring; SDK clients are opened only when an invocation has metrics to publish. */
final class FunctionMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(FunctionMetrics.class);

    private FunctionMetrics() {}

    static MetricsPublisher configured(RuntimeContext context) {
        String compartment = context.getConfigurationByKey("METRICS_COMPARTMENT_ID").orElse("");
        String namespace = context.getConfigurationByKey("METRICS_NAMESPACE").orElse("traficinfo");
        boolean principal = Boolean.parseBoolean(
                context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        return samples -> {
            if (samples.isEmpty()) return;
            if (compartment.isBlank()) {
                LOG.warn("Skipping metrics: METRICS_COMPARTMENT_ID is not configured");
                return;
            }
            try (var publisher = OciMetricsPublisher.create(principal, compartment, namespace)) {
                publisher.publish(samples);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not publish metrics to OCI Monitoring", failure);
            }
        };
    }
}
