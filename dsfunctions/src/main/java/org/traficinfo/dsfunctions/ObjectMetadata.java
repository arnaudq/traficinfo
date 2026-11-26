package org.traficinfo.dsfunctions;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom object metadata shared by retrieval, validation and publication code.
 * This is an application-owned map, not a provider SDK response. HTTP content type,
 * content encoding and cache control are handled separately by {@link ObjectStore}.
 *
 * <p>The convenience accessors preserve the existing {@code current-etag} and
 * {@code last-modified} custom-metadata keys. The former holds a publisher HTTP
 * validator or synthetic {@code sha256:...} validator, not an Object Storage ETag.
 * It must not be treated as a provider-independent checksum of the stored object;
 * publication checksums are validated separately. Map keys are case-sensitive
 * and are not normalized by this class.
 */
public class ObjectMetadata {

    // Keep the existing persisted custom-metadata keys.
    static final String RESOURCE_METADATA_CURRENT_ETAG = "Current-ETag".toLowerCase(Locale.US);
    static final String RESOURCE_METADATA_LAST_MODIFIED = "Last-Modified".toLowerCase(Locale.US);

    private static final Pattern ETAG_PATTERN = Pattern.compile("\"([^\"]*)\"");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");


    private final Map<String, String> metadata;

    /** Creates an empty, mutable custom-metadata map. */
    public ObjectMetadata() {
        this.metadata = new HashMap<>();
    }

    /**
     * Wraps the supplied map without copying it. Changes through either reference
     * are visible through the other; setters require the map to support mutation.
     *
     * @param metadata non-null backing map of custom-metadata keys and values
     */
    public ObjectMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    /**
     * Reads the source validator, restoring surrounding double quotes for nonempty values.
     * Synthetic validators are returned in the same quoted form; the HTTP client determines
     * whether a validator is suitable for a conditional publisher request.
     *
     * @return quoted source or synthetic validator; null when absent, or an empty string when stored empty
     */
    public String getETag() {
        String metadataETag = metadata.get(RESOURCE_METADATA_CURRENT_ETAG);
        return metadataETag != null && !metadataETag.isEmpty() ? "\"" + metadataETag + "\"" : metadataETag;
    }

    /**
     * Stores the source validator without surrounding quotes, preserving the existing metadata format.
     * A null value removes the key; an empty value is retained as an empty string.
     *
     * @param rawETag source or synthetic validator, optionally quoted, or null to remove it
     */
    public void setETag(String rawETag) {
        if (rawETag == null) {
            metadata.remove(RESOURCE_METADATA_CURRENT_ETAG);
            return;
        }
        String metadataETag = rawETag;
        if (rawETag != null) {
            Matcher m = ETAG_PATTERN.matcher(rawETag);
            if (m.matches() && m.groupCount() == 1) {
                metadataETag =  m.group(1);
            }
        }
        metadata.put(RESOURCE_METADATA_CURRENT_ETAG, metadataETag);
    }

    /**
     * Reads the date used by the existing retrieval/archive lifecycle. This is custom
     * application metadata, not the storage service's Last-Modified response header.
     * The stored string is returned without parsing or validation.
     *
     * @return stored date, conventionally {@code yyyyMMdd}, or null when absent
     */
    public String getLastModified() {
        return metadata.get(RESOURCE_METADATA_LAST_MODIFIED);
    }

    /**
     * Stores the supplied date in the existing {@code yyyyMMdd} archive-date format.
     *
     * @param lastModified date to store; null leaves the current entry unchanged
     */
    public void setLastModified(LocalDate lastModified) {
        if (lastModified != null) {
            metadata.put(RESOURCE_METADATA_LAST_MODIFIED, lastModified.format(DATE_TIME_FORMATTER));
        }
    }

    /**
     * Returns the backing map directly so callers can read/write datasource identity,
     * provenance and other application metadata. No defensive copy is made.
     *
     * @return backing custom-metadata map
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }
}
