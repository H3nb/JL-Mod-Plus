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

import kotlin.math.hypot
import kotlin.math.roundToInt

/** Guest-coordinate bounds used by both pointer producers. */
data class GuestViewport(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
    }

    val maxX: Int
        get() = width - 1
    val maxY: Int
        get() = height - 1
    val shortestSide: Float
        get() = minOf(width, height).toFloat()

    fun clampX(value: Float): Int = value.roundToInt().coerceIn(0, maxX)

    fun clampY(value: Float): Int = value.roundToInt().coerceIn(0, maxY)

    fun point(x: Float, y: Float): GuestPoint = GuestPoint(clampX(x), clampY(y))
}

data class GuestPoint(
    val x: Int,
    val y: Int,
)

/** Cursor speed is measured in shortest-guest-side lengths per second. */
data class CursorSettings(
    val speedPerShortestSidePerSecond: Float = 1.0f,
    val maxDeltaMillis: Long = 50L,
) {
    init {
        require(speedPerShortestSidePerSecond.isFinite() && speedPerShortestSidePerSecond >= 0.0f) {
            "speed must be finite and non-negative"
        }
        require(maxDeltaMillis in 1L..1_000L) { "maxDeltaMillis must be between 1 and 1000" }
    }
}

data class CursorPosition(
    val x: Float,
    val y: Float,
)

/**
 * Android-free proportional cursor state machine.
 *
 * The cursor position remains float precision and is clamped to the guest
 * viewport. A pointer gesture exists only while the supplied virtual source
 * token owns the shared [PointerLeaseController] lease. [tick] caps elapsed
 * time and [onResume] discards the pre-resume timestamp, preventing a resume
 * gap from becoming a cursor teleport.
 */
class ProportionalCursorController(
    private val lease: PointerLeaseController,
    private val settings: CursorSettings = CursorSettings(),
) {
    private var viewport: GuestViewport? = null
    private var position = CursorPosition(0.0f, 0.0f)
    private var lastTickMillis: Long? = null
    private var clickToken: PointerSourceToken? = null

    fun setViewport(viewport: GuestViewport, initial: CursorPosition? = null) {
        this.viewport = viewport
        val start = initial ?: CursorPosition(viewport.width / 2.0f, viewport.height / 2.0f)
        position = CursorPosition(
            x = start.x.coerceIn(0.0f, viewport.maxX.toFloat()),
            y = start.y.coerceIn(0.0f, viewport.maxY.toFloat()),
        )
        lastTickMillis = null
    }

    fun position(): CursorPosition = position

    fun onResume(nowMillis: Long) {
        lastTickMillis = nowMillis
    }

    /** Starts an input stream without erasing the accumulated float position. */
    fun onResumeIfUnset(nowMillis: Long) {
        if (lastTickMillis == null) lastTickMillis = nowMillis
    }

    /** Advances the cursor and emits a drag only when a virtual click is held. */
    fun tick(nowMillis: Long, axisX: Float, axisY: Float): List<PointerLeaseAction> {
        val currentViewport = viewport ?: return emptyList()
        val previousTick = lastTickMillis
        lastTickMillis = nowMillis
        if (previousTick == null) return emptyList()

        val elapsedMillis = (nowMillis - previousTick).coerceIn(0L, settings.maxDeltaMillis)
        if (elapsedMillis == 0L) return emptyList()
        val seconds = elapsedMillis / 1_000.0f
        val speed = settings.speedPerShortestSidePerSecond * currentViewport.shortestSide
        val x = finiteAxis(axisX)
        val y = finiteAxis(axisY)
        position = CursorPosition(
            x = (position.x + x * speed * seconds).coerceIn(0.0f, currentViewport.maxX.toFloat()),
            y = (position.y + y * speed * seconds).coerceIn(0.0f, currentViewport.maxY.toFloat()),
        )

        val token = clickToken ?: return emptyList()
        val point = currentViewport.point(position.x, position.y)
        return lease.updateVirtual(token, point.x, point.y)
    }

    fun beginClick(token: PointerSourceToken): List<PointerLeaseAction> {
        val currentViewport = viewport ?: return emptyList()
        if (clickToken != null) return emptyList()
        val point = currentViewport.point(position.x, position.y)
        val actions = lease.beginVirtual(token, point.x, point.y)
        if (actions.isNotEmpty()) clickToken = token
        return actions
    }

    fun endClick(token: PointerSourceToken): List<PointerLeaseAction> {
        if (clickToken != token) return emptyList()
        clickToken = null
        val currentViewport = viewport ?: return emptyList()
        val point = currentViewport.point(position.x, position.y)
        return lease.endVirtual(token, point.x, point.y)
    }

    /** Physical touch takes over the guest pointer and releases a held cursor click. */
    fun beginPhysical(token: PointerSourceToken, x: Int, y: Int): List<PointerLeaseAction> {
        val hadCursorLease = clickToken != null
        val actions = lease.beginPhysical(token, x, y)
        if (hadCursorLease) clickToken = null
        return actions
    }

    fun updatePhysical(token: PointerSourceToken, x: Int, y: Int) =
        lease.updatePhysical(token, x, y)

    fun endPhysical(token: PointerSourceToken, x: Int, y: Int): List<PointerLeaseAction> =
        lease.endPhysical(token, x, y)

    /** Releases the cursor gesture without disturbing another producer's physical contacts. */
    fun reset(): List<PointerLeaseAction> {
        val token = clickToken ?: return emptyList()
        clickToken = null
        val currentViewport = viewport ?: return emptyList()
        val point = currentViewport.point(position.x, position.y)
        return lease.endVirtual(token, point.x, point.y)
    }

    fun hasClick(): Boolean = clickToken != null

    private fun finiteAxis(value: Float): Float = if (value.isFinite()) value.coerceIn(-1.0f, 1.0f) else 0.0f
}

