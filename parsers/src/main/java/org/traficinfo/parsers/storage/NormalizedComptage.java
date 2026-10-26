package org.traficinfo.parsers.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.geojson.GeoJsonObject;
import org.traficinfo.parsers.Comptage;

/** Source-derived yearly count. Conversion to/from Comptage is introduced separately.
 * Unknown numeric values are null; zero is a measured value. OSM enrichment is excluded. */
public record NormalizedComptage(
        @JsonProperty("codeCentrale") String codeCentrale,
        @JsonProperty("discriminator") String discriminator,
        @JsonProperty("annee") int annee,
        @JsonProperty("directionType") Comptage.DirectionType directionType,
        @JsonProperty("type") Comptage.Type type,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("cityName") String cityName,
        @JsonProperty("roadReference") String roadReference,
        @JsonProperty("geometry") GeoJsonObject geometry,
        @JsonProperty("moyenneJourAnnuelle") Integer moyenneJourAnnuelle,
        @JsonProperty("moyenneHeureDePointeMatin") String moyenneHeureDePointeMatin,
        @JsonProperty("moyenneHeureDePointeSoir") String moyenneHeureDePointeSoir,
        @JsonProperty("v85Speed") Integer v85Speed,
        @JsonProperty("meanSpeed") Integer meanSpeed) {
    @JsonCreator
    public NormalizedComptage {
    }
}
