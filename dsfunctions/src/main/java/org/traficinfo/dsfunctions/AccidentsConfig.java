package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import java.io.*;
import java.util.*;

/** Bundled list of accident datasets to publish; IDs define input and output object names. */
public final class AccidentsConfig {
    public record Source(String id, String sourceBucket) {
        public Source {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid accident source ID");
            DataSourceRegistry.validateBucket(sourceBucket);
        }
    }

    public static List<Source> bundled() {
        try (InputStream input = AccidentsConfig.class.getResourceAsStream("/accidentsConfig.json")) {
            if (input == null) throw new IOException("Missing bundled accidentsConfig.json");
            return read(input);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Cannot load accidentsConfig.json", invalid);
        }
    }

    static List<Source> read(InputStream input) throws IOException {
        var json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        List<Source> sources = json.readerFor(json.getTypeFactory().constructCollectionType(List.class, Source.class))
                .without(JsonParser.Feature.AUTO_CLOSE_SOURCE).readValue(input);
        if (sources == null) throw new IOException("Expected an accident publication list");
        Set<String> ids = new HashSet<>();
        for (Source source : sources) {
            if (source == null || !ids.add(source.id())) throw new IOException("Null or duplicate accident publication ID");
        }
        return List.copyOf(sources);
    }
}
