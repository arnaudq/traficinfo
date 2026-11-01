package org.traficinfo.dsfunctions;

import org.traficinfo.parsers.GeoJsonParser;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Parser versions must change whenever the stored interpretation of a source changes. */
enum PreprocessingSource {
    MMM(GeoJsonParser.DataSource.M3MV2, ',',
            List.of("code_centrale", "annee", "mja_jo", "type_compteur")),
    DPT30(GeoJsonParser.DataSource.DPT30, ',',
            List.of("id_tmja", "annee", "tmja_tous_vehicules")),
    DPT76(GeoJsonParser.DataSource.DPT76, "application/geo+json"),
    DPT34(GeoJsonParser.DataSource.DPT34, ';',
            List.of("Identifiant du compteur", "Année", "mja_tv_tcj")),
    MRN(GeoJsonParser.DataSource.MRN, ';',
            List.of("Id_SIG", "Année", "TV_TMJA", "Type de Campagne")),
    NIM(GeoJsonParser.DataSource.NIM, ';',
            List.of("id_camera", "date", "tranche", "nbpassage"));

    final GeoJsonParser.DataSource parser;
    final char separator;
    final List<String> requiredHeaders;
    final String countsContentType;
    final String parserVersion = "1";

    PreprocessingSource(GeoJsonParser.DataSource parser, char separator, List<String> requiredHeaders) {
        this.parser = parser;
        this.separator = separator;
        this.requiredHeaders = requiredHeaders;
        this.countsContentType = "text/csv";
    }

    PreprocessingSource(GeoJsonParser.DataSource parser, String countsContentType) {
        this.parser = parser;
        this.countsContentType = countsContentType;
        this.separator = '\0';
        this.requiredHeaders = List.of();
    }

    String headerKey(String header) {
        String key = header.trim().toLowerCase(Locale.ROOT);
        if (this == NIM) {
            key = Normalizer.normalize(key, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                    .replaceAll("[\\s_]", "");
        }
        return key;
    }

    static PreprocessingSource forId(String dataSourceId) {
        return Arrays.stream(values()).filter(source -> source.name().equals(dataSourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported datasource: " + dataSourceId));
    }
}
