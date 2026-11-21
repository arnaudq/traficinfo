package org.traficinfo.dsfunctions;

import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Runs the production configuration lifecycle with in-memory storage and metrics. */
final class ConfiguredOSMFunction extends OSMInfoFunction {
    private final ObjectStoreClient store;
    private final MetricsPublisher publisher;

    ConfiguredOSMFunction(ObjectStoreClient store) {
        this(store, new OpenStreetMapClient(), System::nanoTime);
    }

    ConfiguredOSMFunction(ObjectStoreClient store, OpenStreetMapClient client, LongSupplier nanoTime) {
        this(store, client, nanoTime, samples -> { });
    }

    ConfiguredOSMFunction(ObjectStoreClient store, OpenStreetMapClient client, LongSupplier nanoTime,
                          MetricsPublisher publisher) {
        super(store, client, nanoTime);
        this.store = store;
        this.publisher = publisher;
        config(new FunctionRuntimeContext(new FunctionLoader().loadClass(OSMInfoFunction.class.getName(),
                "handleRequest"), Map.of()));
    }

    @Override protected ObjectStoreClient createObjectStoreClient(String bucket) {
        return store;
    }

    @Override protected void publishMetrics(List<MetricSample> samples) {
        publisher.publish(samples);
    }
}
