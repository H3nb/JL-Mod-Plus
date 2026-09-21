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

import kotlin.math.min

/**
 * Resolves persisted normalized grouped-control geometry into a viewport-safe runtime geometry.
 *
 * Persisted center fractions remain the user's preferred position. Resolution is intentionally
 * side-effect free so passive viewport/orientation changes cannot drift the stored preference.
 */
object VirtualControlGeometryResolver {
    @JvmStatic
    fun resolve(
        width: Float,
        height: Float,
        preferredCenterXFraction: Float,
        preferredCenterYFraction: Float,
        radiusFractionOfShortestSide: Float,
    ): VirtualDpadGeometry {
        val safeWidth = positiveFiniteOrOne(width)
        val safeHeight = positiveFiniteOrOne(height)
        val shortest = min(safeWidth, safeHeight)
        val safeRadiusFraction = when {
            !radiusFractionOfShortestSide.isFinite() ||
                radiusFractionOfShortestSide <= 0.0f -> 0.01f
            else -> radiusFractionOfShortestSide.coerceAtMost(0.5f)
        }
        val radius = safeRadiusFraction * shortest

        val preferredX = unitFiniteOrCenter(preferredCenterXFraction) * safeWidth
        val preferredY = unitFiniteOrCenter(preferredCenterYFraction) * safeHeight
        val centerX = preferredX.coerceIn(radius, safeWidth - radius)
        val centerY = preferredY.coerceIn(radius, safeHeight - radius)

        return VirtualDpadGeometry(centerX, centerY, radius)
    }

    private fun positiveFiniteOrOne(value: Float): Float =
        if (value.isFinite() && value > 0.0f) value else 1.0f

    private fun unitFiniteOrCenter(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0.0f, 1.0f) else 0.5f
}
