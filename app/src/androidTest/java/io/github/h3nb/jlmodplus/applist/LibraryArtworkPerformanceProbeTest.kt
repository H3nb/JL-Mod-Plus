/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import android.graphics.Bitmap
import android.os.Debug
import android.os.Trace
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in CPU characterization and exact artwork evidence; timings never gate CI. */
@RunWith(AndroidJUnit4::class)
class LibraryArtworkPerformanceProbeTest {
    @Test
    fun characterizeArtworkCpuAndOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Only the isolated performance installation selects this probe",
            InstrumentationRegistry.getArguments().getString("libraryPerformanceProbe") == "true")
        assertEquals("io.github.h3nb.jlmodplus.perf158.debug", context.packageName)

        // A characterization boundary avoids a production API solely for benchmark access.
        val normalize = Class.forName("io.github.h3nb.jlmodplus.applist.LibraryComposeBridgeKt")
            .getDeclaredMethod("normalizeLibraryIcon", Bitmap::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val results = JSONArray()
        for (size in intArrayOf(48, 256)) {
            for (kind in arrayOf("transparent-pixel-art", "framed", "checkerboard", "gradient")) {
                val template = artwork(kind, size)
                try {
                    var expectedOutput: String? = null
                    repeat(100) { index ->
                        val source = template.copy(Bitmap.Config.ARGB_8888, true)
                        val normalized = normalize.invoke(null, source, true)
                        try {
                            assertNotNull(normalized)
                            if (index == 0) expectedOutput = outputEvidence(normalized!!).toString()
                        } finally {
                            recycleArtwork(normalized, source)
                        }
                    }
                    val cpuTimes = JSONArray()
                    repeat(20) {
                        // Creation, hashing, reflection of properties and assertions are outside
                        // the measured normalization interval, identical on baseline/candidate.
                        val source = template.copy(Bitmap.Config.ARGB_8888, true)
                        Trace.beginSection("LibraryProbe/artwork/$kind/$size")
                        val start = Debug.threadCpuTimeNanos()
                        val normalized: Any?
                        val cpuNs: Long
                        try {
                            normalized = normalize.invoke(null, source, true)
                            cpuNs = Debug.threadCpuTimeNanos() - start
                        } finally {
                            Trace.endSection()
                        }
                        try {
                            assertNotNull(normalized)
                            assertTrue(cpuNs >= 0L)
                            assertEquals("Deterministic output for $kind/$size", expectedOutput,
                                outputEvidence(normalized!!).toString())
                            cpuTimes.put(cpuNs / 1_000_000.0)
                        } finally {
                            recycleArtwork(normalized, source)
                        }
                    }
                    results.put(JSONObject().put("kind", kind).put("sourceWidth", size)
                        .put("sourceHeight", size).put("threadCpuMs", cpuTimes)
                        .put("output", JSONObject(checkNotNull(expectedOutput))))
                } finally {
                    template.recycle()
                }
            }
        }
        File(context.filesDir, "library-artwork-cpu.json").writeText(
            JSONObject().put("schemaVersion", 1).put("warmupCalls", 100)
                .put("sampleCalls", 20).put("enhancedIcons", true).put("cases", results).toString(2),
        )
    }

    private fun artwork(kind: String, size: Int): Bitmap {
        val pixels = IntArray(size * size)
        val inset = size / 4
        val block = (size / 8).coerceAtLeast(1)
        for (y in 0 until size) for (x in 0 until size) {
            val subject = x in inset until size - inset && y in inset until size - inset
            pixels[y * size + x] = when (kind) {
                "transparent-pixel-art" -> if (!subject) 0 else {
                    if ((x / block + y / block) % 2 == 0) 0xff2266cc.toInt() else 0xffeeaa44.toInt()
                }
                "framed" -> if (!subject) 0xfffafafa.toInt() else {
                    if ((x / block + y / block) % 2 == 0) 0xff225577.toInt() else 0xffbb4422.toInt()
                }
                "checkerboard" -> if ((x / block + y / block) % 2 == 0) {
                    0xff224466.toInt()
                } else {
                    0xffcc8844.toInt()
                }
                "gradient" -> (0xff shl 24) or ((x * 255 / (size - 1)) shl 16) or
                    ((y * 255 / (size - 1)) shl 8) or ((x + y) * 255 / (2 * (size - 1)))
                else -> error("Unknown artwork $kind")
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun outputEvidence(normalized: Any): JSONObject {
        val result = JSONObject()
        // Include every stored presentation property, even if the data class gains a field.
        for (field in normalized.javaClass.declaredFields.sortedBy { it.name }) {
            if (field.isSynthetic || java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val value = field.get(normalized)
            result.put(field.name, when (value) {
                null -> JSONObject.NULL
                is ImageBitmap -> bitmapEvidence(value.asAndroidBitmap())
                is Color -> value.value.toString()
                is Float -> JSONObject().put("value", value).put("rawBits", value.toRawBits())
                is Double -> JSONObject().put("value", value).put("rawBits", value.toRawBits().toString())
                is Number, is Boolean, is String -> value
                is Enum<*> -> value.name
                else -> value.toString()
            })
        }
        assertTrue("Normalized result must contain bitmap evidence", result.has("bitmap"))
        return result
    }

    private fun bitmapEvidence(bitmap: Bitmap): JSONObject {
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        val bytes = ByteArray(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) {
                val pixel = row[x]
                for (channel in 0 until 4) bytes[x * 4 + channel] =
                    (pixel ushr (24 - channel * 8)).toByte()
            }
            digest.update(bytes)
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject().put("width", bitmap.width).put("height", bitmap.height)
            .put("pixelEncoding", "ARGB big-endian rows").put("pixelSha256", hex)
    }

    private fun recycleArtwork(normalized: Any?, source: Bitmap) {
        if (normalized != null) {
            val bitmapField = normalized.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }
            val bitmap = (bitmapField.get(normalized) as ImageBitmap).asAndroidBitmap()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        if (!source.isRecycled) source.recycle()
    }
}
