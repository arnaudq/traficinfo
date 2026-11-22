package org.traficinfo.parsers.history;

import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;

import java.util.List;

import static org.traficinfo.parsers.history.CounterHistory.*;

/**
 * Converts one datasource's stored measurements to the common history representation.
 * Each {@link GeoJsonParser.DataSource} registers its implementation alongside its parser and record class.
 * Implementations normalize integer measurements, retain peak text and coverage without mutating records, filtering map
 * populations or combining measurements from different records. One record can supply several counts.
 */
public interface Canonicalizer {
    /**
     * Normalizes a record belonging to the registered datasource.
     *
     * @param record an instance of the record class registered with this canonicalizer
     * @return source campaign descriptors and canonical measurements for subsequent grouping
     * @throws ClassCastException if the record does not belong to this datasource
     */
    Observation canonicalize(Comptage record);

    /**
     * Returns the site name without any source-specific measurement suffix.
     *
     * @param record source record supplying site metadata
     * @return count-agnostic site display name, or null when unavailable
     */
    default String displayName(Comptage record) { return CanonicalValues.text(record.getDisplayName()); }

    /**
     * Intermediate canonical measurements for one stored record, before counter/campaign grouping.
     * This record is not serialized as part of the public JSON hierarchy.
     *
     * @param discriminator source campaign descriptors supplementing year and dataset identity;
     *                      empty when the source model identifies only an annual campaign
     * @param label campaign description, or null
     * @param startDate known measurement start date in ISO date format, or null
     * @param endDate known measurement end date in ISO date format, or null
     * @param countingMode campaign operation, or DISREGARDED to exclude this record from public history
     * @param counts canonical measurements supplied by this record, including retained directions
     */
    record Observation(String discriminator, String label, String startDate, String endDate,
                       CountingMode countingMode, List<Count> counts) {
        public Observation {
            counts = List.copyOf(counts);
        }
    }
}
