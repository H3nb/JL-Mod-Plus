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

package io.github.h3nb.jlmodplus.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigDisplayLayoutTest {
    @Test
    fun normalFontPreservesCompactColumnThresholds() {
        assertEquals(1, screenPresetColumnCount(249f, 1f))
        assertEquals(2, screenPresetColumnCount(250f, 1f))
        assertEquals(3, screenPresetColumnCount(480f, 1f))
    }

    @Test
    fun largeFontReducesColumnsWhenTilesWouldBeCramped() {
        assertEquals(1, screenPresetColumnCount(320f, 2f))
        assertEquals(2, screenPresetColumnCount(480f, 2f))
        assertEquals(3, screenPresetColumnCount(720f, 2f))
    }
}
