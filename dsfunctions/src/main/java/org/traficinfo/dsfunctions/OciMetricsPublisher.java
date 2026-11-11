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

import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.monitoring.Monitoring;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.Datapoint;
import com.oracle.bmc.monitoring.model.MetricDataDetails;
import com.oracle.bmc.monitoring.model.PostMetricDataDetails;
import com.oracle.bmc.monitoring.requests.PostMetricDataRequest;
import com.oracle.bmc.retrier.RetryConfiguration;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** OCI transport only; metric names, values and dimensions are supplied by the application. */
public final class OciMetricsPublisher implements MetricsPublisher, AutoCloseable {
    private final Monitoring client;
    private final String compartmentId;
    private final String namespace;

    public OciMetricsPublisher(Monitoring client, String compartmentId, String namespace) {
        this.client = Objects.requireNonNull(client);
        this.compartmentId = Objects.requireNonNull(compartmentId);
        this.namespace = Objects.requireNonNull(namespace);
    }

    public static OciMetricsPublisher create(boolean principal, String compartmentId, String namespace)
            throws IOException {
        var provider = principal ? ResourcePrincipalAuthenticationDetailsProvider.builder().build()
                : new ConfigFileAuthenticationDetailsProvider("arnaudq");
        var client = MonitoringClient.builder()
                .configuration(ClientConfiguration.builder().connectionTimeoutMillis(2000)
                        .readTimeoutMillis(3000).retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                        .build())
                .build(provider);
        // Monitoring queries and ingestion use different endpoints. Resolve the SDK's regional
        // endpoint first so region and realm selection remain the SDK's responsibility.
        client.setEndpoint(client.getEndpoint().replace("https://telemetry.", "https://telemetry-ingestion."));
        return new OciMetricsPublisher(client, compartmentId, namespace);
    }

    @Override
    public void publish(List<MetricSample> samples) {
        // OCI accepts at most 50 metric streams per request.
        for (int start = 0; start < samples.size(); start += 50) {
            var data = samples.subList(start, Math.min(start + 50, samples.size())).stream()
                    .map(sample -> MetricDataDetails.builder()
                            .compartmentId(compartmentId).namespace(namespace).name(sample.name())
                            .dimensions(sample.dimensions()).metadata(Map.of("unit", sample.unit()))
                            .datapoints(List.of(Datapoint.builder().timestamp(Date.from(sample.timestamp()))
                                    .value(sample.value()).count(1).build()))
                            .build()).toList();
            var response = client.postMetricData(PostMetricDataRequest.builder()
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .postMetricDataDetails(PostMetricDataDetails.builder().metricData(data).build()).build());
            var result = response.getPostMetricDataResponseDetails();
            if (result != null && result.getFailedMetricsCount() != null && result.getFailedMetricsCount() > 0) {
                throw new IllegalStateException("OCI rejected " + result.getFailedMetricsCount() + " metrics: "
                        + result.getFailedMetrics());
            }
        }
    }

    @Override
    public void close() throws Exception {
        client.close();
    }
}
