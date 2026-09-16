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

/** Physical and virtual contacts are separate from the guest MIDP pointer channel (0). */
enum class PointerSourceKind { PHYSICAL, VIRTUAL }

data class PointerSourceToken(
    val kind: PointerSourceKind,
    val contactId: Int,
    val targetId: Any,
    val generation: Long,
)

sealed interface PointerLeaseAction {
    val token: PointerSourceToken

    data class Press(
        override val token: PointerSourceToken,
        val x: Int,
        val y: Int,
    ) : PointerLeaseAction

    data class Drag(
        override val token: PointerSourceToken,
        val x: Int,
        val y: Int,
    ) : PointerLeaseAction

    data class Release(
        override val token: PointerSourceToken,
        val x: Int,
        val y: Int,
    ) : PointerLeaseAction
}

/**
 * Owns the one virtual guest-pointer channel used by cursor/joystick modes. It never invents an
 * Android pointer id and never calls a guest callback; the Canvas adapter consumes the actions.
 */
class PointerLeaseController(
    private val guestPointerId: Int = 0,
) {
    private val physical = LinkedHashMap<PointerSourceToken, Pair<Int, Int>>()
    private var virtualToken: PointerSourceToken? = null
    private var virtualPosition: Pair<Int, Int>? = null

    fun guestPointerId(): Int = guestPointerId

    fun beginPhysical(token: PointerSourceToken, x: Int, y: Int): List<PointerLeaseAction> {
        val actions = ArrayList<PointerLeaseAction>(1)
        releaseVirtualInto(actions)
        physical[token] = x to y
        return actions
    }

    fun updatePhysical(token: PointerSourceToken, x: Int, y: Int) {
        if (physical.containsKey(token)) physical[token] = x to y
    }

    fun endPhysical(token: PointerSourceToken, x: Int, y: Int): List<PointerLeaseAction> {
        if (physical.remove(token) == null) return emptyList()
        return emptyList()
    }

    /** Starts only while no physical guest gesture owns the channel. */
    fun beginVirtual(token: PointerSourceToken, centerX: Int, centerY: Int): List<PointerLeaseAction> {
        if (physical.isNotEmpty() || virtualToken != null) return emptyList()
        virtualToken = token
        virtualPosition = centerX to centerY
        return listOf(PointerLeaseAction.Press(token, centerX, centerY))
    }

    fun updateVirtual(token: PointerSourceToken, x: Int, y: Int): List<PointerLeaseAction> {
        if (virtualToken != token) return emptyList()
        val previous = virtualPosition
        if (previous != null && previous.first == x && previous.second == y) return emptyList()
        virtualPosition = x to y
        return listOf(PointerLeaseAction.Drag(token, x, y))
    }

    fun endVirtual(token: PointerSourceToken, x: Int, y: Int): List<PointerLeaseAction> {
        if (virtualToken != token) return emptyList()
        virtualToken = null
        virtualPosition = null
        return listOf(PointerLeaseAction.Release(token, x, y))
    }

    /** Releases the virtual gesture and forgets all contacts during hide/focus/target changes. */
    fun reset(releaseVirtual: Boolean = true): List<PointerLeaseAction> {
        val actions = ArrayList<PointerLeaseAction>(1)
        if (releaseVirtual) releaseVirtualInto(actions)
        else {
            virtualToken = null
            virtualPosition = null
        }
        physical.clear()
        return actions
    }

    fun hasPhysicalGesture(): Boolean = physical.isNotEmpty()

    fun hasVirtualGesture(): Boolean = virtualToken != null

    private fun releaseVirtualInto(actions: MutableList<PointerLeaseAction>) {
        val token = virtualToken ?: return
        val position = virtualPosition ?: (0 to 0)
        actions += PointerLeaseAction.Release(token, position.first, position.second)
        virtualToken = null
        virtualPosition = null
    }
}
