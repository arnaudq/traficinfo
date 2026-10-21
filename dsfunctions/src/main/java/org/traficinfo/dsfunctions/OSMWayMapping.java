package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** A counter's cached OSM way assignment, location, and review metadata. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OSMWayMapping {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonDeserialize(using = WayIdDeserializer.class)
    private Long osmWayId;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Double latitude;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Double longitude;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    // Source road identifier retained for later comparison: D0011 may match OSM ref D 11.
    private String sourceRoadReference;
    private OSMWayMappingStatus status = OSMWayMappingStatus.MISSING;
    private String firstSeen;
    private String lastSeen;
    private String lastUpdated;
    @JsonDeserialize(using = WayIdDeserializer.class)
    private Long candidateOsmWayId;
    private String osmRoadReference;
    private String reviewReason;
    private Double distanceMeters;
    private Double lastLookupLatitude;
    private Double lastLookupLongitude;
    private String lastLookupAt;
    private JsonNode reverseLookupResult;
    private final Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

    public Long getOsmWayId() {
        return osmWayId;
    }

    public void setOsmWayId(Long osmWayId) {
        this.osmWayId = osmWayId;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getSourceRoadReference() {
        return sourceRoadReference;
    }

    public void setSourceRoadReference(String sourceRoadReference) {
        this.sourceRoadReference = sourceRoadReference;
    }

    public OSMWayMappingStatus getStatus() {
        return status;
    }

    public void setStatus(OSMWayMappingStatus status) {
        this.status = status;
    }

    public String getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(String firstSeen) {
        this.firstSeen = firstSeen;
    }

    public String getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(String lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Long getCandidateOsmWayId() {
        return candidateOsmWayId;
    }

    public void setCandidateOsmWayId(Long candidateOsmWayId) {
        this.candidateOsmWayId = candidateOsmWayId;
    }

    public String getOsmRoadReference() {
        return osmRoadReference;
    }

    public void setOsmRoadReference(String osmRoadReference) {
        this.osmRoadReference = osmRoadReference;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public Double getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(Double distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public Double getLastLookupLatitude() {
        return lastLookupLatitude;
    }

    public void setLastLookupLatitude(Double lastLookupLatitude) {
        this.lastLookupLatitude = lastLookupLatitude;
    }

    public Double getLastLookupLongitude() {
        return lastLookupLongitude;
    }

    public void setLastLookupLongitude(Double lastLookupLongitude) {
        this.lastLookupLongitude = lastLookupLongitude;
    }

    public String getLastLookupAt() {
        return lastLookupAt;
    }

    public void setLastLookupAt(String lastLookupAt) {
        this.lastLookupAt = lastLookupAt;
    }

    public JsonNode getReverseLookupResult() {
        return reverseLookupResult;
    }

    public void setReverseLookupResult(JsonNode reverseLookupResult) {
        this.reverseLookupResult = reverseLookupResult;
    }

    /** Preserve extra seed annotations without coupling the model to every audit field. */
    @JsonAnyGetter
    public Map<String, JsonNode> getAdditionalProperties() {
        return additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, JsonNode value) {
        additionalProperties.put(name, value);
    }

    /** Avoid coercing string or fractional IDs into usable OSM way identifiers. */
    public static class WayIdDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                BigInteger id = parser.getBigIntegerValue();
                return id.bitLength() <= 63 ? id.longValue() : null;
            }
            parser.skipChildren();
            return null;
        }
    }
}
