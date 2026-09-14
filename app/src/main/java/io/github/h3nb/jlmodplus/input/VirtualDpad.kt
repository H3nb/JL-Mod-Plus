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

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.PI

enum class VirtualDpadDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

enum class VirtualDpadDirectionMode {
    FOUR_WAY,
    EIGHT_WAY,
}

data class VirtualDpadSettings(
    val directionMode: VirtualDpadDirectionMode = VirtualDpadDirectionMode.EIGHT_WAY,
    val deadzoneFraction: Float = 0.18f,
) {
    init {
        require(deadzoneFraction.isFinite() && deadzoneFraction in 0.0f..1.0f) {
            "deadzoneFraction must be in [0, 1]"
        }
    }
}

data class VirtualDpadGeometry(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
) {
    init {
        require(centerX.isFinite() && centerY.isFinite()) { "center must be finite" }
        require(radius.isFinite() && radius > 0.0f) { "radius must be positive" }
    }
}

/**
 * Shared, Android-free virtual D-pad quantizer. Each contact has its own source token and output
 * state, so sliding between sectors updates a guest producer without stealing another contact.
 */
class VirtualDpadController @JvmOverloads constructor(
    private val settings: VirtualDpadSettings = VirtualDpadSettings(),
) {
    private val contacts = LinkedHashMap<PointerSourceToken, Set<VirtualDpadDirection>>()

    fun begin(
        token: PointerSourceToken,
        geometry: VirtualDpadGeometry,
        x: Float,
        y: Float,
    ): Set<VirtualDpadDirection> {
        if (contacts.containsKey(token)) return contacts.getValue(token)
        val directions = quantize(geometry, x, y)
        contacts[token] = directions
        return directions
    }

    fun move(
        token: PointerSourceToken,
        geometry: VirtualDpadGeometry,
        x: Float,
        y: Float,
    ): Set<VirtualDpadDirection> {
        if (!contacts.containsKey(token)) return emptySet()
        val directions = quantize(geometry, x, y)
        contacts[token] = directions
        return directions
    }

    fun end(token: PointerSourceToken): Set<VirtualDpadDirection> =
        contacts.remove(token) ?: emptySet()

    fun cancelAll(): Map<PointerSourceToken, Set<VirtualDpadDirection>> {
        val previous = LinkedHashMap(contacts)
        contacts.clear()
        return previous
    }

    fun state(token: PointerSourceToken): Set<VirtualDpadDirection> =
        contacts[token] ?: emptySet()

    fun hasContact(token: PointerSourceToken): Boolean = contacts.containsKey(token)

    private fun quantize(
        geometry: VirtualDpadGeometry,
        x: Float,
        y: Float,
    ): Set<VirtualDpadDirection> {
        val safeX = if (x.isFinite()) x else geometry.centerX
        val safeY = if (y.isFinite()) y else geometry.centerY
        val dx = safeX - geometry.centerX
        val dy = safeY - geometry.centerY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance <= geometry.radius * settings.deadzoneFraction) return emptySet()

        val directions = LinkedHashSet<VirtualDpadDirection>(2)
        if (settings.directionMode == VirtualDpadDirectionMode.FOUR_WAY) {
            if (abs(dx) >= abs(dy)) {
                directions += if (dx < 0.0f) VirtualDpadDirection.LEFT
                else VirtualDpadDirection.RIGHT
            } else {
                directions += if (dy < 0.0f) VirtualDpadDirection.UP
                else VirtualDpadDirection.DOWN
            }
            return directions
        }

        val sector = floor((atan2(dy.toDouble(), dx.toDouble()) + PI / 8.0) /
            (PI / 4.0)).toInt().mod(8)
        when (sector) {
            0 -> directions += VirtualDpadDirection.RIGHT
            1 -> directions += listOf(VirtualDpadDirection.DOWN, VirtualDpadDirection.RIGHT)
            2 -> directions += VirtualDpadDirection.DOWN
            3 -> directions += listOf(VirtualDpadDirection.DOWN, VirtualDpadDirection.LEFT)
            4 -> directions += VirtualDpadDirection.LEFT
            5 -> directions += listOf(VirtualDpadDirection.UP, VirtualDpadDirection.LEFT)
            6 -> directions += VirtualDpadDirection.UP
            else -> directions += listOf(VirtualDpadDirection.UP, VirtualDpadDirection.RIGHT)
        }
        return directions
    }
}
