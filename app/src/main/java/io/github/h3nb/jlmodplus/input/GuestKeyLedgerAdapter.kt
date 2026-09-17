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
 *
 * Repeat timing is intentionally producer-specific. Controller/analog producers may use the
 * ledger's regular [RepeatSpec], while the long-established on-screen keypad cadence is replayed
 * separately so this input refactor does not change existing MIDlet behavior.
 */
class GuestKeyLedgerAdapter @JvmOverloads constructor(
    private val canvas: Canvas,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private data class LegacyRepeatRegistration(
        var keyCodes: IntArray,
        var repeatIndex: Int = 0,
        var runnable: Runnable? = null,
    )

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
    private val legacyKeypadRepeats = LinkedHashMap<SourceToken, LegacyRepeatRegistration>()

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
        if (repeat && kind == LEGACY_VIRTUAL_KEYPAD_KIND) {
            ledger.down(source, target(), outputs, RepeatSpec.Disabled)
            startLegacyKeypadRepeat(source, keyCodes)
        } else {
            ledger.down(source, target(), outputs, if (repeat) RepeatSpec.Default else RepeatSpec.Disabled)
        }
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
        if (repeat && kind == LEGACY_VIRTUAL_KEYPAD_KIND) {
            ledger.update(source, target(), outputs, RepeatSpec.Disabled)
            if (outputs.isEmpty()) {
                stopLegacyKeypadRepeat(source)
            } else {
                val existing = legacyKeypadRepeats[source]
                if (existing == null) startLegacyKeypadRepeat(source, keyCodes)
                else existing.keyCodes = keyCodes.copyOf()
            }
        } else {
            stopLegacyKeypadRepeat(source)
            ledger.update(source, target(), outputs, if (repeat) RepeatSpec.Default else RepeatSpec.Disabled)
        }
    }

    fun release(deviceId: String, sessionId: Long, kind: String, channel: String) {
        val source = SourceToken(deviceId, sessionId, kind, channel)
        stopLegacyKeypadRepeat(source)
        ledger.up(source)
    }

    /** True only while this exact producer currently owns a delivered/requested output set. */
    fun isActive(deviceId: String, sessionId: Long, kind: String, channel: String): Boolean =
        ledger.isActive(SourceToken(deviceId, sessionId, kind, channel))

    /** Ends the current visibility generation and releases all outputs before the next one. */
    fun endVisibility() {
        cancelLegacyKeypadRepeats()
        ledger.clear()
        generation = safeIncrement(generation)
    }

    fun resetForShow() {
        cancelLegacyKeypadRepeats()
        ledger.clear()
        generation = safeIncrement(generation)
    }

    fun clear() = endVisibility()

    fun currentGeneration(): Long = generation

    private fun startLegacyKeypadRepeat(source: SourceToken, keyCodes: IntArray) {
        if (keyCodes.isEmpty() || legacyKeypadRepeats.containsKey(source)) return
        val registration = LegacyRepeatRegistration(keyCodes.copyOf())
        val task = object : Runnable {
            override fun run() {
                val current = legacyKeypadRepeats[source] ?: return
                if (!ledger.isActive(source)) {
                    legacyKeypadRepeats.remove(source)
                    return
                }
                current.keyCodes.forEach(canvas::postKeyRepeated)
                val delay = LegacyVirtualKeypadRepeat.delayAfterRepeat(current.repeatIndex)
                current.repeatIndex += 1
                handler.postDelayed(this, delay)
            }
        }
        registration.runnable = task
        legacyKeypadRepeats[source] = registration
        handler.postDelayed(task, LegacyVirtualKeypadRepeat.INITIAL_DELAY_MILLIS)
    }

    private fun stopLegacyKeypadRepeat(source: SourceToken) {
        val registration = legacyKeypadRepeats.remove(source) ?: return
        registration.runnable?.let(handler::removeCallbacks)
    }

    private fun cancelLegacyKeypadRepeats() {
        val callbacks = legacyKeypadRepeats.values.mapNotNull { it.runnable }
        legacyKeypadRepeats.clear()
        callbacks.forEach(handler::removeCallbacks)
    }

    private fun safeIncrement(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L

    private companion object {
        const val LEGACY_VIRTUAL_KEYPAD_KIND = "virtual-keypad"
    }
}
