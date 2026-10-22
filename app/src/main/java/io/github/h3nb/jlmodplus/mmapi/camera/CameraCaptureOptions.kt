// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import java.util.Locale
import javax.microedition.media.MediaException

/** The supported MMAPI capture subset. Device numbers belong to the emulated device. */
object CameraCaptureOptions {
    const val FRONT = 0
    const val BACK = 1
    const val DEFAULT_WIDTH = 640
    const val DEFAULT_HEIGHT = 480
    const val MAX_PIXELS = 1_228_800
    const val RESOLUTION_AUTO = 0
    const val RESOLUTION_PORTRAIT = 1
    const val RESOLUTION_LANDSCAPE = 2

    @JvmStatic fun normalizeFacing(value: Int): Int = if (value == FRONT) FRONT else BACK
    @JvmStatic fun normalizeResolution(value: Int): Int = when (value) {
        RESOLUTION_PORTRAIT, RESOLUTION_LANDSCAPE -> value
        else -> RESOLUTION_AUTO
    }

    data class Dimensions(val width: Int = DEFAULT_WIDTH, val height: Int = DEFAULT_HEIGHT)

    /** One launch-time choice from the full virtual screen, before UI insets. */
    @JvmStatic
    fun defaultsForScreen(width: Int, height: Int): Dimensions =
        defaultsForOrientation(height > width)

    @JvmStatic
    fun defaultsForOrientation(portrait: Boolean): Dimensions =
        if (portrait) Dimensions(DEFAULT_HEIGHT, DEFAULT_WIDTH) else Dimensions()

    @JvmStatic
    fun configuredDimensions(resolution: Int, screenPortrait: Boolean): Dimensions =
        defaultsForOrientation(when (normalizeResolution(resolution)) {
            RESOLUTION_PORTRAIT -> true
            RESOLUTION_LANDSCAPE -> false
            else -> screenPortrait
        })

    /** Named devices and session swaps are strict; a configured default may fall back. */
    @JvmStatic
    @Throws(MediaException::class)
    fun selectFacing(requested: Int?, preferred: Int, rearAvailable: Boolean, frontAvailable: Boolean): Int {
        fun available(facing: Int) = when (facing) {
            BACK -> rearAvailable
            FRONT -> frontAvailable
            else -> false
        }
        if (requested != null) {
            if (!available(requested)) throw MediaException("Requested camera is unavailable")
            return requested
        }
        val selected = normalizeFacing(preferred)
        if (available(selected)) return selected
        val other = if (selected == BACK) FRONT else BACK
        if (available(other)) return other
        throw MediaException("No camera is available")
    }

    data class Source(val facing: Int?, val width: Int, val height: Int, val microphone: Boolean = false)

    enum class Format { JPEG, PNG }

    data class Snapshot(val format: Format, val width: Int, val height: Int, val quality: Int)

    /** Logical source encoding is raw; snapshot encoding is selected independently. */
    @JvmStatic
    @JvmOverloads
    @Throws(MediaException::class)
    fun parseLocator(locator: String, defaults: Dimensions = Dimensions()): Source {
        val separator = locator.indexOf('?')
        val device = if (separator < 0) locator else locator.substring(0, separator)
        val facing = when (device) {
            "capture://video", "capture://audio_video", "capture://image" -> null
            "capture://devcam0" -> BACK
            "capture://devcam1" -> FRONT
            else -> throw MediaException("Unsupported camera locator: $device")
        }
        val parameters = if (separator < 0) emptyMap() else parseParameters(locator.substring(separator + 1))
        requireKeys(parameters, setOf("encoding", "width", "height"))
        val encoding = parameters["encoding"]?.lowercase(Locale.ROOT)
        if (encoding != null && encoding != "rgb888" && encoding != "video/raw") {
            throw MediaException("Unsupported camera source encoding: $encoding")
        }
        val (width, height) = dimensions(parameters, defaults)
        return Source(facing, width, height, device == "capture://audio_video")
    }

    /**
     * Returned dimensions are exact. The image pipeline fits the shared framing into
     * this rectangle with padding; changing output aspect ratio must never add crop.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(MediaException::class)
    fun parseSnapshot(type: String?, defaults: Dimensions = Dimensions()): Snapshot {
        val parameters = if (type == null) emptyMap() else parseParameters(type)
        requireKeys(parameters, setOf("encoding", "width", "height", "quality"))
        val format = when (val encoding = parameters["encoding"]?.lowercase(Locale.ROOT)) {
            null, "jpeg", "image/jpeg" -> Format.JPEG
            "png", "image/png" -> Format.PNG
            else -> throw MediaException("Unsupported snapshot encoding: $encoding")
        }
        if (format == Format.PNG && parameters.containsKey("quality")) {
            throw MediaException("PNG snapshot quality is unsupported")
        }
        val quality = parameters["quality"]?.let { positiveInteger(it, "quality") } ?: 90
        if (quality > 100) throw MediaException("Snapshot quality must be between 1 and 100")
        val (width, height) = dimensions(parameters, defaults)
        return Snapshot(format, width, height, quality)
    }

    private fun parseParameters(value: String): Map<String, String> {
        if (value.isEmpty()) throw MediaException("Empty camera encoding parameters")
        val parameters = LinkedHashMap<String, String>()
        for (parameter in value.split('&')) {
            val equals = parameter.indexOf('=')
            if (equals <= 0 || equals == parameter.lastIndex || parameter.indexOf('=', equals + 1) >= 0) {
                throw MediaException("Malformed camera encoding parameter")
            }
            val key = parameter.substring(0, equals)
            val entry = parameter.substring(equals + 1)
            if (parameters.put(key, entry) != null) throw MediaException("Duplicate camera parameter: $key")
        }
        return parameters
    }

    private fun requireKeys(parameters: Map<String, String>, supported: Set<String>) {
        for (key in parameters.keys) {
            if (key !in supported) throw MediaException("Unsupported camera parameter: $key")
        }
    }

    private fun dimensions(parameters: Map<String, String>, defaults: Dimensions): Pair<Int, Int> {
        val widthValue = parameters["width"]
        val heightValue = parameters["height"]
        // The supported subset accepts either a complete requested size or defaults.
        if ((widthValue == null) != (heightValue == null)) {
            throw MediaException("Camera width and height must be specified together")
        }
        val width = widthValue?.let { positiveInteger(it, "width") } ?: defaults.width
        val height = heightValue?.let { positiveInteger(it, "height") } ?: defaults.height
        if (width > 1280 || height > 1280 || width.toLong() * height > MAX_PIXELS) {
            throw MediaException("Camera dimensions exceed the supported snapshot limit")
        }
        return width to height
    }

    private fun positiveInteger(value: String, name: String): Int {
        if (value.any { it !in '0'..'9' }) throw MediaException("Invalid camera $name")
        return value.toIntOrNull()?.takeIf { it > 0 }
            ?: throw MediaException("Invalid camera $name")
    }
}
