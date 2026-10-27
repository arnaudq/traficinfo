package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Yearly storage preserves source fields, including null measures, without derived getter aliases. */
public final class ComptageJson {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public ComptageJson() {
        for (GeoJsonParser.DataSource source : GeoJsonParser.DataSource.values()) {
            mapper.addMixIn(source.getDsComptageClass(), StoredFields.class);
        }
    }

    public byte[] write(Comptage count) throws IOException { return mapper.writeValueAsBytes(count); }

    public List<? extends Comptage> read(InputStream input, Class<? extends Comptage> dsComptageClass) throws IOException {
        return mapper.readerFor(mapper.getTypeFactory().constructCollectionType(List.class, dsComptageClass))
                .without(com.fasterxml.jackson.core.JsonParser.Feature.AUTO_CLOSE_SOURCE).readValue(input);
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE,
            setterVisibility = JsonAutoDetect.Visibility.NONE)
    private abstract static class StoredFields {}
}
