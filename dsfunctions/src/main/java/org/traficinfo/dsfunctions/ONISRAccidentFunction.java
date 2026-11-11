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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Manual ONISR entry point importing the explicitly configured yearly publications. */
public class ONISRAccidentFunction {
    private static final Logger LOG = LoggerFactory.getLogger(ONISRAccidentFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String ACCIDENT_BUCKET = "onisrinfo";
    private String namespace;
    private String osmBucket;
    private boolean principal;
    private boolean configured;

    protected ObjectStoreClient createObjectStoreClient(boolean principal, String namespace, String bucket) {
        return new ObjectStoreClient(principal, namespace, bucket);
    }

    @FnConfiguration
    public void config(RuntimeContext context) {
        namespace = context.getConfigurationByKey("NAMESPACE").orElse("");
        osmBucket = context.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        principal = Boolean.parseBoolean(context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        configured = true;
    }

    /** Empty input or {} imports configured years; {"action":"refresh"} rebuilds stored accident output only. */
    public String handleRequest(String input) {
        if (!configured) throw new IllegalStateException("Accident function has not been configured");
        String action = action(input);
        try (var logging = LoggingContext.forSource("accidents", "ONISR")) {
            try (var accidentStore = createObjectStoreClient(principal, namespace, ACCIDENT_BUCKET);
                 var osmStore = createObjectStoreClient(principal, namespace, osmBucket)) {
                var processor = new ONISRAccidentProcessor(accidentStore, osmStore);
                Map<String, ONISRAccidentProcessor.Statistics> result = action.equals("refresh")
                        ? processor.refresh() : processor.importYears(ONISRYearInputs.configured());
                result.forEach((region, statistics) -> LOG.info("ONISR regional ID {}: {}", region, statistics));
                return "OK";
            } catch (IOException failure) {
                LOG.error("ONISR accident processing failed", failure);
                throw new UncheckedIOException("ONISR accident processing failed", failure);
            } catch (RuntimeException failure) {
                LOG.error("ONISR accident processing failed", failure);
                throw failure;
            }
        }
    }

    private static String action(String input) {
        if (input == null || input.isBlank()) return "import";
        try {
            Request request = JSON.readValue(input, Request.class);
            if (request == null) throw new IllegalArgumentException("Expected an accident request object");
            String action = request.action() == null ? "import" : request.action();
            if (!action.equals("import") && !action.equals("refresh")) {
                throw new IllegalArgumentException("Expected action import or refresh");
            }
            return action;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid accident request", invalid);
        }
    }

    public record Request(String action) {}
}
