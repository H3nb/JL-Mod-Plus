// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

/** Derives guest camera discovery without rewriting profiles or their preset linkage. */
object CameraCapabilities {
    const val SUPPORTS_CAPTURE = "supports.video.capture"
    const val VIDEO_ENCODINGS = "video.encodings"
    const val SNAPSHOT_ENCODINGS = "video.snapshot.encodings"
    /**
     * The configured property is a JSR-135 boolean, not an "auto" sentinel.
     * An absent key preserves hardware-based discovery for older custom profiles.
     */
    @JvmStatic
    @JvmOverloads
    fun resolve(properties: String, available: Boolean,
                dimensions: CameraCaptureOptions.Dimensions = CameraCaptureOptions.Dimensions()): Map<String, String?> {
        val profile = parse(properties)
        val captureEnabled = available && profile[SUPPORTS_CAPTURE] != "false"
        val support = captureEnabled.toString()
        val size = "width=${dimensions.width}&height=${dimensions.height}"
        return linkedMapOf(
            SUPPORTS_CAPTURE to support,
            VIDEO_ENCODINGS to if (captureEnabled) {
                explicit(profile[VIDEO_ENCODINGS]) ?: "encoding=rgb888&$size"
            } else null,
            SNAPSHOT_ENCODINGS to if (captureEnabled) {
                explicit(profile[SNAPSHOT_ENCODINGS]) ?: "encoding=jpeg&$size encoding=png&$size"
            } else null,
        )
    }

    private fun explicit(value: String?): String? = value?.takeUnless { it == "auto" }

    private fun parse(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            result[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }
        return result
    }
}
