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

/**
 * Repeat cadence used by the legacy on-screen keypad.
 *
 * Keep this separate from controller/analog repeat policy. The virtual keypad historically
 * emitted its first repeat after 400 ms, then used 200, 400, five 128 ms intervals, and finally
 * settled at 80 ms. Existing MIDlets can be sensitive to this cadence, so the ownership ledger
 * must not silently replace it with the controller's fixed repeat interval.
 */
object LegacyVirtualKeypadRepeat {
    const val INITIAL_DELAY_MILLIS: Long = 400L
    const val STEADY_DELAY_MILLIS: Long = 80L

    private val FOLLOW_UP_DELAYS = longArrayOf(
        200L,
        400L,
        128L,
        128L,
        128L,
        128L,
        128L,
    )

    /** Delay from the current repeat edge to the following repeat edge. */
    fun delayAfterRepeat(repeatIndex: Int): Long =
        FOLLOW_UP_DELAYS.getOrElse(repeatIndex) { STEADY_DELAY_MILLIS }
}
