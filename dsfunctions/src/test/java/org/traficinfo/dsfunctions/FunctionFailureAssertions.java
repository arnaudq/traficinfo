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

package org.traficinfo.dsfunctions;

import static org.junit.Assert.fail;

/** Check that failed handlers reach the runtime as errors rather than successful KO responses. */
public final class FunctionFailureAssertions {
    private FunctionFailureAssertions() { }

    @FunctionalInterface
    public interface Invocation {
        void run() throws Exception;
    }

    public static void assertFunctionFailure(Invocation invocation) {
        try {
            invocation.run();
            fail("Expected the failed invocation to throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // The Functions runtime records an error response for the alarm.
        } catch (Exception unexpected) {
            throw new AssertionError("Expected IllegalStateException", unexpected);
        }
    }
}
