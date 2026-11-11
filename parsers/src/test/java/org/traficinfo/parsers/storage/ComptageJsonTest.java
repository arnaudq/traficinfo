/*
 * This file is part of TraficInfo.
 * Copyright (C) 2026 Arnaud Quillaud
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

package org.traficinfo.parsers.storage;

import org.junit.Test;
import org.traficinfo.parsers.Comptage;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.parsers.m3m.M3MComptage;
import org.geojson.Point;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class ComptageJsonTest {
    @Test
    public void roundTripsStoredFieldsForEveryDatasourceWithoutLosingNullMeasures() throws Exception {
        ComptageJson json = new ComptageJson();
        for (GeoJsonParser.DataSource source : GeoJsonParser.DataSource.values()) {
            String fixture = switch (source) {
                case M3MV2 -> "M3MSample.csv";
                case DPT76 -> "DPT76_Comptage_2022.geojson";
                default -> source.getId() + "Sample.csv";
            };
            try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/" + fixture), StandardCharsets.UTF_8)) {
                List<? extends Comptage> counts = source.getParser().parseComptages(reader);
                assertFalse(source.getId(), counts.isEmpty());
                for (Comptage count : counts) {
                    if (count instanceof M3MComptage mmm) mmm.setGeometry(new Point(3.8, 43.6));
                    byte[] original = json.write(count);
                    byte[] array = ("[" + new String(original, StandardCharsets.UTF_8) + "]").getBytes(StandardCharsets.UTF_8);
                    Comptage restored = json.read(new ByteArrayInputStream(array), source.getDsComptageClass()).get(0);
                    assertArrayEquals(source.getId(), original, json.write(restored));
                    assertEquals(count.getMoyenneJourAnnuelle(), restored.getMoyenneJourAnnuelle());
                    assertEquals(count.getDirectionType(), restored.getDirectionType());
                    assertEquals(count.getGeometry(), restored.getGeometry());
                    assertEquals(count.getDisplayName(), restored.getDisplayName());
                }
            }
        }
    }
}
