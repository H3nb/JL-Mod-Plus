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

import android.os.Handler
import android.os.Looper
import javax.microedition.lcdui.Canvas

/**
 * Android boundary for the pure ownership ledger. It serializes timer callbacks on the main
 * handler but leaves guest callbacks on the existing Canvas event queue.
 */
class GuestKeyLedgerAdapter @JvmOverloads constructor(
    private val canvas: Canvas,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val scheduler = AndroidRepeatScheduler(handler)
    private val ledger = KeyOwnershipLedger(
        sink = KeyEventSink { event ->
            when (event.type) {
                KeyEventType.DOWN -> canvas.postKeyPressed(event.key.code)
                KeyEventType.UP -> canvas.postKeyReleased(event.key.code)
                KeyEventType.REPEAT -> canvas.postKeyRepeated(event.key.code)
            }
        },
        repeatScheduler = scheduler,
    )

    @Volatile
    private var generation = 1L
    private val targetId = "canvas@${System.identityHashCode(canvas).toUInt().toString(16)}"

    fun target(): OutputTarget = OutputTarget(targetId, generation)

    fun press(
        deviceId: String,
        sessionId: Long,
        kind: String,
        channel: String,
        keyCodes: IntArray,
        repeat: Boolean = true,
    ) {
        val source = SourceToken(deviceId, sessionId, kind, channel)
        val outputs = keyCodes.asSequence().map(::CanonicalOutputKey).toList()
        ledger.down(source, target(), outputs, if (repeat) RepeatSpec.Default else RepeatSpec.Disabled)
    }

    fun update(
        deviceId: String,
        sessionId: Long,
        kind: String,
        channel: String,
        keyCodes: IntArray,
        repeat: Boolean = true,
    ) {
        val source = SourceToken(deviceId, sessionId, kind, channel)
        val outputs = keyCodes.asSequence().map(::CanonicalOutputKey).toList()
        ledger.update(source, target(), outputs, if (repeat) RepeatSpec.Default else RepeatSpec.Disabled)
    }

    fun release(deviceId: String, sessionId: Long, kind: String, channel: String) {
        ledger.up(SourceToken(deviceId, sessionId, kind, channel))
    }

    /** Ends the current visibility generation and releases all outputs before the next one. */
    fun endVisibility() {
        ledger.clear()
        generation = safeIncrement(generation)
    }

    fun resetForShow() {
        ledger.clear()
        generation = safeIncrement(generation)
    }

    fun clear() = endVisibility()

    fun currentGeneration(): Long = generation

    private fun safeIncrement(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L
}
