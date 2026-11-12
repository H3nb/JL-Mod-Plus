/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.os.Debug
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.security.MessageDigest
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Experiment-only probe. Reflection lets the *same* source run against both the original
 * private normalizer and a refactored internal one without changing baseline production code.
 * It measures normalization CPU time, not decode, Compose drawing, or whole-app frame timing.
 */
@RunWith(AndroidJUnit4::class)
class LibraryIconBenchmarkProbeTest {
    private data class Fixture(
        val id: String,
        val width: Int,
        val height: Int,
        val enhance: Boolean = true,
        val portrait: Boolean = false,
        val slotAspectRatio: Float = 1f,
        val colorAt: (Int, Int) -> Int,
    ) {
        fun pixels() = IntArray(width * height) { i -> colorAt(i % width, i / width) }
    }

    private class Normalizer {
        private val owner = Class.forName("io.github.h3nb.jlmodplus.applist.LibraryComposeBridgeKt")
        private val invoke: Method = owner.declaredMethods.single {
            it.name.startsWith("normalizeLibraryIcon") &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Bitmap::class.java &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                it.parameterTypes[2] == java.lang.Boolean.TYPE
        }.apply { isAccessible = true }
        private val model = Class.forName("io.github.h3nb.jlmodplus.applist.LibraryNormalizedIcon")
        private val bitmap = field("bitmap")
        private val mode = field("presentationMode")
        private val scale = field("visualScale")
        private val filter = field("filterQuality")
        private val tile = field("tileColor")
        // Alpha does not have this helper. On that exact baseline Cover always means Crop.
        private val cropPolicy: Method? =
            Class.forName("io.github.h3nb.jlmodplus.applist.LibraryIconPresentationKt")
                .declaredMethods.singleOrNull {
                    it.name == "shouldCropLibraryCover" && it.parameterTypes.size == 3
                }?.apply { isAccessible = true }

        private fun field(name: String): Field =
            model.getDeclaredField(name).apply { isAccessible = true }

        fun run(source: Bitmap, fixture: Fixture): Any =
            requireNotNull(invoke.invoke(null, source, fixture.enhance, fixture.portrait))

        fun output(result: Any): Bitmap =
            (bitmap.get(result) as ImageBitmap).asAndroidBitmap()

        fun tileBackgroundArgb(result: Any): Int =
            (tile.get(result) as? Color)?.toArgb() ?: AndroidColor.rgb(235, 235, 235)

        fun slotContentScale(result: Any, rendered: Bitmap, fixture: Fixture): String {
            if (mode.get(result).toString() != "Cover") return "Fit"
            val crop = cropPolicy?.invoke(
                null, rendered.width, rendered.height, fixture.slotAspectRatio
            ) as? Boolean ?: true
            return if (crop) "Crop" else "Fit"
        }

        fun properties(
            result: Any,
            rendered: Bitmap,
            slotContentScale: String,
            slotPreview: Bitmap,
        ): JSONObject {
            val tileValue = tile.get(result)
            val tileArgb: Any = when (tileValue) {
                null -> JSONObject.NULL
                is Color -> Integer.toHexString(tileValue.toArgb())
                else -> error("Unexpected Compose Color representation: " + tileValue.javaClass.name)
            }
            return JSONObject()
                .put("mode", mode.get(result).toString())
                .put("filter", filter.get(result).toString())
                .put("scale", (scale.get(result) as Number).toFloat().toDouble())
                .put("tileArgb", tileArgb)
                .put("slotContentScale", slotContentScale)
                .put("slotPreviewSha256", pixelHash(slotPreview))
                .put("width", rendered.width)
                .put("height", rendered.height)
                .put("argbSha256", pixelHash(rendered))
        }
    }

    @Test
    fun collectComparativeArtworkAndTiming() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = File(requireNotNull(context.getExternalFilesDir(null)), "icon-benchmark")
        if (outputDir.exists()) assertTrue("Cannot delete stale probe output", outputDir.deleteRecursively())
        assertTrue("Cannot create probe output", outputDir.mkdirs())
        val imageDir = File(outputDir, "images")
        assertTrue(imageDir.mkdirs())

