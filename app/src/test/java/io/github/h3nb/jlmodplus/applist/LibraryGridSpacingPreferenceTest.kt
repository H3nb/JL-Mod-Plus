/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.applist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryGridSpacingPreferenceTest {
    @Test
    fun eachSpacingRoundTripsThroughStoredAndSettingsValues() {
        for (spacing in LibraryGridSpacing.entries) {
            assertEquals(spacing, LibraryGridSpacing.fromStoredValue(spacing.storedValue))
            assertEquals(spacing, LibraryGridSpacing.fromPreferenceKey(spacing.preferenceKey))
        }
    }

    @Test
    fun unknownValuesFallBackToStandardSpacing() {
        assertEquals(LibraryGridSpacing.Standard, LibraryGridSpacing.fromStoredValue(Int.MIN_VALUE))
        assertEquals(LibraryGridSpacing.Standard, LibraryGridSpacing.fromPreferenceKey("invalid"))
    }
}
