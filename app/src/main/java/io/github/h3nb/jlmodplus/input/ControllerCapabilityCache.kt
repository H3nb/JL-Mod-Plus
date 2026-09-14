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

import android.view.InputDevice
import java.util.Locale

/**
 * Caches the stable calibration identity for one Android device/source pair.
 *
 * Motion events are high frequency. Building, sorting, and formatting the capability signature
 * therefore happens on first use and after an InputDevice callback, never for every sample.
 */
class ControllerCapabilityCache {
    private val signatures = HashMap<Key, String>()

    fun signature(device: InputDevice, source: Int): String {
        val key = Key(device.id, source)
        return signatures[key] ?: buildSignature(device, source).also { signatures[key] = it }
    }

    fun invalidate(deviceId: Int) {
        signatures.keys.removeAll { it.deviceId == deviceId }
    }

    fun clear() = signatures.clear()

    private data class Key(val deviceId: Int, val source: Int)

    companion object {
        @JvmStatic
        fun buildSignature(device: InputDevice, source: Int): String = buildString {
            append("descriptor=")
            append(device.descriptor.orEmpty().ifBlank { "unknown" }.replace("|", "%7C"))
            append(";source=")
            append(source)
            device.motionRanges
                .sortedWith(
                    compareBy<InputDevice.MotionRange>(
                        { it.axis },
                        { it.source },
                        { it.min },
                        { it.max },
                        { it.flat },
                        { it.fuzz },
                        { it.resolution },
                    ),
                )
                .forEach { range ->
                    append(";axis=")
                    append(range.axis)
                    append(',')
                    append(range.source)
                    append(',')
                    append(number(range.min))
                    append(',')
                    append(number(range.max))
                    append(',')
                    append(number(range.flat))
                    append(',')
                    append(number(range.fuzz))
                    append(',')
                    append(number(range.resolution))
                }
        }

        private fun number(value: Float): String =
            if (value.isFinite()) String.format(Locale.US, "%.6f", value) else "nan"
    }
}