        val normalizer = Normalizer()
        val cases = JSONArray()
        Debug.startAllocCounting()
        try {
            for (fixture in fixtures()) {
                val pixels = fixture.pixels()
                val source = Bitmap.createBitmap(pixels, fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
                savePng(source, File(imageDir, fixture.id + "-source.png"))
                val first = normalizer.run(source, fixture)
                val normalized = normalizer.output(first)
                val slotContentScale = normalizer.slotContentScale(first, normalized, fixture)
                val slot = renderSlotPreview(
                    normalized, fixture.slotAspectRatio, slotContentScale, normalizer.tileBackgroundArgb(first),
                )
                val visual = normalizer.properties(first, normalized, slotContentScale, slot)
                savePng(normalized, File(imageDir, fixture.id + "-normalized.png"))
                savePng(slot, File(imageDir, fixture.id + "-slot.png"))
                slot.recycle()
                release(source, normalized)

                repeat(WARMUPS) {
                    val input = Bitmap.createBitmap(pixels, fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
                    val item = normalizer.run(input, fixture)
                    release(input, normalizer.output(item))
                }

                // Explicit stabilization outside the measured interval, not per invocation.
                Runtime.getRuntime().gc()
                Thread.sleep(40)

                val cpu = JSONArray()
                val allocated = JSONArray()
                // Time only enhanced normalization; the disabled path is below clock resolution.
                val batchSize = if (!fixture.enhance) 0 else if (fixture.id == "fully-transparent") 128 else 8
                if (batchSize > 0) repeat(MEASUREMENTS) {
                    val inputs = Array(batchSize) {
                        Bitmap.createBitmap(pixels, fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
                    }
                    val outputs = arrayOfNulls<Any>(batchSize)
                    val allocationBefore = Debug.getThreadAllocSize()
                    val cpuBefore = Debug.threadCpuTimeNanos()
                    for (index in inputs.indices) {
                        outputs[index] = normalizer.run(inputs[index], fixture)
                    }
                    val elapsed = Debug.threadCpuTimeNanos() - cpuBefore
                    val allocationAfter = Debug.getThreadAllocSize()
                    require(elapsed > 0L) { "CPU clock did not advance over a batch for " + fixture.id }
                    cpu.put(elapsed / batchSize)
                    if (allocationBefore >= 0 && allocationAfter >= allocationBefore) {
                        allocated.put((allocationAfter - allocationBefore) / batchSize)
                    }
                    for (index in inputs.indices) {
                        release(inputs[index], normalizer.output(requireNotNull(outputs[index])))
                    }
                }

                cases.put(
                    JSONObject()
                        .put("id", fixture.id)
                        .put("inputWidth", fixture.width)
                        .put("inputHeight", fixture.height)
                        .put("enhance", fixture.enhance)
                        .put("preserveArtwork", fixture.portrait)
                        .put("slotAspectRatio", fixture.slotAspectRatio.toDouble())
                        .put("batchSize", batchSize)
                        .put("visual", visual)
                        .put("cpuNs", cpu)
                        .put("allocatedBytes", allocated)
                )
            }
        } finally {
            Debug.stopAllocCounting()
        }
        File(outputDir, "metrics.json").writeText(
            JSONObject().put("schema", 1).put("warmups", WARMUPS)
                .put("measurements", MEASUREMENTS).put("cases", cases).toString(2)
        )
    }

    private fun renderSlotPreview(
        image: Bitmap,
        slotAspectRatio: Float,
        contentScale: String,
        backgroundArgb: Int,
    ): Bitmap {
        // A deterministic representation of Fit/Crop geometry; the live Compose
        // Card shape, background and GPU resampling still need device visual review.
        val width = 128
        val height = (width / slotAspectRatio).roundToInt().coerceAtLeast(1)
        val preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        canvas.drawColor(backgroundArgb)
        val xRatio = width.toFloat() / image.width
        val yRatio = height.toFloat() / image.height
        val scale = if (contentScale == "Crop") maxOf(xRatio, yRatio) else minOf(xRatio, yRatio)
        val drawnWidth = image.width * scale
        val drawnHeight = image.height * scale
        val target = RectF(
            (width - drawnWidth) / 2f,
            (height - drawnHeight) / 2f,
            (width + drawnWidth) / 2f,
            (height + drawnHeight) / 2f,
        )
        canvas.drawBitmap(image, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
        return preview
    }

    private fun release(input: Bitmap, result: Bitmap) {
        if (!result.isRecycled) result.recycle()
        if (input !== result && !input.isRecycled) input.recycle()
    }

    private fun savePng(source: Bitmap, destination: File) {
        FileOutputStream(destination).use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun fixtures(): List<Fixture> = listOf(
        Fixture("transparent-sprite-64", 64, 64) { x, y ->
            if (x in 21..42 && y in 20..43) AndroidColor.RED else AndroidColor.TRANSPARENT
        },
        Fixture("opaque-matte-96", 96, 96) { x, y ->
            if (x in 28..67 && y in 28..67) AndroidColor.RED else AndroidColor.BLUE
        },
        Fixture("colorful-cover-128", 128, 128) { x, y ->
            AndroidColor.rgb((x * 11 + y * 3) and 255, (x * 13 + y * 7) and 255, (x * 17 + y * 23) and 255)
        },
        Fixture("poster-cover-96x128", 96, 128) { x, y ->
            AndroidColor.rgb((x * 11 + y * 3) and 255, (x * 13 + y * 7) and 255, (x * 17 + y * 23) and 255)
        },
        Fixture("matching-corners-only-poster", 96, 128) { x, y ->
            val inCorner = (x < 12 || x >= 84) && (y < 12 || y >= 116)
            if (inCorner) {
                AndroidColor.rgb(12, 99, 172)
            } else {
                AndroidColor.rgb((x * 11 + y * 3) and 255, (x * 13 + y * 7) and 255, (x * 17 + y * 23) and 255)
            }
        },
        Fixture("uniform-border-cover-poster", 96, 128) { x, y ->
            val inBorder = x < 12 || x >= 84 || y == 0 || y == 127
            if (inBorder) {
                AndroidColor.rgb(12, 99, 172)
            } else {
                AndroidColor.rgb((x * 11 + y * 3) and 255, (x * 13 + y * 7) and 255, (x * 17 + y * 23) and 255)
            }
        },
        Fixture("square-cover-portrait-slot", 128, 128, slotAspectRatio = 0.75f) { x, y ->
            AndroidColor.rgb((x * 11 + y * 3) and 255, (x * 13 + y * 7) and 255, (x * 17 + y * 23) and 255)
        },
        Fixture("pixel-art-48-colors", 64, 64) { x, y ->
            val i = (y * 64 + x) % 48
            AndroidColor.rgb(((i shr 8) and 15) * 17, ((i shr 4) and 15) * 17, (i and 15) * 17)
        },
        Fixture("anti-aliased-128", 128, 128) { x, y ->
            val radius = kotlin.math.sqrt(((x - 64) * (x - 64) + (y - 64) * (y - 64)).toDouble())
            val alpha = ((45.0 - radius) * 255.0).toInt().coerceIn(0, 255)
            AndroidColor.argb(alpha, 241, 157, 36)
        },
        Fixture("sparse-horizontal-256", 256, 256) { x, y ->
            if (x in 17..236 && y in 110..125) AndroidColor.YELLOW else AndroidColor.TRANSPARENT
        },
        Fixture("gradient-photo-like-256", 256, 256) { x, y ->
            AndroidColor.rgb(x, y, ((x + y) / 2).coerceIn(0, 255))
        },
        Fixture("portrait-override-60x80", 60, 80, portrait = true) { x, y ->
            if (x in 10..49 && y in 10..69) AndroidColor.GREEN else AndroidColor.WHITE
        },
        Fixture("enhancement-disabled", 64, 64, enhance = false) { x, y ->
            if (x in 20..43 && y in 20..43) AndroidColor.CYAN else AndroidColor.TRANSPARENT
        },
        Fixture("fully-transparent", 64, 64) { _, _ -> AndroidColor.TRANSPARENT },
    )

    companion object {
        private const val WARMUPS = 12
        private const val MEASUREMENTS = 45

        private fun pixelHash(bitmap: Bitmap): String {
            val row = IntArray(bitmap.width)
            val digest = MessageDigest.getInstance("SHA-256")
            val argb = ByteArray(4)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (pixel in row) {
                    argb[0] = (pixel ushr 24).toByte()
                    argb[1] = (pixel ushr 16).toByte()
                    argb[2] = (pixel ushr 8).toByte()
                    argb[3] = pixel.toByte()
                    digest.update(argb)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
