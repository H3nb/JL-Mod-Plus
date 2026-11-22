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

package io.github.h3nb.jlmodplus.applist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import io.github.h3nb.jlmodplus.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val LIBRARY_GRID_ARTWORK_FRACTION = 0.78f
private val LibraryGridMaxArtworkSize = 72.dp

private data class LibraryDominantColorSample(
    val color: Int,
    val populationRatio: Float,
)

private data class LibraryIconAnalysis(
    val contentBounds: Rect,
    val framedCropBounds: Rect?,
    val edgeFillColor: Int?,
    val backingColor: Int?,
    val dominantColor: Int?,
    val presentation: LibraryIconPresentationDecision,
    val foregroundLuminance: Float,
    val pixelArt: Boolean,
)

internal data class LibraryNormalizedIcon(
    val bitmap: ImageBitmap,
    val filterQuality: FilterQuality,
    val presentationMode: LibraryIconPresentationMode,
    val visualScale: Float,
    val tileColor: Color? = null,
)

private const val LIBRARY_ICON_PRESENTATION_VERSION = 7
private const val LIBRARY_ICON_WORK_CONCURRENCY = 3
private const val LIBRARY_ICON_CACHE_MIN_BYTES = 8L * 1024L * 1024L
private const val LIBRARY_ICON_CACHE_MAX_BYTES = 24L * 1024L * 1024L
private val LibraryIconWorkSemaphore = Semaphore(LIBRARY_ICON_WORK_CONCURRENCY)
private val LibraryIconCacheBytes = (Runtime.getRuntime().maxMemory() / 16L)
    .coerceIn(LIBRARY_ICON_CACHE_MIN_BYTES, LIBRARY_ICON_CACHE_MAX_BYTES)
    .toInt()
