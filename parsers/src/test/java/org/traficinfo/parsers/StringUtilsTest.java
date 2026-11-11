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

package org.traficinfo.parsers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StringUtilsTest {

  @Test
  public void capitalizesWordsSeparatedBySpacesAndHyphens() {
    assertEquals("Saint Clement-De-Riviere",
        StringUtils.toCamelCase("SAINT CLEMENT-DE-RIVIERE"));
    assertEquals("Saint-Clément De Rivière",
        StringUtils.toCamelCase("sAiNt-cLÉmEnT dE rIvIèRe"));
    assertEquals("Béziers", StringUtils.toCamelCase("BÉZIERS"));
  }

  @Test
  public void preservesSeparators() {
    assertEquals("  Saint--Clément  De\tRivière ",
        StringUtils.toCamelCase("  SAINT--CLÉMENT  DE\tRIVIÈRE "));
  }

  @Test
  public void handlesMissingOrEmptyInput() {
    assertNull(StringUtils.toCamelCase(null));
    assertEquals("", StringUtils.toCamelCase(""));
    assertEquals(" - ", StringUtils.toCamelCase(" - "));
  }
}
