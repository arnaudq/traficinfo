package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.RuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Year;
import java.util.HashSet;
import java.util.List;

/** Settings for either the deployed active dataset or an explicitly requested archive import. */
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
