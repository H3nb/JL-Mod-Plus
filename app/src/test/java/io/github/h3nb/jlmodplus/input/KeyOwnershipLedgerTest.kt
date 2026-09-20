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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyOwnershipLedgerTest {
    private val target = OutputTarget("guest", 7L)
    private val newerTarget = OutputTarget("guest", 8L)
    private val left = CanonicalOutputKey(10)
    private val right = CanonicalOutputKey(20)
    private val up = CanonicalOutputKey(30)

    @Test
    fun duplicateDownIsIdempotentAndExposesRequestedAndDeliveredState() {
        val sink = RecordingSink()
        val ledger = KeyOwnershipLedger(sink)
        val source = source("pad", 1L, "left-stick")

        ledger.down(source, target, listOf(right, left, right))
        ledger.down(source, target, listOf(left, right))

        assertEquals(
            listOf(event(KeyEventType.DOWN, target, left), event(KeyEventType.DOWN, target, right)),
            sink.events,
        )
        assertEquals(setOf(source), ledger.requestedOwners(target, left))
        assertTrue(ledger.isRequested(target, right))
        assertEquals(
            setOf(TargetedOutputKey(target, left), TargetedOutputKey(target, right)),
            ledger.deliveredState(),
        )
    }

    @Test
    fun multipleOwnersReleaseOnlyOnLastOwner() {
        val sink = RecordingSink()
        val ledger = KeyOwnershipLedger(sink)
        val first = source("pad", 1L, "dpad")
        val second = source("pad", 2L, "stick")

        ledger.down(first, target, listOf(left, up))
        ledger.down(second, target, listOf(left))
        ledger.up(first)
        ledger.up(second)

        assertEquals(
            listOf(
                event(KeyEventType.DOWN, target, left),
                event(KeyEventType.DOWN, target, up),
                event(KeyEventType.UP, target, up),
                event(KeyEventType.UP, target, left),
            ),
            sink.events,
        )
        assertTrue(ledger.requestedState().isEmpty())
        assertTrue(ledger.deliveredState().isEmpty())
    }

    @Test
    fun updateUsesDeterministicSetDiffForDiagonalOutputs() {
        val sink = RecordingSink()
        val ledger = KeyOwnershipLedger(sink)
        val source = source("pad", 1L, "dpad")

        ledger.down(source, target, listOf(right, left))
        ledger.update(source, target, listOf(up, right))

        assertEquals(
            listOf(
                event(KeyEventType.DOWN, target, left),
                event(KeyEventType.DOWN, target, right),
                event(KeyEventType.UP, target, left),
                event(KeyEventType.DOWN, target, up),
            ),
            sink.events,
        )
        assertEquals(listOf(right, up), ledger.requestedState()[target]?.keys?.toList())
    }

    @Test
    fun upUsesCapturedBindingAndDoesNotTouchNewTargetGeneration() {
        val sink = RecordingSink()
        val ledger = KeyOwnershipLedger(sink)
        val source = source("pad", 1L, "button-a")

        ledger.down(source, target, listOf(left))
        ledger.update(source, newerTarget, listOf(right))
        ledger.up(source)
        ledger.up(source)

        assertEquals(
            listOf(
                event(KeyEventType.DOWN, target, left),
                event(KeyEventType.UP, target, left),
                event(KeyEventType.DOWN, newerTarget, right),
                event(KeyEventType.UP, newerTarget, right),
            ),
            sink.events,
        )
        assertFalse(ledger.isDelivered(target, right))
        assertTrue(ledger.deliveredState().isEmpty())
    }

    @Test
    fun releasingOldGenerationCannotReleaseNewGeneration() {
        val sink = RecordingSink()
        val ledger = KeyOwnershipLedger(sink)
        val oldSource = source("pad", 1L, "contact-old")
        val newSource = source("pad", 2L, "contact-new")

        ledger.down(oldSource, target, listOf(left))
        ledger.releaseTarget(target)
        ledger.down(newSource, newerTarget, listOf(left))
        ledger.up(oldSource)

        assertEquals(
            listOf(
                event(KeyEventType.DOWN, target, left),
                event(KeyEventType.UP, target, left),
                event(KeyEventType.DOWN, newerTarget, left),
            ),
            sink.events,
        )
        assertEquals(setOf(TargetedOutputKey(newerTarget, left)), ledger.deliveredState())
    }

    @Test
    fun requestedAndDeliveredStatesRemainDistinctWhenSinkFailsAndFlushRetries() {
        val sink = RecordingSink(failuresRemaining = 1)
        val ledger = KeyOwnershipLedger(sink)
        val source = source("pad", 1L, "button-a")

        try {
            ledger.down(source, target, listOf(left))
        } catch (_: RecordingSink.SinkFailure) {
            // The logical request must survive a failed delivery.
        }

        assertEquals(setOf(source), ledger.requestedOwners(target, left))
        assertFalse(ledger.isDelivered(target, left))
        ledger.flush()
        assertTrue(ledger.isDelivered(target, left))
        assertEquals(
            listOf(event(KeyEventType.DOWN, target, left), event(KeyEventType.DOWN, target, left)),
            sink.events,
        )
    }

    @Test
    fun manualClockRepeatsAfterDelayAtIntervalAndStopsOnUp() {
        val sink = RecordingSink()
        val clock = ManualRepeatScheduler()
        val ledger = KeyOwnershipLedger(sink, clock)
        val source = source("pad", 1L, "button-a")

        ledger.down(source, target, listOf(left), RepeatSpec.Default)
        clock.advanceBy(399L)
        assertEquals(1, sink.events.size)
        clock.advanceBy(1L)
        clock.advanceBy(80L)
        ledger.up(source)
        clock.advanceBy(1_000L)

        assertEquals(
            listOf(
                event(KeyEventType.DOWN, target, left),
                event(KeyEventType.REPEAT, target, left),
                event(KeyEventType.REPEAT, target, left),
                event(KeyEventType.UP, target, left),
            ),
            sink.events,
        )
        assertEquals(0, clock.pendingTaskCount())
    }

    @Test
    fun repeatHasOneDeterministicProducerAndTransfersWithoutBurst() {
        val sink = RecordingSink()
        val clock = ManualRepeatScheduler()
        val ledger = KeyOwnershipLedger(sink, clock)
        val first = source("pad", 1L, "a")
        val second = source("pad", 2L, "b")
        val spec = RepeatSpec(initialDelayMillis = 100L, intervalMillis = 50L, enabled = true)

        ledger.down(second, target, listOf(left), spec)
        ledger.down(first, target, listOf(left), spec)
        clock.advanceBy(100L)
        ledger.up(first)
        clock.advanceBy(49L)
        assertEquals(1, sink.events.count { it.type == KeyEventType.REPEAT })
        clock.advanceBy(1L)
        ledger.up(second)

        assertEquals(
            listOf(
                event(KeyEventType.DOWN, target, left),
                event(KeyEventType.REPEAT, target, left),
                event(KeyEventType.REPEAT, target, left),
                event(KeyEventType.UP, target, left),
            ),
            sink.events,
        )
    }

    @Test
    fun releaseDeviceOnlyReleasesSourcesOwnedByThatDevice() {
        val sink = RecordingSink()
        val ledger = KeyOwnershipLedger(sink)
        val firstDevice = source("pad-a", 1L, "button-a")
        val sameDevice = source("pad-a", 2L, "button-b")
        val otherDevice = source("pad-b", 1L, "button-a")

        ledger.down(firstDevice, target, listOf(left))
        ledger.down(sameDevice, target, listOf(right))
        ledger.down(otherDevice, target, listOf(up))
        ledger.releaseDevice("pad-a")

        assertEquals(
            listOf(
                event(KeyEventType.DOWN, target, left),
                event(KeyEventType.DOWN, target, right),
                event(KeyEventType.DOWN, target, up),
                event(KeyEventType.UP, target, left),
                event(KeyEventType.UP, target, right),
            ),
            sink.events,
        )
        assertFalse(ledger.isActive(firstDevice))
        assertFalse(ledger.isActive(sameDevice))
        assertTrue(ledger.isActive(otherDevice))
        assertEquals(setOf(TargetedOutputKey(target, up)), ledger.deliveredState())
    }

    @Test
    fun clearReleasesAllOutputsInDeterministicOrder() {
        val sink = RecordingSink()
        val ledger = KeyOwnershipLedger(sink)
        val first = source("pad", 1L, "first")
        val second = source("pad", 2L, "second")

        ledger.down(first, target, listOf(right, left))
        ledger.down(second, newerTarget, listOf(up, left))
        ledger.clear()

        assertEquals(
            listOf(
                event(KeyEventType.DOWN, target, left),
                event(KeyEventType.DOWN, target, right),
                event(KeyEventType.DOWN, newerTarget, left),
                event(KeyEventType.DOWN, newerTarget, up),
                event(KeyEventType.UP, target, left),
                event(KeyEventType.UP, target, right),
                event(KeyEventType.UP, newerTarget, left),
                event(KeyEventType.UP, newerTarget, up),
            ),
            sink.events,
        )
    }

    private fun source(device: String, session: Long, channel: String): SourceToken =
        SourceToken(deviceId = device, sessionId = session, kind = "controller", channel = channel)

    private fun event(type: KeyEventType, target: OutputTarget, key: CanonicalOutputKey): KeyEvent =
        KeyEvent(type = type, target = target, key = key)

    private class RecordingSink(
        private var failuresRemaining: Int = 0,
    ) : KeyEventSink {
        val events = mutableListOf<KeyEvent>()

        override fun record(event: KeyEvent) {
            events += event
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw SinkFailure()
            }
        }

        class SinkFailure : RuntimeException()
    }
}
