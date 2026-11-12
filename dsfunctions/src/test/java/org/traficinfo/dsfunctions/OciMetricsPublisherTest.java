package org.traficinfo.dsfunctions;

import com.oracle.bmc.monitoring.Monitoring;
import com.oracle.bmc.monitoring.model.PostMetricDataResponseDetails;
import com.oracle.bmc.monitoring.requests.PostMetricDataRequest;
import com.oracle.bmc.monitoring.responses.PostMetricDataResponse;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class OciMetricsPublisherTest {
    private final List<PostMetricDataRequest> requests = new ArrayList<>();
    private int rejected;
    private boolean closed;

    private Monitoring client() {
        return (Monitoring) Proxy.newProxyInstance(Monitoring.class.getClassLoader(),
                new Class<?>[]{Monitoring.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closed = true;
                        return null;
                    }
                    assertEquals("postMetricData", method.getName());
                    requests.add((PostMetricDataRequest) args[0]);
                    return PostMetricDataResponse.builder().postMetricDataResponseDetails(
                            PostMetricDataResponseDetails.builder().failedMetricsCount(rejected).build()).build();
                });
    }

    private MetricSample sample() {
        return new MetricSample("GeoJsonFeatures", 0, "count", Instant.parse("2026-11-03T10:00:00Z"),
                Map.of("datasourceId", "MMM", "population", "BICYCLES"));
    }

    @Test
    public void mapsObservationToOciAndClosesClient() throws Exception {
        var sample = sample();
        try (var publisher = new OciMetricsPublisher(client(), "compartment", "traficinfo")) {
            publisher.publish(List.of(sample));
        }
        assertTrue(closed);
        assertEquals(1, requests.size());
        var data = requests.get(0).getPostMetricDataDetails().getMetricData();
        assertEquals(1, data.size());
        var metric = data.get(0);
        assertEquals("compartment", metric.getCompartmentId());
        assertEquals("traficinfo", metric.getNamespace());
        assertEquals(sample.name(), metric.getName());
        assertEquals(sample.dimensions(), metric.getDimensions());
        assertEquals(Map.of("unit", "count"), metric.getMetadata());
        assertEquals(1, metric.getDatapoints().size());
        var point = metric.getDatapoints().get(0);
        assertEquals(0.0, point.getValue(), 0);
        assertEquals(Date.from(sample.timestamp()), point.getTimestamp());
        assertEquals(Integer.valueOf(1), point.getCount());
    }

    @Test
    public void skipsEmptyBatchesAndSplitsBatchesAtOciLimit() throws Exception {
        try (var publisher = new OciMetricsPublisher(client(), "compartment", "traficinfo")) {
            publisher.publish(List.of());
            assertTrue(requests.isEmpty());
            publisher.publish(Collections.nCopies(51, sample()));
        }
        assertEquals(List.of(50, 1), requests.stream()
                .map(request -> request.getPostMetricDataDetails().getMetricData().size()).toList());
    }

    @Test
    public void reportsPartialRejection() throws Exception {
        rejected = 1;
        try (var publisher = new OciMetricsPublisher(client(), "compartment", "traficinfo")) {
            try {
                publisher.publish(List.of(sample()));
                fail("Expected rejected metric failure");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("OCI rejected 1 metrics"));
            }
        }
        assertTrue(closed);
    }
}
