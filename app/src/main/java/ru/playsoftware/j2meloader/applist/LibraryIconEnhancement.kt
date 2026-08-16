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

package ru.playsoftware.j2meloader.applist

import kotlin.math.roundToInt

internal const val LIBRARY_ICON_ENHANCEMENT_VERSION = 1

internal data class LibraryIconEnhancementDecision(
    val apply: Boolean,
    val targetWidth: Int,
    val targetHeight: Int,
    val strength: Float,
)

/**
 * Keeps enhancement intentionally bounded: only low-resolution/general raster artwork is
 * considered, pixel art is a hard bypass, and already-large sources are left untouched.
 */
internal fun decideLibraryIconEnhancement(
    enabled: Boolean,
    pixelArt: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    targetSizePx: Int,
    strengthScale: Float = 1f,
): LibraryIconEnhancementDecision {
    if (!enabled || pixelArt || sourceWidth <= 0 || sourceHeight <= 0 || targetSizePx <= 0) {
        return LibraryIconEnhancementDecision(false, sourceWidth, sourceHeight, 0f)
    }

    val sourceMax = maxOf(sourceWidth, sourceHeight)
    val resolutionRatio = sourceMax.toFloat() / targetSizePx.toFloat()
    if (resolutionRatio > LIBRARY_ENHANCE_MAX_SOURCE_RATIO || strengthScale <= 0f) {
        return LibraryIconEnhancementDecision(false, sourceWidth, sourceHeight, 0f)
    }

    val scale = if (sourceMax < targetSizePx) {
        targetSizePx.toFloat() / sourceMax.toFloat()
    } else {
        1f
    }
    val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    val baseStrength = when {
        resolutionRatio <= 0.50f -> 0.20f
        resolutionRatio <= 0.80f -> 0.17f
        resolutionRatio <= 1.00f -> 0.14f
        else -> 0.10f
    }
    return LibraryIconEnhancementDecision(
        apply = true,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        strength = (baseStrength * strengthScale).coerceIn(0f, LIBRARY_ENHANCE_MAX_STRENGTH),
    )
}

/**
 * Small 5-tap detail filter. Transparent/semi-transparent edges are copied verbatim so
 * sharpening cannot create bright/dark fringes around legacy sprites or logos.
 */
internal fun sharpenLibraryPixels(
    source: IntArray,
    width: Int,
    height: Int,
    strength: Float,
): IntArray {
    if (width < 3 || height < 3 || source.size < width * height || strength <= 0f) {
        return source.copyOf()
    }
    val output = source.copyOf()
    for (y in 1 until height - 1) {
        val rowOffset = y * width
        for (x in 1 until width - 1) {
            val index = rowOffset + x
            val center = source[index]
            val left = source[index - 1]
            val right = source[index + 1]
            val up = source[index - width]
            val down = source[index + width]
            if (
                alpha(center) < LIBRARY_ENHANCE_OPAQUE_ALPHA ||
                alpha(left) < LIBRARY_ENHANCE_OPAQUE_ALPHA ||
                alpha(right) < LIBRARY_ENHANCE_OPAQUE_ALPHA ||
                alpha(up) < LIBRARY_ENHANCE_OPAQUE_ALPHA ||
                alpha(down) < LIBRARY_ENHANCE_OPAQUE_ALPHA
            ) {
                continue
            }

            val red = sharpenChannel(center, left, right, up, down, 16, strength)
            val green = sharpenChannel(center, left, right, up, down, 8, strength)
            val blue = sharpenChannel(center, left, right, up, down, 0, strength)
            output[index] = (alpha(center) shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return output
}

private fun sharpenChannel(
    centerPixel: Int,
    leftPixel: Int,
    rightPixel: Int,
    upPixel: Int,
    downPixel: Int,
    shift: Int,
    strength: Float,
): Int {
    val center = channel(centerPixel, shift)
    val left = channel(leftPixel, shift)
    val right = channel(rightPixel, shift)
    val up = channel(upPixel, shift)
    val down = channel(downPixel, shift)
    val neighborAverage = (left + right + up + down) / 4f
    val sharpened = center + (center - neighborAverage) * strength
    val localMin = minOf(center, left, right, up, down) - LIBRARY_ENHANCE_LOCAL_MARGIN
    val localMax = maxOf(center, left, right, up, down) + LIBRARY_ENHANCE_LOCAL_MARGIN
    return sharpened.roundToInt().coerceIn(localMin.coerceAtLeast(0), localMax.coerceAtMost(255))
}

private fun alpha(pixel: Int): Int = (pixel ushr 24) and 0xff

private fun channel(pixel: Int, shift: Int): Int = (pixel ushr shift) and 0xff

private const val LIBRARY_ENHANCE_MAX_SOURCE_RATIO = 1.10f
private const val LIBRARY_ENHANCE_MAX_STRENGTH = 0.22f
private const val LIBRARY_ENHANCE_OPAQUE_ALPHA = 240
private const val LIBRARY_ENHANCE_LOCAL_MARGIN = 12