private val LibraryIconCache = object : LruCache<String, LibraryNormalizedIcon>(LibraryIconCacheBytes) {
    override fun sizeOf(key: String, value: LibraryNormalizedIcon): Int {
        return (value.bitmap.width.toLong() * value.bitmap.height.toLong() * 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
    }
}

internal fun normalizeLibraryIcon(
    fileSource: Bitmap,
    enhanceIcon: Boolean,
    preserveArtwork: Boolean = false,
): LibraryNormalizedIcon {
    if (preserveArtwork || !enhanceIcon) {
        val backgroundColor = if (preserveArtwork && enhanceIcon) {
            fileSource.analyzeLibraryIcon()?.let { it.edgeFillColor ?: it.backingColor }
        } else {
            null
        }
        return LibraryNormalizedIcon(
            bitmap = fileSource.asImageBitmap(),
            filterQuality = fileSource.libraryFilterQuality(),
            presentationMode = LibraryIconPresentationMode.SafeFit,
            visualScale = 1f,
            tileColor = backgroundColor?.toComposeLibraryColor(),
        )
    }

    val analysis = fileSource.analyzeLibraryIcon()
        ?: return LibraryNormalizedIcon(
            bitmap = fileSource.asImageBitmap(),
            filterQuality = fileSource.libraryFilterQuality(),
            presentationMode = LibraryIconPresentationMode.SafeFit,
            visualScale = 1f,
        )
    val cropBounds = if (analysis.presentation.mode == LibraryIconPresentationMode.Subject) {
        analysis.framedCropBounds ?: analysis.contentBounds
    } else {
        null
    }
    val normalized = if (cropBounds != null && !cropBounds.isFullBitmap(fileSource)) {
        fileSource.cropLibraryBounds(cropBounds)
    } else {
        fileSource
    }
    val tileColor = when (analysis.presentation.mode) {
        LibraryIconPresentationMode.Cover -> analysis.edgeFillColor
            ?.takeIf { fileSource.hasUniformLibraryBorderColor(it) }
            ?.toComposeLibraryColor()
        LibraryIconPresentationMode.Fallback -> null
        LibraryIconPresentationMode.Backed,
        LibraryIconPresentationMode.Subject,
        LibraryIconPresentationMode.SafeFit -> {
            val sourceBackground = if (analysis.presentation.mode == LibraryIconPresentationMode.Backed) {
                analysis.edgeFillColor ?: analysis.backingColor
            } else {
                analysis.edgeFillColor
            }
            sourceBackground?.toComposeLibraryColor() ?: run {
                val sourceColor = analysis.dominantColor?.toComposeLibraryColor()
                    ?: normalized.findRepresentativeColor()
                    ?: normalized.findAverageVisibleColor()
                sourceColor?.let { syntheticLibraryTileColor(it, analysis.foregroundLuminance) }
            }
        }
    }
    val imageBitmap = normalized.asImageBitmap()
    if (normalized !== fileSource) {
        fileSource.recycle()
    }
    return LibraryNormalizedIcon(
        bitmap = imageBitmap,
        filterQuality = if (analysis.pixelArt) FilterQuality.None else FilterQuality.Medium,
        presentationMode = analysis.presentation.mode,
        visualScale = analysis.presentation.visualScale,
        tileColor = tileColor,
    )
}

private fun Bitmap.cropLibraryBounds(bounds: Rect): Bitmap {
    val left = bounds.left.coerceIn(0, width)
    val top = bounds.top.coerceIn(0, height)
    val right = bounds.right.coerceIn(left, width)
    val bottom = bounds.bottom.coerceIn(top, height)
    val cropWidth = right - left
    val cropHeight = bottom - top
    if (cropWidth <= 0 || cropHeight <= 0 || (cropWidth == width && cropHeight == height)) {
        return this
    }
    return try {
        Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
    } catch (_: OutOfMemoryError) {
        this
    } catch (_: RuntimeException) {
        this
    }
}

private fun decodeLibraryBitmap(path: String, targetSizePx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeTarget = (targetSizePx.coerceAtLeast(48) * 2).coerceAtMost(1024)
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > decodeTarget ||
            bounds.outHeight / sampleSize > decodeTarget
        ) {
            if (sampleSize >= 1 shl 20) break
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, options)
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun Bitmap.analyzeLibraryIcon(): LibraryIconAnalysis? {
    if (width <= 0 || height <= 0) return null

    val row = IntArray(width)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var visiblePixels = 0L
    var semiTransparentPixels = 0L
    var luminanceSum = 0.0
    var visibleWeight = 0.0
    var seenColors: LongArray? = null
    var distinctColorCount = 0

    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) {
            val pixel = row[x]
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue

            visiblePixels++
            if (alpha < LIBRARY_PIXEL_ART_OPAQUE_ALPHA) semiTransparentPixels++
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y

            val alphaWeight = alpha / 255.0
            luminanceSum += pixelLibraryLuminance(pixel) * alphaWeight
            visibleWeight += alphaWeight
            if (distinctColorCount <= LIBRARY_COLOR_SAMPLE_MAX_BINS) {
                val colorBin = quantizeLibraryColor(pixel)
                val bins = seenColors ?: LongArray(64).also { seenColors = it }
                val word = colorBin ushr 6
                val mask = 1L shl (colorBin and 63)
                if ((bins[word] and mask) == 0L) {
                    bins[word] = bins[word] or mask
                    distinctColorCount++
                }
            }
        }
    }
    if (right < left || bottom < top || visiblePixels == 0L || visibleWeight <= 0.0) return null

    val contentBounds = Rect(left, top, right + 1, bottom + 1)
    val canvasArea = width.toLong() * height.toLong()
    val boundsArea = contentBounds.width().toLong() * contentBounds.height().toLong()
    val boundsCoverage = if (canvasArea == 0L) 1f else boundsArea.toFloat() / canvasArea.toFloat()
    val occupancy = if (boundsArea == 0L) 1f else visiblePixels.toFloat() / boundsArea.toFloat()
    val transparentRatio = if (canvasArea == 0L) {
        0f
    } else {
        1f - visiblePixels.toFloat() / canvasArea.toFloat()
    }
    val maxContentDimension = maxOf(contentBounds.width(), contentBounds.height()).coerceAtLeast(1)
    val aspectFill = minOf(contentBounds.width(), contentBounds.height()).toFloat() /
        maxContentDimension.toFloat()
    val cornerBackgroundColor = findUniformCornerBackgroundColor()
    val framedCropBounds = cornerBackgroundColor?.let(::findFramedArtworkCropBounds)
    val cornerBackingColor = cornerBackgroundColor?.takeUnless { it.isLibraryNeutralMatte() }
    val preliminaryInput = LibraryIconPresentationInput(
        transparentRatio = transparentRatio,
        boundsCoverage = boundsCoverage,
        occupancy = occupancy,
        aspectFill = aspectFill,
        hasFramedCrop = framedCropBounds != null,
        hasBackingColor = cornerBackingColor != null,
        highColorDiversity = distinctColorCount > LIBRARY_PIXEL_ART_MAX_COLORS,
        sourceAspectRatio = width.toFloat() / height.toFloat(),
    )
    val preliminaryPresentation = decideLibraryIconPresentation(preliminaryInput)

    // Cover never consumes the dominant color. A colored corner already establishes both
    // backing classification and tile color, so the two-pass histogram is also redundant.
    val dominantColor = if (
        preliminaryPresentation.mode == LibraryIconPresentationMode.Cover ||
        cornerBackingColor != null
    ) {
        null
    } else {
        findDominantVisibleColor()
    }
    val dominantBackingColor = dominantColor?.takeIf { sample ->
        sample.populationRatio >= LIBRARY_BACKING_MIN_DOMINANT_RATIO &&
            boundsCoverage >= LIBRARY_BACKING_MIN_BOUNDS_COVERAGE &&
            occupancy >= LIBRARY_BACKING_MIN_OCCUPANCY &&
            transparentRatio <= LIBRARY_BACKING_MAX_TRANSPARENT_RATIO
    }?.color
    val backingColor = cornerBackingColor ?: dominantBackingColor
    val presentation = if (
        preliminaryPresentation.mode == LibraryIconPresentationMode.Cover ||
        cornerBackingColor != null
    ) {
        preliminaryPresentation
    } else {
        decideLibraryIconPresentation(
            preliminaryInput.copy(hasBackingColor = backingColor != null),
        )
    }
    val semiTransparentRatio = semiTransparentPixels.toFloat() / visiblePixels.toFloat()
    val pixelArt = maxOf(width, height) <= LIBRARY_PIXEL_ART_MAX_DIMENSION &&
        distinctColorCount <= LIBRARY_PIXEL_ART_MAX_COLORS &&
        semiTransparentRatio <= LIBRARY_PIXEL_ART_MAX_SEMI_TRANSPARENT_RATIO
    val foregroundLuminance = (luminanceSum / visibleWeight).toFloat().coerceIn(0f, 1f)

    return LibraryIconAnalysis(
        contentBounds = contentBounds,
        framedCropBounds = framedCropBounds,
        edgeFillColor = cornerBackgroundColor,
        backingColor = backingColor,
        dominantColor = dominantColor?.color,
        presentation = presentation,
        foregroundLuminance = foregroundLuminance,
        pixelArt = pixelArt,
    )
}

