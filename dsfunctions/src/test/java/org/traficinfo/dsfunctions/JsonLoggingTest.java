package org.traficinfo.dsfunctions;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class JsonLoggingTest {
    @Test
    public void configuredEncoderProducesSingleLineJsonWithContextAndException() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger(JsonLoggingTest.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.setContext(context);
        captured.start();
        logger.addAppender(captured);
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            try (var logging = LoggingContext.forSource("retrieval", "DPT30")) {
                logger.warn("Downloaded {}", "quoted \"value\"\nsecond line\\",
                        new IllegalStateException("failure\nwith newline"));
                // Real console output is synchronous; freeze the captured event before scope cleanup.
                captured.list.get(0).prepareForDeferredProcessing();
            }
            var console = (OutputStreamAppender<ILoggingEvent>) context
                    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
            assertNotNull(console);
            String line = new String(console.getEncoder().encode(captured.list.get(0)), StandardCharsets.UTF_8);
            assertTrue(line.endsWith("\n"));
            assertEquals(1, line.chars().filter(character -> character == '\n').count());
            JsonNode json = new ObjectMapper().readTree(line);
            assertFalse(json.has("context"));
            assertFalse(json.has("sequenceNumber"));
            assertFalse(json.has("nanoseconds"));
            assertTrue(json.path("timestamp").isIntegralNumber());
            assertEquals("WARN", json.path("level").asText());
            assertEquals("DPT30", json.path("mdc").path("datasourceId").asText());
            assertEquals("retrieval", json.path("mdc").path("stage").asText());
            assertEquals("Downloaded quoted \"value\"\nsecond line\\", json.path("formattedMessage").asText());
            assertEquals("java.lang.IllegalStateException", json.path("throwable").path("className").asText());
            assertEquals("failure\nwith newline", json.path("throwable").path("message").asText());
            assertNull(MDC.get("datasourceId"));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }

    @Test
    public void reusedWorkerRestoresContextAfterFailureAndNestedScopes() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                MDC.put("caller", "retained");
                try {
                    try (var logging = LoggingContext.forSource("retrieval", "DPT30")) {
                        try (var nested = LoggingContext.forSource("osminfo", null)) {
                            assertNull(MDC.get("datasourceId"));
                            assertEquals("osminfo", MDC.get("stage"));
                        }
                        assertEquals("DPT30", MDC.get("datasourceId"));
                        assertEquals("retrieval", MDC.get("stage"));
                        throw new IllegalStateException("invocation failed");
                    }
                } catch (IllegalStateException expected) {
                    assertEquals(Map.of("caller", "retained"), MDC.getCopyOfContextMap());
                } finally {
                    MDC.clear();
                }
            }).get();
            worker.submit(() -> {
                assertNull(MDC.get("datasourceId"));
                try (var logging = LoggingContext.forSource("geojson", "MRN")) {
                    assertEquals("MRN", MDC.get("datasourceId"));
                }
                assertNull(MDC.get("stage"));
                assertNull(MDC.get("datasourceId"));
            }).get();
        } finally {
            worker.shutdownNow();
        }
    }
}
