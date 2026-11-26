package org.traficinfo.dsfunctions;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Custom object metadata, including the existing publisher validator and archive date. */
public class ObjectMetadata {

    // Keep the existing persisted custom-metadata keys.
    static final String RESOURCE_METADATA_CURRENT_ETAG = "Current-ETag".toLowerCase(Locale.US);
    static final String RESOURCE_METADATA_LAST_MODIFIED = "Last-Modified".toLowerCase(Locale.US);

    private static final Pattern ETAG_PATTERN = Pattern.compile("\"([^\"]*)\"");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");


    private final Map<String, String> metadata;

    public ObjectMetadata() {
        this.metadata = new HashMap<>();
    }

    public ObjectMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    /**
     * Get the stored validator, adding back double quotes.
     * @return quoted server ETag or synthetic sha256 validator.
     */
    public String getETag() {
        String metadataETag = metadata.get(RESOURCE_METADATA_CURRENT_ETAG);
        return metadataETag != null && !metadataETag.isEmpty() ? "\"" + metadataETag + "\"" : metadataETag;
    }

    /**
     * Stores the source validator without surrounding quotes, preserving the existing metadata format.
     * @param rawETag - can be null.
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

    public String getLastModified() {
        return metadata.get(RESOURCE_METADATA_LAST_MODIFIED);
    }

    public void setLastModified(LocalDate lastModified) {
        if (lastModified != null) {
            metadata.put(RESOURCE_METADATA_LAST_MODIFIED, lastModified.format(DATE_TIME_FORMATTER));
        }
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }
}