private fun Bitmap.findFramedArtworkCropBounds(backgroundColor: Int): Rect? {
    if (width < 6 || height < 6) return null
    val distanceLimit = LIBRARY_BACKGROUND_FOREGROUND_DISTANCE *
        LIBRARY_BACKGROUND_FOREGROUND_DISTANCE * 3
    val row = IntArray(width)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var foregroundPixels = 0L

    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) {
            val pixel = row[x]
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue
            if (libraryColorDistanceSquared(pixel, backgroundColor) <= distanceLimit) continue

            foregroundPixels++
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top || foregroundPixels == 0L) return null

    val canvasArea = width.toLong() * height.toLong()
    val foregroundRatio = foregroundPixels.toFloat() / canvasArea.toFloat()
    if (
        foregroundRatio < LIBRARY_FRAMED_MIN_FOREGROUND_RATIO ||
        foregroundRatio > LIBRARY_FRAMED_MAX_FOREGROUND_RATIO
    ) {
        return null
    }

    val opticalBounds = Rect(left, top, right + 1, bottom + 1)
    val opticalCoverage = opticalBounds.width().toLong() * opticalBounds.height().toLong() /
        canvasArea.toDouble()
    if (opticalCoverage >= LIBRARY_FRAMED_MAX_OPTICAL_COVERAGE) return null

    val cropBounds = opticalBounds.toLibraryFramedCrop(width, height)
    val cropCoverage = cropBounds.width().toLong() * cropBounds.height().toLong() /
        canvasArea.toDouble()
    return cropBounds.takeIf { cropCoverage <= LIBRARY_FRAMED_MAX_CROP_COVERAGE }
}

