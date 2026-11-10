package org.traficinfo.parsers.onisr;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** Stable field-based accident JSON, with ISO local dates/times and no derived getter aliases. */
public final class ONISRAccidentJson {
    private final ObjectMapper mapper;

    public ONISRAccidentJson() {
        SimpleModule time = new SimpleModule();
        time.addSerializer(LocalDate.class, new JsonSerializer<LocalDate>() {
            @Override public void serialize(LocalDate value, JsonGenerator out, SerializerProvider provider) throws IOException {
                out.writeString(value.toString());
            }
        });
        time.addSerializer(LocalTime.class, new JsonSerializer<LocalTime>() {
            @Override public void serialize(LocalTime value, JsonGenerator out, SerializerProvider provider) throws IOException {
                out.writeString(value.toString());
            }
        });
        time.addDeserializer(LocalDate.class, new JsonDeserializer<LocalDate>() {
            @Override public LocalDate deserialize(JsonParser input, DeserializationContext context) throws IOException {
                try {
                    return LocalDate.parse(input.getText());
                } catch (DateTimeParseException invalid) {
                    throw new IOException("Invalid accident date", invalid);
                }
            }
        });
        time.addDeserializer(LocalTime.class, new JsonDeserializer<LocalTime>() {
            @Override public LocalTime deserialize(JsonParser input, DeserializationContext context) throws IOException {
                try {
                    return LocalTime.parse(input.getText());
                } catch (DateTimeParseException invalid) {
                    throw new IOException("Invalid accident time", invalid);
                }
            }
        });
        mapper = new ObjectMapper().registerModule(time)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .addMixIn(ONISRAccident.class, StoredFields.class);
    }

    public byte[] write(List<ONISRAccident> accidents) throws IOException {
        return mapper.writeValueAsBytes(accidents);
    }

    public byte[] writeByWay(Map<Long, List<ONISRAccident>> accidents) throws IOException {
        return mapper.writeValueAsBytes(accidents);
    }

    /** The caller retains ownership of the stream. */
    public List<ONISRAccident> read(InputStream input) throws IOException {
        List<ONISRAccident> accidents = mapper.readerFor(
                mapper.getTypeFactory().constructCollectionType(List.class, ONISRAccident.class))
                .without(JsonParser.Feature.AUTO_CLOSE_SOURCE).readValue(input);
        if (accidents == null || accidents.stream().anyMatch(accident -> accident == null)) {
            throw new IOException("Expected an accident JSON array without null records");
        }
        return accidents;
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE,
            setterVisibility = JsonAutoDetect.Visibility.NONE)
    private abstract static class StoredFields {
        @JsonCreator
        StoredFields(@JsonProperty("accidentId") String accidentId,
                     @JsonProperty("date") LocalDate date,
                     @JsonProperty("time") LocalTime time,
                     @JsonProperty("regionalId") String regionalId,
                     @JsonProperty("municipalityId") String municipalityId,
                     @JsonProperty("address") String address,
                     @JsonProperty("latitude") Double latitude,
                     @JsonProperty("longitude") Double longitude,
                     @JsonProperty("sourceFields") Map<String, String> sourceFields) {}
    }
}
