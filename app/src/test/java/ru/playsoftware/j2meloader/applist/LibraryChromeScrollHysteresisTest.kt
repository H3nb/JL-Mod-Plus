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

package ru.playsoftware.j2meloader.applist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryChromeScrollHysteresisTest {
    @Test
    fun smallForwardMotionDoesNotHideChrome() {
        val state = LibraryChromeScrollHysteresis(
            hideDistancePx = 10f,
            revealDistancePx = 18f,
        )

        assertNull(state.onScrollDelta(-4f))
        assertNull(state.onScrollDelta(-5f))
        assertTrue(state.chromeVisible)
    }

    @Test
    fun forwardMotionAccumulatesUntilHideThreshold() {
        val state = LibraryChromeScrollHysteresis(
            hideDistancePx = 10f,
            revealDistancePx = 18f,
        )

        assertNull(state.onScrollDelta(-6f))
        assertFalse(state.onScrollDelta(-4f)!!)
        assertFalse(state.chromeVisible)
    }

    @Test
    fun slowReverseMotionAccumulatesBeforeReveal() {
        val state = LibraryChromeScrollHysteresis(
            hideDistancePx = 10f,
            revealDistancePx = 18f,
        )
        state.onScrollDelta(-10f)

        assertNull(state.onScrollDelta(5f))
        assertNull(state.onScrollDelta(6f))
        assertFalse(state.chromeVisible)
        assertTrue(state.onScrollDelta(7f)!!)
        assertTrue(state.chromeVisible)
    }

    @Test
    fun directionChangeResetsPendingRevealDistance() {
        val state = LibraryChromeScrollHysteresis(
            hideDistancePx = 10f,
            revealDistancePx = 18f,
        )
        state.onScrollDelta(-10f)
        state.onScrollDelta(12f)
        state.onScrollDelta(-1f)

        assertNull(state.onScrollDelta(7f))
        assertFalse(state.chromeVisible)
        assertTrue(state.onScrollDelta(11f)!!)
    }

    @Test
    fun resetAndRevealNowRestoreChromeWithoutDuplicateTransitions() {
        val state = LibraryChromeScrollHysteresis(
            hideDistancePx = 10f,
            revealDistancePx = 18f,
        )
        state.onScrollDelta(-10f)

        assertTrue(state.revealNow()!!)
        assertNull(state.revealNow())
        state.onScrollDelta(-10f)
        assertTrue(state.reset()!!)
        assertNull(state.reset())
    }
}