private fun Bitmap.findUniformCornerBackgroundColor(): Int? {
    val minDimension = minOf(width, height)
    if (minDimension < 6) return null

    val patchSize = maxOf(2, minDimension / 8).coerceAtMost(minDimension)
    val step = maxOf(1, patchSize / 4)
    val cornerRects = arrayOf(
        Rect(0, 0, patchSize, patchSize),
        Rect(width - patchSize, 0, width, patchSize),
        Rect(0, height - patchSize, patchSize, height),
        Rect(width - patchSize, height - patchSize, width, height),
    )
    val samplesPerSide = (patchSize - 1) / step + 1
    val maxSamples = cornerRects.size * samplesPerSide * samplesPerSide
    val sampledPixels = IntArray(maxSamples)
    val quantizedBins = IntArray(maxSamples)
    val cornerStarts = IntArray(cornerRects.size + 1)
    val row = IntArray(patchSize)
    var totalSamples = 0

    cornerRects.forEachIndexed { index, rect ->
        cornerStarts[index] = totalSamples
        for (y in rect.top until rect.bottom step step) {
            getPixels(row, 0, patchSize, rect.left, y, patchSize, 1)
            for (x in 0 until patchSize step step) {
                val pixel = row[x]
                val alpha = (pixel ushr 24) and 0xff
                if (alpha < LIBRARY_BACKGROUND_SAMPLE_ALPHA) continue
                sampledPixels[totalSamples] = pixel
                quantizedBins[totalSamples] = quantizeLibraryColor(pixel)
                totalSamples++
            }
        }
    }
    cornerStarts[cornerRects.size] = totalSamples
    if (totalSamples < LIBRARY_BACKGROUND_MIN_CORNER_SAMPLES) return null

    // At most 196 corner samples are stored. Sorting only these avoids a full
    // 4096-entry histogram and keeps the original lowest-bin tie-breaking behavior.
    quantizedBins.sort(0, totalSamples)
    var dominantBin = quantizedBins[0]
    var dominantCount = 0
    var start = 0
    while (start < totalSamples) {
        val bin = quantizedBins[start]
        var end = start + 1
        while (end < totalSamples && quantizedBins[end] == bin) end++
        val count = end - start
        if (count > dominantCount) {
            dominantBin = bin
            dominantCount = count
        }
        start = end
    }
    if (dominantCount.toFloat() / totalSamples < LIBRARY_BACKGROUND_MIN_DOMINANT_BIN_RATIO) {
        return null
    }

    val candidate = quantizedLibraryColorCenter(dominantBin)
    val distanceLimit = LIBRARY_BACKGROUND_EDGE_DISTANCE *
        LIBRARY_BACKGROUND_EDGE_DISTANCE * 3
    var closeSamples = 0
    var red = 0L
    var green = 0L
    var blue = 0L

    for (index in cornerRects.indices) {
        val sampleCount = cornerStarts[index + 1] - cornerStarts[index]
        if (sampleCount < 2) return null
        var cornerClose = 0
        for (sampleIndex in cornerStarts[index] until cornerStarts[index + 1]) {
            val pixel = sampledPixels[sampleIndex]
            if (libraryColorDistanceSquared(pixel, candidate) > distanceLimit) continue
            cornerClose++
            closeSamples++
            red += AndroidColor.red(pixel)
            green += AndroidColor.green(pixel)
            blue += AndroidColor.blue(pixel)
        }
        if (cornerClose.toFloat() / sampleCount < LIBRARY_BACKGROUND_MIN_PER_CORNER_RATIO) {
            return null
        }
    }
    if (closeSamples.toFloat() / totalSamples < LIBRARY_BACKGROUND_MIN_TOTAL_CORNER_RATIO) {
        return null
    }

    return AndroidColor.rgb(
        (red / closeSamples).toInt().coerceIn(0, 255),
        (green / closeSamples).toInt().coerceIn(0, 255),
        (blue / closeSamples).toInt().coerceIn(0, 255),
    )
}

private fun Bitmap.hasUniformLibraryBorderColor(backgroundColor: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val distanceLimit = LIBRARY_BACKGROUND_EDGE_DISTANCE *
        LIBRARY_BACKGROUND_EDGE_DISTANCE * 3
    fun isMatching(pixel: Int): Boolean {
        return ((pixel ushr 24) and 0xff) >= LIBRARY_BACKGROUND_SAMPLE_ALPHA &&
            libraryColorDistanceSquared(pixel, backgroundColor) <= distanceLimit
    }

    // Check all four edges; matching corners alone do not justify a source-colored letterbox.
    val row = IntArray(width)
    val xStep = maxOf(1, width / 16)
    for (y in intArrayOf(0, height - 1)) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width step xStep) {
            if (!isMatching(row[x])) return false
        }
        if (!isMatching(row[width - 1])) return false
    }
    val yStep = maxOf(1, height / 16)
    for (y in 0 until height step yStep) {
        if (!isMatching(getPixel(0, y)) || !isMatching(getPixel(width - 1, y))) {
            return false
        }
    }
    return isMatching(getPixel(0, height - 1)) &&
        isMatching(getPixel(width - 1, height - 1))
}

private fun Bitmap.findDominantVisibleColor(): LibraryDominantColorSample? {
    if (width <= 0 || height <= 0) return null
    val step = maxOf(1, maxOf(width, height) / 64)
    val bins = IntArray(4096)
    val row = IntArray(width)
    var visibleSamples = 0
    for (y in 0 until height step step) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width step step) {
            val pixel = row[x]
            if (((pixel ushr 24) and 0xff) < MIN_VISIBLE_ALPHA) continue
            bins[quantizeLibraryColor(pixel)]++
            visibleSamples++
        }
    }
    if (visibleSamples == 0) return null

    var dominantBin = -1
    var dominantCount = 0
    for (index in bins.indices) {
        if (bins[index] > dominantCount) {
            dominantCount = bins[index]
            dominantBin = index
        }
    }
    if (dominantBin < 0 || dominantCount == 0) return null

    var red = 0L
    var green = 0L
    var blue = 0L
    var matched = 0
    for (y in 0 until height step step) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width step step) {
            val pixel = row[x]
            if (((pixel ushr 24) and 0xff) < MIN_VISIBLE_ALPHA) continue
            if (quantizeLibraryColor(pixel) != dominantBin) continue
            red += AndroidColor.red(pixel)
            green += AndroidColor.green(pixel)
            blue += AndroidColor.blue(pixel)
            matched++
        }
    }
    if (matched == 0) return null
    return LibraryDominantColorSample(
        color = AndroidColor.rgb(
            (red / matched).toInt().coerceIn(0, 255),
            (green / matched).toInt().coerceIn(0, 255),
            (blue / matched).toInt().coerceIn(0, 255),
        ),
        populationRatio = dominantCount.toFloat() / visibleSamples.toFloat(),
    )
}

