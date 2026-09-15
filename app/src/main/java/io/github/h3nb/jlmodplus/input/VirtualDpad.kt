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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

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
    /** Radius required to acquire a direction from neutral. */
    val directionMode: VirtualDpadDirectionMode = VirtualDpadDirectionMode.EIGHT_WAY,
    val deadzoneFraction: Float = 0.18f,
    /** Smaller radius used to release an already-held direction. */
    val releaseDeadzoneFraction: Float = 0.12f,
    /** Extra angle retained around the currently-held sector to prevent edge chatter. */
    val angularHysteresisDegrees: Float = 7.5f,
) {
    init {
        require(deadzoneFraction.isFinite() && deadzoneFraction in 0.0f..1.0f) {
            "deadzoneFraction must be in [0, 1]"
        }
        require(
            releaseDeadzoneFraction.isFinite() &&
                releaseDeadzoneFraction in 0.0f..deadzoneFraction,
        ) { "releaseDeadzoneFraction must be in [0, deadzoneFraction]" }
        require(angularHysteresisDegrees.isFinite() && angularHysteresisDegrees in 0.0f..45.0f) {
            "angularHysteresisDegrees must be in [0, 45]"
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
 *
 * Press/release radial hysteresis avoids accidental neutral flicker near the center. Angular
 * hysteresis retains the current sector slightly beyond its mathematical boundary, which makes a
 * touch D-pad feel stable when a thumb rests close to a diagonal/cardinal edge.
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
        val directions = quantize(geometry, x, y, emptySet())
        contacts[token] = directions
        return directions
    }

    fun move(
        token: PointerSourceToken,
        geometry: VirtualDpadGeometry,
        x: Float,
        y: Float,
    ): Set<VirtualDpadDirection> {
        val previous = contacts[token] ?: return emptySet()
        val directions = quantize(geometry, x, y, previous)
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
        previous: Set<VirtualDpadDirection>,
    ): Set<VirtualDpadDirection> {
        val safeX = if (x.isFinite()) x else geometry.centerX
        val safeY = if (y.isFinite()) y else geometry.centerY
        val dx = safeX - geometry.centerX
        val dy = safeY - geometry.centerY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val pressRadius = geometry.radius * settings.deadzoneFraction
        val releaseRadius = geometry.radius * settings.releaseDeadzoneFraction

        if (previous.isEmpty()) {
            if (distance <= pressRadius) return emptySet()
        } else {
            if (distance <= releaseRadius) return emptySet()
            if (distance < pressRadius) return previous
        }

        val angleDegrees = normalizeDegrees(
            Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat(),
        )
        previousCenterDegrees(previous)?.let { previousCenter ->
            val halfSector = if (settings.directionMode == VirtualDpadDirectionMode.FOUR_WAY) {
                45.0f
            } else {
                22.5f
            }
            if (angularDistanceDegrees(angleDegrees, previousCenter) <=
                halfSector + settings.angularHysteresisDegrees
            ) {
                return previous
            }
        }

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

    private fun previousCenterDegrees(directions: Set<VirtualDpadDirection>): Float? {
        if (directions.isEmpty()) return null
        return if (settings.directionMode == VirtualDpadDirectionMode.FOUR_WAY) {
            when {
                directions.size != 1 -> null
                VirtualDpadDirection.RIGHT in directions -> 0.0f
                VirtualDpadDirection.DOWN in directions -> 90.0f
                VirtualDpadDirection.LEFT in directions -> 180.0f
                VirtualDpadDirection.UP in directions -> -90.0f
                else -> null
            }
        } else {
            when {
                directions == setOf(VirtualDpadDirection.RIGHT) -> 0.0f
                directions == setOf(VirtualDpadDirection.DOWN, VirtualDpadDirection.RIGHT) -> 45.0f
                directions == setOf(VirtualDpadDirection.DOWN) -> 90.0f
                directions == setOf(VirtualDpadDirection.DOWN, VirtualDpadDirection.LEFT) -> 135.0f
                directions == setOf(VirtualDpadDirection.LEFT) -> 180.0f
                directions == setOf(VirtualDpadDirection.UP, VirtualDpadDirection.LEFT) -> -135.0f
                directions == setOf(VirtualDpadDirection.UP) -> -90.0f
                directions == setOf(VirtualDpadDirection.UP, VirtualDpadDirection.RIGHT) -> -45.0f
                else -> null
            }
        }
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value % 360.0f
        if (normalized > 180.0f) normalized -= 360.0f
        if (normalized < -180.0f) normalized += 360.0f
        return normalized
    }

    private fun angularDistanceDegrees(first: Float, second: Float): Float =
        abs(normalizeDegrees(first - second))
}
