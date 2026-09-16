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

package io.github.h3nb.jlmodplus.input

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyVirtualKeypadRepeatTest {
    @Test
    fun cadenceMatchesLegacyVirtualKeyboard() {
        assertEquals(400L, LegacyVirtualKeypadRepeat.INITIAL_DELAY_MILLIS)
        assertEquals(200L, LegacyVirtualKeypadRepeat.delayAfterRepeat(0))
        assertEquals(400L, LegacyVirtualKeypadRepeat.delayAfterRepeat(1))
        for (index in 2..6) {
            assertEquals(128L, LegacyVirtualKeypadRepeat.delayAfterRepeat(index))
        }
        assertEquals(80L, LegacyVirtualKeypadRepeat.delayAfterRepeat(7))
        assertEquals(80L, LegacyVirtualKeypadRepeat.delayAfterRepeat(100))
    }
}