private fun Int.isLibraryNeutralMatte(): Boolean {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(this, hsv)
    return hsv[1] <= LIBRARY_MATTE_MAX_SATURATION &&
        (hsv[2] >= LIBRARY_MATTE_LIGHT_VALUE || hsv[2] <= LIBRARY_MATTE_DARK_VALUE)
}

private fun Rect.toLibraryFramedCrop(canvasWidth: Int, canvasHeight: Int): Rect {
    val subjectMargin = maxOf(
        2,
        (maxOf(width(), height()) * LIBRARY_FRAMED_SUBJECT_MARGIN).roundToInt(),
    )
    var desiredWidth = (width() + subjectMargin * 2).coerceAtMost(canvasWidth)
    var desiredHeight = (height() + subjectMargin * 2).coerceAtMost(canvasHeight)
    desiredWidth = maxOf(
        desiredWidth,
        (canvasWidth / LIBRARY_FRAMED_MAX_ZOOM).roundToInt().coerceAtLeast(1),
    )
    desiredHeight = maxOf(
        desiredHeight,
        (canvasHeight / LIBRARY_FRAMED_MAX_ZOOM).roundToInt().coerceAtLeast(1),
    )

    val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat()
    val desiredAspect = desiredWidth.toFloat() / desiredHeight.toFloat()
    if (desiredAspect < canvasAspect) {
        desiredWidth = (desiredHeight * canvasAspect).roundToInt().coerceAtMost(canvasWidth)
    } else if (desiredAspect > canvasAspect) {
        desiredHeight = (desiredWidth / canvasAspect).roundToInt().coerceAtMost(canvasHeight)
    }

    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val cropLeft = (centerX - desiredWidth / 2f)
        .roundToInt()
        .coerceIn(0, canvasWidth - desiredWidth)
    val cropTop = (centerY - desiredHeight / 2f)
        .roundToInt()
        .coerceIn(0, canvasHeight - desiredHeight)
    return Rect(
        cropLeft,
        cropTop,
        cropLeft + desiredWidth,
        cropTop + desiredHeight,
    )
}

private fun quantizedLibraryColorCenter(bin: Int): Int {
    val red = ((bin shr 8) and 0x0f) * 17
    val green = ((bin shr 4) and 0x0f) * 17
    val blue = (bin and 0x0f) * 17
    return AndroidColor.rgb(red, green, blue)
}

private fun libraryColorDistanceSquared(first: Int, second: Int): Int {
    val red = AndroidColor.red(first) - AndroidColor.red(second)
    val green = AndroidColor.green(first) - AndroidColor.green(second)
    val blue = AndroidColor.blue(first) - AndroidColor.blue(second)
    return red * red + green * green + blue * blue
}

private fun Rect.isFullBitmap(bitmap: Bitmap): Boolean {
    return left == 0 && top == 0 && right == bitmap.width && bottom == bitmap.height
}

private fun Bitmap.libraryFilterQuality(): FilterQuality {
    return if (width <= 64 && height <= 64) FilterQuality.None else FilterQuality.Medium
}

