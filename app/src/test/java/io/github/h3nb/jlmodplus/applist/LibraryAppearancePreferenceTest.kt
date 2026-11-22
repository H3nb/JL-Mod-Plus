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

class LibraryAppearancePreferenceTest {
    @Test
    fun layoutValuesAndFallbackMatchThePersistedContract() {
        for (layout in LibraryLayout.entries) {
            assertEquals(layout, LibraryLayout.fromStoredValue(layout.storedValue))
        }
        assertEquals(LibraryLayout.List, LibraryLayout.fromStoredValue(Int.MIN_VALUE))
    }

    @Test
    fun iconOptionsRoundTripAcrossStoredAndSettingsValues() {
        for (ratio in LibraryIconRatio.entries) {
            assertEquals(ratio, LibraryIconRatio.fromStoredValue(ratio.storedValue))
            assertEquals(ratio, LibraryIconRatio.fromPreferenceKey(ratio.preferenceKey))
        }
        for (shape in LibraryIconShape.entries) {
            assertEquals(shape, LibraryIconShape.fromStoredValue(shape.storedValue))
            assertEquals(shape, LibraryIconShape.fromPreferenceKey(shape.preferenceKey))
        }
        for (size in LibraryGridIconSize.entries) {
            assertEquals(size, LibraryGridIconSize.fromStoredValue(size.storedValue))
            assertEquals(size, LibraryGridIconSize.fromPreferenceKey(size.preferenceKey))
        }
    }

    @Test
    fun unknownIconOptionsFallBackToHistoricalDefaults() {
        assertEquals(LibraryIconRatio.Square, LibraryIconRatio.fromStoredValue(Int.MIN_VALUE))
        assertEquals(LibraryIconRatio.Square, LibraryIconRatio.fromPreferenceKey("invalid"))
        assertEquals(LibraryIconShape.Round, LibraryIconShape.fromStoredValue(Int.MIN_VALUE))
        assertEquals(LibraryIconShape.Round, LibraryIconShape.fromPreferenceKey("invalid"))
        assertEquals(LibraryGridIconSize.Standard, LibraryGridIconSize.fromStoredValue(Int.MIN_VALUE))
        assertEquals(LibraryGridIconSize.Standard, LibraryGridIconSize.fromPreferenceKey("invalid"))
    }

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
