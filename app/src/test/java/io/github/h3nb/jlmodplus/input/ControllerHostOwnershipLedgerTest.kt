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
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerHostOwnershipLedgerTest {
    private val target = ControllerHostTarget("screen", 2L)
    private val newerTarget = ControllerHostTarget("screen", 3L)
    private val fire = ControllerHostOutput.GuestKey(53)
    private val activate = ControllerHostOutput.Action(HostAction.ACTIVATE)

    @Test
    fun manyToOneReleasesOnlyAfterLastHostOwner() {
        val sink = RecordingSink()
        val ledger = ControllerHostOwnershipLedger(sink)
        val first = source("a", "first")
        val second = source("b", "second")

        ledger.down(first, target, listOf(fire))
        ledger.down(second, target, listOf(fire))
        ledger.up(first)
        ledger.up(second)

        assertEquals(
            listOf(
                event(ControllerHostEventType.DOWN, target, fire),
                event(ControllerHostEventType.UP, target, fire),
            ),
            sink.events,
        )
    }

    @Test
    fun updateUsesSetDiffAndTargetGeneration() {
        val sink = RecordingSink()
        val ledger = ControllerHostOwnershipLedger(sink)
        val source = source("pad", "direction")

        ledger.down(source, target, listOf(fire, activate))
        ledger.update(source, newerTarget, listOf(fire))
        ledger.up(source)

        assertEquals(
            listOf(
                event(ControllerHostEventType.DOWN, target, fire),
                event(ControllerHostEventType.DOWN, target, activate),
                event(ControllerHostEventType.UP, target, fire),
                event(ControllerHostEventType.UP, target, activate),
                event(ControllerHostEventType.DOWN, newerTarget, fire),
                event(ControllerHostEventType.UP, newerTarget, fire),
            ),
            sink.events,
        )
        assertTrue(ledger.deliveredState().isEmpty())
    }

    @Test
    fun repeatUsesOneOwnerAndStopsOnRelease() {
        val sink = RecordingSink()
        val clock = ManualRepeatScheduler()
        val ledger = ControllerHostOwnershipLedger(sink, clock)
        val source = source("pad", "dpad")

        ledger.down(source, target, listOf(fire), RepeatSpec(100L, 50L, true))
        clock.advanceBy(99L)
        clock.advanceBy(1L)
        ledger.up(source)
        clock.advanceBy(500L)

        assertEquals(
            listOf(
                event(ControllerHostEventType.DOWN, target, fire),
                event(ControllerHostEventType.REPEAT, target, fire),
                event(ControllerHostEventType.UP, target, fire),
            ),
            sink.events,
        )
        assertEquals(0, clock.pendingTaskCount())
    }

    private fun source(device: String, channel: String) =
        SourceToken(device, 1L, "controller", channel)

    private fun event(
        type: ControllerHostEventType,
        target: ControllerHostTarget,
        output: ControllerHostOutput,
    ) = ControllerHostEvent(type, target, output)

    private class RecordingSink : ControllerHostOutputSink {
        val events = mutableListOf<ControllerHostEvent>()

        override fun record(event: ControllerHostEvent) {
            events += event
        }
    }
}