private fun Bitmap.findRepresentativeColor(): Color? {
    if (width <= 0 || height <= 0) return null

    val step = maxOf(1, maxOf(width, height) / 32)
    val hueScores = DoubleArray(LIBRARY_HUE_BUCKETS)
    val huePopulation = DoubleArray(LIBRARY_HUE_BUCKETS)
    val hueRed = DoubleArray(LIBRARY_HUE_BUCKETS)
    val hueGreen = DoubleArray(LIBRARY_HUE_BUCKETS)
    val hueBlue = DoubleArray(LIBRARY_HUE_BUCKETS)
    var sampledWeight = 0.0
    val row = IntArray(width)
    val hsv = FloatArray(3)
    for (y in 0 until height step step) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width step step) {
            val pixel = row[x]
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue

            val alphaWeight = alpha / 255.0
            sampledWeight += alphaWeight
            AndroidColor.colorToHSV(pixel, hsv)
            if (hsv[1] >= LIBRARY_MIN_COLOR_SATURATION && hsv[2] >= LIBRARY_MIN_COLOR_VALUE) {
                val hueBucket = (hsv[0] / 360f * LIBRARY_HUE_BUCKETS)
                    .toInt()
                    .coerceIn(0, LIBRARY_HUE_BUCKETS - 1)
                val score = alphaWeight * (0.5 + hsv[1]) * (0.35 + hsv[2])
                hueScores[hueBucket] += score
                huePopulation[hueBucket] += alphaWeight
                hueRed[hueBucket] += AndroidColor.red(pixel) * score
                hueGreen[hueBucket] += AndroidColor.green(pixel) * score
                hueBlue[hueBucket] += AndroidColor.blue(pixel) * score
            }
        }
    }

    if (sampledWeight == 0.0) return null
    val dominantHueBucket = hueScores.indices.maxByOrNull { hueScores[it] } ?: return null
    val dominantHueScore = hueScores[dominantHueBucket]
    val dominantPopulation = huePopulation[dominantHueBucket]
    if (
        dominantHueScore <= 0.0 ||
        dominantPopulation / sampledWeight < LIBRARY_MIN_DOMINANT_COLOR_RATIO
    ) {
        return null
    }

    return Color(
        red = (hueRed[dominantHueBucket] / dominantHueScore / 255.0).toFloat().coerceIn(0f, 1f),
        green = (hueGreen[dominantHueBucket] / dominantHueScore / 255.0).toFloat().coerceIn(0f, 1f),
        blue = (hueBlue[dominantHueBucket] / dominantHueScore / 255.0).toFloat().coerceIn(0f, 1f),
    )
}

private fun Bitmap.findAverageVisibleColor(): Color? {
    if (width <= 0 || height <= 0) return null
    val row = IntArray(width)
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var weight = 0.0
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) {
            val alpha = (pixel ushr 24) and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) continue
            val alphaWeight = alpha / 255.0
            red += AndroidColor.red(pixel) * alphaWeight
            green += AndroidColor.green(pixel) * alphaWeight
            blue += AndroidColor.blue(pixel) * alphaWeight
            weight += alphaWeight
        }
    }
    if (weight == 0.0) return null
    return Color(
        red = (red / weight / 255.0).toFloat().coerceIn(0f, 1f),
        green = (green / weight / 255.0).toFloat().coerceIn(0f, 1f),
        blue = (blue / weight / 255.0).toFloat().coerceIn(0f, 1f),
    )
}

private fun pixelLibraryLuminance(pixel: Int): Double {
    val red = AndroidColor.red(pixel) / 255.0
    val green = AndroidColor.green(pixel) / 255.0
    val blue = AndroidColor.blue(pixel) / 255.0
    return red * 0.2126 + green * 0.7152 + blue * 0.0722
}

private fun quantizeLibraryColor(pixel: Int): Int {
    return ((AndroidColor.red(pixel) shr 4) shl 8) or
        ((AndroidColor.green(pixel) shr 4) shl 4) or
        (AndroidColor.blue(pixel) shr 4)
}

private fun Int.toComposeLibraryColor(): Color {
    return Color(
        red = AndroidColor.red(this) / 255f,
        green = AndroidColor.green(this) / 255f,
        blue = AndroidColor.blue(this) / 255f,
        alpha = 1f,
    )
}

