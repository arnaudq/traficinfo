package org.traficinfo.dsfunctions;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OOSResourceMetadata {

    // metadata key returned in lower case by OOS
    static final String OOS_RESOURCE_METADATA_CURRENT_ETAG = "Current-ETag".toLowerCase(Locale.US);
    static final String OOS_RESOURCE_METADATA_LAST_MODIFIED = "Last-Modified".toLowerCase(Locale.US);

    private static final Pattern ETAG_PATTERN = Pattern.compile("\"([^\"]*)\"");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");


    private final Map<String, String> metadata;

    public OOSResourceMetadata() {
        this.metadata = new HashMap<>();
    }

    public OOSResourceMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    /**
     * Get the stored validator, adding back double quotes.
     * @return quoted server ETag or synthetic sha256 validator.
     */
    public String getETag() {
        String metadataETag = metadata.get(OOS_RESOURCE_METADATA_CURRENT_ETAG);
        return metadataETag != null && !metadataETag.isEmpty() ? "\"" + metadataETag + "\"" : metadataETag;
    }

    /**
     * set etag to metadata table after stripping the double quotes which are actually removed by OOS.
     * We can not rely on that behaviour being consistent so removing them unconditionally.
     * @param rawETag - can be null.
     */
    public void setETag(String rawETag) {
        if (rawETag == null) {
            metadata.remove(OOS_RESOURCE_METADATA_CURRENT_ETAG);
            return;
        }
        String metadataETag = rawETag;
        if (rawETag != null) {
            Matcher m = ETAG_PATTERN.matcher(rawETag);
            if (m.matches() && m.groupCount() == 1) {
                metadataETag =  m.group(1);
            }
        }
        metadata.put(OOS_RESOURCE_METADATA_CURRENT_ETAG, metadataETag);
    }

    public String getLastModified() {
        return metadata.get(OOS_RESOURCE_METADATA_LAST_MODIFIED);
    }

    public void setLastModified(LocalDate lastModified) {
        if (lastModified != null) {
            metadata.put(OOS_RESOURCE_METADATA_LAST_MODIFIED, lastModified.format(DATE_TIME_FORMATTER));
        }
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }
}
