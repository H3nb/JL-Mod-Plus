// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

/** Derives guest camera discovery without rewriting profiles or their preset linkage. */
object CameraCapabilities {
    const val SUPPORTS_CAPTURE = "supports.video.capture"
    const val VIDEO_ENCODINGS = "video.encodings"
    const val SNAPSHOT_ENCODINGS = "video.snapshot.encodings"
    private val managedKeys = setOf(SUPPORTS_CAPTURE, VIDEO_ENCODINGS, SNAPSHOT_ENCODINGS)

    /**
     * A complete legacy factory template inherited its old false capability. A
     * customized legacy template cannot reveal whether false was intentional, so
     * preserve it. Explicit encoding strings always retain their final precedence.
     * Null values remove unsupported automatic properties left by a prior runtime.
     */
    @JvmStatic
    fun resolve(properties: String, defaultProperties: String, available: Boolean): Map<String, String?> {
        val profile = parse(properties)
        val defaults = parse(defaultProperties)
        val inheritedLegacyFalse = profile[SUPPORTS_CAPTURE] == "false" &&
            profile.filterKeys { it !in managedKeys } == defaults.filterKeys { it !in managedKeys }
        val requestedSupport = profile[SUPPORTS_CAPTURE].takeUnless { inheritedLegacyFalse }
        val support = explicit(requestedSupport) ?: available.toString()
        val automaticCapture = available && support == "true"
        return linkedMapOf(
            SUPPORTS_CAPTURE to support,
            VIDEO_ENCODINGS to (explicit(profile[VIDEO_ENCODINGS]) ?: if (automaticCapture) {
                "encoding=rgb888&width=640&height=480"
            } else null),
            SNAPSHOT_ENCODINGS to (explicit(profile[SNAPSHOT_ENCODINGS]) ?: if (automaticCapture) {
                "encoding=jpeg&width=640&height=480 encoding=png&width=640&height=480"
            } else null),
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