private const val MIN_VISIBLE_ALPHA = 16
private const val LIBRARY_HUE_BUCKETS = 12
private const val LIBRARY_MIN_COLOR_SATURATION = 0.28f
private const val LIBRARY_MIN_COLOR_VALUE = 0.16f
private const val LIBRARY_MIN_DOMINANT_COLOR_RATIO = 0.10
private const val LIBRARY_BACKGROUND_SAMPLE_ALPHA = 240
private const val LIBRARY_BACKGROUND_MIN_CORNER_SAMPLES = 8
private const val LIBRARY_BACKGROUND_MIN_DOMINANT_BIN_RATIO = 0.16f
private const val LIBRARY_BACKGROUND_MIN_PER_CORNER_RATIO = 0.55f
private const val LIBRARY_BACKGROUND_MIN_TOTAL_CORNER_RATIO = 0.70f
private const val LIBRARY_BACKGROUND_EDGE_DISTANCE = 42
private const val LIBRARY_BACKGROUND_FOREGROUND_DISTANCE = 42
private const val LIBRARY_FRAMED_MIN_FOREGROUND_RATIO = 0.035f
private const val LIBRARY_FRAMED_MAX_FOREGROUND_RATIO = 0.72f
private const val LIBRARY_FRAMED_MAX_OPTICAL_COVERAGE = 0.82
private const val LIBRARY_FRAMED_SUBJECT_MARGIN = 0.10f
private const val LIBRARY_FRAMED_MAX_ZOOM = 1.30f
private const val LIBRARY_FRAMED_MAX_CROP_COVERAGE = 0.94
private const val LIBRARY_PIXEL_ART_MAX_DIMENSION = 160
private const val LIBRARY_PIXEL_ART_MAX_COLORS = 48
private const val LIBRARY_COLOR_SAMPLE_MAX_BINS = 64
private const val LIBRARY_PIXEL_ART_OPAQUE_ALPHA = 240
private const val LIBRARY_PIXEL_ART_MAX_SEMI_TRANSPARENT_RATIO = 0.06f
private const val LIBRARY_BACKING_MIN_DOMINANT_RATIO = 0.24f
private const val LIBRARY_BACKING_MIN_BOUNDS_COVERAGE = 0.70f
private const val LIBRARY_BACKING_MIN_OCCUPANCY = 0.72f
private const val LIBRARY_BACKING_MAX_TRANSPARENT_RATIO = 0.30f
private const val LIBRARY_MATTE_MAX_SATURATION = 0.08f
private const val LIBRARY_MATTE_LIGHT_VALUE = 0.92f
private const val LIBRARY_MATTE_DARK_VALUE = 0.10f
private const val LIBRARY_SYNTHETIC_FILL_MIN_SATURATION = 0.12f
private const val LIBRARY_SYNTHETIC_FILL_MAX_SATURATION = 0.30f
private const val LIBRARY_SYNTHETIC_FILL_LIGHT_VALUE = 0.88f
private const val LIBRARY_SYNTHETIC_FILL_DARK_VALUE = 0.30f
private const val LIBRARY_SYNTHETIC_FILL_BRIGHT_FOREGROUND = 0.68f

private fun syntheticLibraryTileColor(
    source: Color,
    foregroundLuminance: Float,
): Color {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (source.red * 255f).toInt().coerceIn(0, 255),
        (source.green * 255f).toInt().coerceIn(0, 255),
        (source.blue * 255f).toInt().coerceIn(0, 255),
        hsv,
    )
    val darkTile = foregroundLuminance >= LIBRARY_SYNTHETIC_FILL_BRIGHT_FOREGROUND
    if (hsv[1] < LIBRARY_MATTE_MAX_SATURATION) {
        val value = if (darkTile) {
            LIBRARY_SYNTHETIC_FILL_DARK_VALUE
        } else {
            LIBRARY_SYNTHETIC_FILL_LIGHT_VALUE
        }
        return Color(value, value, value)
    }
    return Color.hsv(
        hue = hsv[0],
        saturation = hsv[1].coerceIn(
            LIBRARY_SYNTHETIC_FILL_MIN_SATURATION,
            LIBRARY_SYNTHETIC_FILL_MAX_SATURATION,
        ),
        value = if (darkTile) {
            LIBRARY_SYNTHETIC_FILL_DARK_VALUE
        } else {
            LIBRARY_SYNTHETIC_FILL_LIGHT_VALUE
        },
    )
}

@Composable
private fun rememberLibraryIcon(
    app: LibraryAppUiItem,
    contentSize: Dp,
    enhancedIcons: Boolean,
    preserveArtwork: Boolean = false,
): LibraryNormalizedIcon? {
    val contentSizePx = with(LocalDensity.current) { contentSize.roundToPx() }.coerceAtLeast(1)
    val cacheKey = remember(app.iconPath, app.iconRevision, contentSizePx, enhancedIcons, preserveArtwork) {
        libraryIconCacheKey(
            app.iconPath,
            app.iconRevision,
            contentSizePx,
            enhancedIcons,
            preserveArtwork,
        )
    }
    // produceState restarts its producer for new keys but retains the previous value. Give each
    // source/presentation its own state so an already-loaded icon cannot suppress a later load.
    return androidx.compose.runtime.key(cacheKey) {
        val cached = remember { LibraryIconCache.get(cacheKey) }
        produceState<LibraryNormalizedIcon?>(initialValue = cached, cacheKey) {
            if (value != null) return@produceState
            val path = app.iconPath?.takeIf(String::isNotBlank) ?: return@produceState
            val normalized = LibraryIconWorkSemaphore.withPermit {
                // Another visible consumer may have completed this source while we queued.
                LibraryIconCache.get(cacheKey)?.let { return@withPermit it }
                val bitmap = withContext(Dispatchers.IO) {
                    android.os.Trace.beginSection("Library/iconDecode")
                    try {
                        decodeLibraryBitmap(path, contentSizePx)
                    } finally {
                        android.os.Trace.endSection()
                    }
                } ?: return@withPermit null
                withContext(Dispatchers.Default) {
                    android.os.Trace.beginSection("Library/iconNormalize")
                    try {
                        normalizeLibraryIcon(
                            fileSource = bitmap,
                            enhanceIcon = enhancedIcons,
                            preserveArtwork = preserveArtwork,
                        )
                    } finally {
                        android.os.Trace.endSection()
                    }
                }?.also {
                    LibraryIconCache.put(cacheKey, it)
                }
            } ?: return@produceState
            value = normalized
        }.value
    }
}

