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

enum class VirtualAnalogStickMode(val token: String) {
    FIXED("fixed"),
    FLOATING("floating");

    companion object {
        fun fromToken(token: String): VirtualAnalogStickMode? = entries.firstOrNull {
            it.token.equals(token.trim(), ignoreCase = true) ||
                it.name.equals(token.trim(), ignoreCase = true)
        }
    }
}

data class VirtualAnalogStickSettings(
    val centerXFraction: Float = 0.5f,
    val centerYFraction: Float = 0.5f,
    val radiusFractionOfShortestSide: Float = 0.15f,
    val mode: VirtualAnalogStickMode = VirtualAnalogStickMode.FIXED,
) {
    init {
        require(centerXFraction.isFinite() && centerXFraction in 0.0f..1.0f) {
            "centerXFraction must be in [0, 1]"
        }
        require(centerYFraction.isFinite() && centerYFraction in 0.0f..1.0f) {
            "centerYFraction must be in [0, 1]"
        }
        require(
            radiusFractionOfShortestSide.isFinite() &&
                radiusFractionOfShortestSide > 0.0f,
        ) { "radiusFractionOfShortestSide must be positive" }
    }
}

data class VirtualAnalogSample(
    val x: Float,
    val y: Float,
    val active: Boolean,
)

data class VirtualAnalogVisualState(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val thumbX: Float,
    val thumbY: Float,
    val active: Boolean,
)

private data class VirtualAnalogGeometry(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
)

/**
 * Android-free virtual analog source. It owns one contact, clamps the thumb to a circle, and
 * emits normalized coordinates in the same coordinate system as Android gamepad axes. A floating
 * stick takes its center from the initial touch; a fixed stick uses the configured center.
 */
class VirtualAnalogStick @JvmOverloads constructor(
    private val settings: VirtualAnalogStickSettings = VirtualAnalogStickSettings(),
) {
    private var viewport: GuestViewport? = null
    private var geometry: VirtualAnalogGeometry? = null
    private var activeToken: PointerSourceToken? = null
    private var sample = VirtualAnalogSample(0.0f, 0.0f, false)
    private var thumbX = 0.0f
    private var thumbY = 0.0f

    fun begin(
        token: PointerSourceToken,
        viewport: GuestViewport,
        touchX: Float? = null,
        touchY: Float? = null,
    ): VirtualAnalogSample? {
        if (activeToken != null) return null
        this.viewport = viewport
        val fixed = fixedGeometry(viewport)
        val center = if (settings.mode == VirtualAnalogStickMode.FLOATING &&
            touchX != null && touchY != null
        ) {
            viewport.point(touchX, touchY)
        } else {
            viewport.point(fixed.centerX, fixed.centerY)
        }
        val next = fixed.copy(centerX = center.x.toFloat(), centerY = center.y.toFloat())
        geometry = next
        activeToken = token
        thumbX = next.centerX
        thumbY = next.centerY
        sample = VirtualAnalogSample(0.0f, 0.0f, true)
        return sample
    }

    fun move(token: PointerSourceToken, touchX: Float, touchY: Float): VirtualAnalogSample? {
        if (activeToken != token) return null
        val next = geometry ?: return null
        val safeX = if (touchX.isFinite()) touchX else next.centerX
        val safeY = if (touchY.isFinite()) touchY else next.centerY
        val deltaX = safeX - next.centerX
        val deltaY = safeY - next.centerY
        val distance = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()
        val scale = if (distance > next.radius && distance > 0.0f) next.radius / distance else 1.0f
        thumbX = next.centerX + deltaX * scale
        thumbY = next.centerY + deltaY * scale
        sample = VirtualAnalogSample(
            x = ((thumbX - next.centerX) / next.radius).coerceIn(-1.0f, 1.0f),
            y = ((thumbY - next.centerY) / next.radius).coerceIn(-1.0f, 1.0f),
            active = true,
        )
        return sample
    }

    fun end(token: PointerSourceToken): VirtualAnalogSample? {
        if (activeToken != token) return null
        activeToken = null
        if (settings.mode == VirtualAnalogStickMode.FLOATING) geometry = null
        sample = VirtualAnalogSample(0.0f, 0.0f, false)
        return sample
    }

    fun reset(): VirtualAnalogSample? {
        val token = activeToken ?: return null
        return end(token)
    }

    fun currentSample(): VirtualAnalogSample = sample

    fun hasGesture(): Boolean = activeToken != null

    fun visualState(viewport: GuestViewport): VirtualAnalogVisualState {
        val resolved = geometry ?: fixedGeometry(viewport)
        val visualThumbX = if (activeToken == null) resolved.centerX else thumbX
        val visualThumbY = if (activeToken == null) resolved.centerY else thumbY
        return VirtualAnalogVisualState(
            centerX = resolved.centerX,
            centerY = resolved.centerY,
            radius = resolved.radius,
            thumbX = visualThumbX,
            thumbY = visualThumbY,
            active = activeToken != null,
        )
    }

    private fun fixedGeometry(viewport: GuestViewport): VirtualAnalogGeometry {
        val resolved = VirtualControlGeometryResolver.resolve(
            width = viewport.width.toFloat(),
            height = viewport.height.toFloat(),
            preferredCenterXFraction = settings.centerXFraction,
            preferredCenterYFraction = settings.centerYFraction,
            radiusFractionOfShortestSide = settings.radiusFractionOfShortestSide,
        )
        return VirtualAnalogGeometry(
            centerX = resolved.centerX,
            centerY = resolved.centerY,
            radius = resolved.radius,
        )
    }
}
