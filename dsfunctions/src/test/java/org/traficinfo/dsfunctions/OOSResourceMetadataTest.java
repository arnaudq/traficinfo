/*
 * This file is part of TraficInfo.
 * Copyright (C) 2024, 2026 Arnaud Quillaud
 *
 * TraficInfo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * TraficInfo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with TraficInfo. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.traficinfo.dsfunctions;

import org.junit.Test;

import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class OOSResourceMetadataTest {

    @Test
    public void testGetETag() {
        Map<String, String> metadataMap = new HashMap<>();
        OOSResourceMetadata metadata = new OOSResourceMetadata(metadataMap);
        assertNull(metadata.getETag());

        metadataMap.put(OOSResourceMetadata.OOS_RESOURCE_METADATA_CURRENT_ETAG, "12");
        assertEquals("\"12\"", metadata.getETag());
    }

    @Test
    public void testSetETag() {
        OOSResourceMetadata metadata = new OOSResourceMetadata();

        metadata.setETag(null);
        assertNull(metadata.getETag());

        metadata.setETag("");
        assertEquals("", metadata.getETag());

        metadata.setETag("12");
        assertEquals("12", metadata.getMetadata().get(OOSResourceMetadata.OOS_RESOURCE_METADATA_CURRENT_ETAG));
        assertEquals("\"12\"", metadata.getETag());

        metadata.setETag("\"12\"");
        assertEquals("12", metadata.getMetadata().get(OOSResourceMetadata.OOS_RESOURCE_METADATA_CURRENT_ETAG));
        assertEquals("\"12\"", metadata.getETag());

        metadata.setETag(null);
        assertNull(metadata.getETag());
        assertFalse(metadata.getMetadata().containsKey(OOSResourceMetadata.OOS_RESOURCE_METADATA_CURRENT_ETAG));
    }

    @Test
    public void testGetLastModified() {
        Map<String, String> metadataMap = new HashMap<>();
        OOSResourceMetadata metadata = new OOSResourceMetadata(metadataMap);
        assertNull(metadata.getLastModified());

        metadataMap.put(OOSResourceMetadata.OOS_RESOURCE_METADATA_LAST_MODIFIED, "2007");
        assertEquals("2007", metadata.getLastModified());
    }

    @Test
    public void testSetLastModified() {
        OOSResourceMetadata metadata = new OOSResourceMetadata();

        metadata.setLastModified(null);
        assertNull(metadata.getLastModified());

        metadata.setLastModified(new Date(0).toInstant().atZone(ZoneId.of("GMT")).toLocalDate());
        assertEquals("19700101", metadata.getLastModified());
    }
}