private fun libraryIconCacheKey(
    iconPath: String?,
    iconRevision: Long,
    targetSizePx: Int,
    enhancedIcons: Boolean,
    preserveArtwork: Boolean,
): String {
    return "real:$LIBRARY_ICON_PRESENTATION_VERSION:${iconPath.orEmpty()}:$iconRevision:$targetSizePx:$enhancedIcons:$preserveArtwork"
}

@Composable
internal fun LibraryIconSlot(
    app: LibraryAppUiItem,
    modifier: Modifier,
    contentSize: Dp?,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape = LibraryIconShape.Round,
    enhancedIcons: Boolean = true,
    maxArtworkSize: Dp = LibraryGridMaxArtworkSize,
) {
    val slotModifier = modifier.aspectRatio(iconRatio.widthToHeight)
    if (contentSize != null) {
        Box(modifier = slotModifier) {
            LibraryIconSlotContent(app, contentSize, iconRatio, iconShape, enhancedIcons)
        }
    } else {
        BoxWithConstraints(modifier = slotModifier) {
            val artworkSize = minOf(
                maxWidth * LIBRARY_GRID_ARTWORK_FRACTION,
                maxArtworkSize,
            )
            LibraryIconSlotContent(app, artworkSize, iconRatio, iconShape, enhancedIcons)
        }
    }
}

@Composable
private fun LibraryIconSlotContent(
    app: LibraryAppUiItem,
    artworkSize: Dp,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean,
) {
    val portraitArtwork = iconRatio == LibraryIconRatio.Portrait &&
        !app.portraitIconPath.isNullOrBlank()
    val displayApp = if (portraitArtwork) app.copy(iconPath = app.portraitIconPath) else app
    val icon = if (displayApp.iconPath.isNullOrBlank()) {
        null
    } else {
        rememberLibraryIcon(displayApp, artworkSize, enhancedIcons, preserveArtwork = portraitArtwork)
    }
    val isFallback = icon == null ||
        icon.presentationMode == LibraryIconPresentationMode.Fallback
    val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val containerColor = when {
        isFallback -> colorResource(R.color.library_default_icon_background)
        else -> icon.tileColor ?: baseContainerColor
    }

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = if (iconShape == LibraryIconShape.Round) {
            MaterialTheme.shapes.medium
        } else {
            RoundedCornerShape(0.dp)
        },
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (isFallback) {
                LibraryFallbackIconArtwork(iconRatio)
            } else {
                LibraryIconArtwork(
                    icon = icon,
                    contentSize = artworkSize,
                    slotAspectRatio = iconRatio.widthToHeight,
                )
            }
        }
    }
}

@Composable
private fun LibraryFallbackIconArtwork(iconRatio: LibraryIconRatio) {
    Icon(
        painter = painterResource(R.drawable.ic_default_midlet),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(
            if (iconRatio == LibraryIconRatio.Square) {
                LIBRARY_FALLBACK_VECTOR_SCALE
            } else {
                LIBRARY_FALLBACK_VECTOR_PORTRAIT_SCALE
            },
        ),
        tint = colorResource(R.color.library_default_icon),
    )
}

private const val LIBRARY_FALLBACK_VECTOR_SCALE = 0.70f
private const val LIBRARY_FALLBACK_VECTOR_PORTRAIT_SCALE = 0.62f

@Composable
private fun LibraryIconArtwork(
    icon: LibraryNormalizedIcon?,
    contentSize: Dp,
    slotAspectRatio: Float,
) {
    if (icon == null) return

    val artworkModifier = when {
        icon.presentationMode == LibraryIconPresentationMode.Subject ||
            icon.presentationMode == LibraryIconPresentationMode.Backed ->
            Modifier.fillMaxSize(icon.visualScale)
        icon.presentationMode == LibraryIconPresentationMode.Fallback ->
            Modifier
                .size(contentSize * icon.visualScale)
                .clip(MaterialTheme.shapes.small)
        else -> Modifier.fillMaxSize()
    }
    val contentScale = if (
        icon.presentationMode == LibraryIconPresentationMode.Cover &&
        shouldCropLibraryCover(icon.bitmap.width, icon.bitmap.height, slotAspectRatio)
    ) {
        ContentScale.Crop
    } else {
        ContentScale.Fit
    }
    Image(
        bitmap = icon.bitmap,
        contentDescription = null,
        modifier = artworkModifier,
        contentScale = contentScale,
        filterQuality = icon.filterQuality,
    )
}
