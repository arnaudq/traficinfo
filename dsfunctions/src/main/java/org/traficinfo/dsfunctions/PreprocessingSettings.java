package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.RuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Year;
import java.util.HashSet;
import java.util.List;

/** Deployment supplies only the active dataset and its explicit expected years. */
public record PreprocessingSettings(DataSourceSettings source, String datasetUrl, List<Integer> years) {
    public static final String CONFIG_KEY = "PREPROCESSING_SETTINGS";

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
        PreprocessingSource.forId(source.dataSourceId());
        long csvInputs = source.inputs().stream().filter(input -> input.contentType().equals("text/csv")).count();
        boolean mmm = source.dataSourceId().equals("MMM");
        if (csvInputs != 1 || source.inputs().size() != (mmm ? 2 : 1)
                || (mmm && source.inputs().stream().noneMatch(input -> input.objectName().endsWith(".json")))) {
            throw new IllegalArgumentException("Expected one counts CSV and, for MMM, one companion geometry input");
        }
    }

    public static PreprocessingSettings from(RuntimeContext context) {
        String json = context.getConfigurationByKey(CONFIG_KEY)
                .orElseThrow(() -> new IllegalArgumentException("Missing " + CONFIG_KEY));
        try {
            return new ObjectMapper().readValue(json, PreprocessingSettings.class);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid " + CONFIG_KEY, invalid);
        }
    }
}