/** Relative-to-guest geometry persisted by a virtual joystick configuration. */
data class VirtualJoystickSettings(
    val centerXFraction: Float = 0.5f,
    val centerYFraction: Float = 0.5f,
    val radiusFractionOfShortestSide: Float = 0.15f,
) {
    init {
        require(centerXFraction.isFinite() && centerXFraction in 0.0f..1.0f) {
            "centerXFraction must be in [0, 1]"
        }
        require(centerYFraction.isFinite() && centerYFraction in 0.0f..1.0f) {
            "centerYFraction must be in [0, 1]"
        }
        require(radiusFractionOfShortestSide.isFinite() && radiusFractionOfShortestSide > 0.0f) {
            "radiusFractionOfShortestSide must be positive"
        }
    }
}

data class VirtualJoystickVisualState(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val thumbX: Float,
    val thumbY: Float,
    val active: Boolean,
)

private data class ResolvedJoystick(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
)

/**
 * Android-free virtual touch joystick producer.
 *
 * The press is at the configured relative center. Motion is clamped to the
 * configured radius, and release returns to center before releasing when the
 * joystick moved. Cursor and joystick instances can share one
 * [PointerLeaseController] to make their virtual guest pointer producer
 * mutually exclusive.
 */
class VirtualTouchJoystickController(
    private val lease: PointerLeaseController,
    private val settings: VirtualJoystickSettings = VirtualJoystickSettings(),
) {
    private var viewport: GuestViewport? = null
    private var resolved: ResolvedJoystick? = null
    private var activeToken: PointerSourceToken? = null
    private var currentPoint: GuestPoint? = null

    fun begin(token: PointerSourceToken, viewport: GuestViewport): List<PointerLeaseAction> {
        if (activeToken != null) return emptyList()
        this.viewport = viewport
        val geometry = resolve(viewport)
        resolved = geometry
        val center = viewport.point(geometry.centerX, geometry.centerY)
        val actions = lease.beginVirtual(token, center.x, center.y)
        if (actions.isEmpty()) return emptyList()
        activeToken = token
        currentPoint = center
        return actions
    }

    fun move(token: PointerSourceToken, touchX: Float, touchY: Float): List<PointerLeaseAction> {
        if (activeToken != token) return emptyList()
        val currentViewport = viewport ?: return emptyList()
        val geometry = resolved ?: return emptyList()
        val clamped = clampToCircle(geometry, touchX, touchY)
        val point = currentViewport.point(clamped[0], clamped[1])
        if (point == currentPoint) return emptyList()
        currentPoint = point
        return lease.updateVirtual(token, point.x, point.y)
    }

    /**
     * Drives the joystick from a processed stick vector. The vector is kept in the same
     * coordinate system as the configured center/radius, so Android routing code does not need
     * to duplicate the relative-guest geometry.
     */
    fun moveVector(token: PointerSourceToken, vectorX: Float, vectorY: Float): List<PointerLeaseAction> {
        if (activeToken != token) return emptyList()
        val geometry = resolved ?: return emptyList()
        val safeX = if (vectorX.isFinite()) vectorX.coerceIn(-1.0f, 1.0f) else 0.0f
        val safeY = if (vectorY.isFinite()) vectorY.coerceIn(-1.0f, 1.0f) else 0.0f
        return move(
            token,
            geometry.centerX + safeX * geometry.radius,
            geometry.centerY + safeY * geometry.radius,
        )
    }

    fun end(token: PointerSourceToken): List<PointerLeaseAction> {
        if (activeToken != token) return emptyList()
        val currentViewport = viewport ?: return emptyList()
        val geometry = resolved ?: return emptyList()
        val center = currentViewport.point(geometry.centerX, geometry.centerY)
        val actions = ArrayList<PointerLeaseAction>(2)
        if (currentPoint != center) {
            actions += lease.updateVirtual(token, center.x, center.y)
        }
        actions += lease.endVirtual(token, center.x, center.y)
        activeToken = null
        currentPoint = null
        return actions
    }

    /** Physical touch wins and first returns a moved joystick to its center. */
    fun beginPhysical(token: PointerSourceToken, x: Int, y: Int): List<PointerLeaseAction> {
        val actions = ArrayList<PointerLeaseAction>(2)
        activeToken?.let { actions += end(it) }
        actions += lease.beginPhysical(token, x, y)
        return actions
    }

    fun updatePhysical(token: PointerSourceToken, x: Int, y: Int) =
        lease.updatePhysical(token, x, y)

    fun endPhysical(token: PointerSourceToken, x: Int, y: Int): List<PointerLeaseAction> =
        lease.endPhysical(token, x, y)

    fun reset(): List<PointerLeaseAction> = activeToken?.let(::end) ?: emptyList()

    fun hasGesture(): Boolean = activeToken != null

    /** Snapshot used by the Canvas overlay; it has no input or guest-dispatch side effects. */
    fun visualState(viewport: GuestViewport): VirtualJoystickVisualState {
        val geometry = resolved ?: resolve(viewport)
        val center = viewport.point(geometry.centerX, geometry.centerY)
        val thumb = currentPoint ?: center
        return VirtualJoystickVisualState(
            centerX = geometry.centerX,
            centerY = geometry.centerY,
            radius = geometry.radius,
            thumbX = thumb.x.toFloat(),
            thumbY = thumb.y.toFloat(),
            active = activeToken != null,
        )
    }

    private fun resolve(viewport: GuestViewport): ResolvedJoystick = ResolvedJoystick(
        centerX = settings.centerXFraction * viewport.width,
        centerY = settings.centerYFraction * viewport.height,
        radius = settings.radiusFractionOfShortestSide * viewport.shortestSide,
    )

    private fun clampToCircle(geometry: ResolvedJoystick, x: Float, y: Float): FloatArray {
        val safeX = if (x.isFinite()) x else geometry.centerX
        val safeY = if (y.isFinite()) y else geometry.centerY
        val dx = safeX - geometry.centerX
        val dy = safeY - geometry.centerY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance <= geometry.radius || distance == 0.0f) {
            return floatArrayOf(safeX, safeY)
        }
        val scale = geometry.radius / distance
        return floatArrayOf(
            geometry.centerX + dx * scale,
            geometry.centerY + dy * scale,
        )
    }
}
