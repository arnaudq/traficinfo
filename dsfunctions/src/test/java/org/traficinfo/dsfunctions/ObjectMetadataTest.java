package org.traficinfo.dsfunctions;

import org.junit.Test;

import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class ObjectMetadataTest {

    @Test
    public void testGetETag() {
        Map<String, String> metadataMap = new HashMap<>();
        ObjectMetadata metadata = new ObjectMetadata(metadataMap);
        assertNull(metadata.getETag());

        metadataMap.put(ObjectMetadata.RESOURCE_METADATA_CURRENT_ETAG, "12");
        assertEquals("\"12\"", metadata.getETag());
    }

    @Test
    public void testSetETag() {
        ObjectMetadata metadata = new ObjectMetadata();

        metadata.setETag(null);
        assertNull(metadata.getETag());

        metadata.setETag("");
        assertEquals("", metadata.getETag());

        metadata.setETag("12");
        assertEquals("12", metadata.getMetadata().get(ObjectMetadata.RESOURCE_METADATA_CURRENT_ETAG));
        assertEquals("\"12\"", metadata.getETag());

        metadata.setETag("\"12\"");
        assertEquals("12", metadata.getMetadata().get(ObjectMetadata.RESOURCE_METADATA_CURRENT_ETAG));
        assertEquals("\"12\"", metadata.getETag());

        metadata.setETag(null);
        assertNull(metadata.getETag());
        assertFalse(metadata.getMetadata().containsKey(ObjectMetadata.RESOURCE_METADATA_CURRENT_ETAG));
    }

    @Test
    public void testGetLastModified() {
        Map<String, String> metadataMap = new HashMap<>();
        ObjectMetadata metadata = new ObjectMetadata(metadataMap);
        assertNull(metadata.getLastModified());

        metadataMap.put(ObjectMetadata.RESOURCE_METADATA_LAST_MODIFIED, "2007");
        assertEquals("2007", metadata.getLastModified());
    }

    @Test
    public void testSetLastModified() {
        ObjectMetadata metadata = new ObjectMetadata();

        metadata.setLastModified(null);
        assertNull(metadata.getLastModified());

        metadata.setLastModified(new Date(0).toInstant().atZone(ZoneId.of("GMT")).toLocalDate());
        assertEquals("19700101", metadata.getLastModified());
    }
}
