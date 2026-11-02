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
