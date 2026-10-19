// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

/** Derives guest camera discovery without rewriting profiles or their preset linkage. */
object CameraCapabilities {
    const val SUPPORTS_CAPTURE = "supports.video.capture"
    const val VIDEO_ENCODINGS = "video.encodings"
    const val SNAPSHOT_ENCODINGS = "video.snapshot.encodings"
    private val managedKeys = setOf(SUPPORTS_CAPTURE, VIDEO_ENCODINGS, SNAPSHOT_ENCODINGS)

    // Frozen pre-camera template: future defaults must not reinterpret legacy overrides.
    private val historicalFactory = parse("""
        microedition.configuration: CDLC-1.1
        microedition.profiles: MIDP-2.0
        microedition.encoding: ISO-8859-1
        microedition.platform: Nokia 6233
        microedition.io.file.FileConnection.version: 1.0
        microedition.sensor.version: 1
        microedition.m3g.version: 1.1
        microedition.media.version: 1.0
        microedition.pim.version: 1.0
        supports.mixing: true
        supports.audio.capture: true
        supports.recording: false
        com.siemens.mp.systemfolder.ringingtone: fs/MyStuff/Ringtones
        com.siemens.mp.systemfolder.pictures: fs/MyStuff/Pictures
        com.siemens.OSVersion: 11
        device.imei: 000000000000000
        com.siemens.IMEI: 000000000000000
        com.sonyericsson.imei: IMEI 00460101-501594-5-00
        com.nokia.mid.impl.isa.visual_radio_operator_id: 0
        com.nokia.mid.impl.isa.visual_radio_channel_freq: 0
        com.nokia.mid.ui.DirectGraphics.PIXEL_FORMAT: 565
    """.trimIndent())

    /**
     * A complete legacy factory template inherited its old false capability. A
     * customized legacy template cannot reveal whether false was intentional, so
     * preserve it. Explicit encoding strings always retain their final precedence.
     * Null values remove unsupported automatic properties left by a prior runtime.
     */
    @JvmStatic
    @JvmOverloads
    fun resolve(properties: String, available: Boolean,
                dimensions: CameraCaptureOptions.Dimensions = CameraCaptureOptions.Dimensions()): Map<String, String?> {
        val profile = parse(properties)
        val inheritedLegacyFalse = profile[SUPPORTS_CAPTURE] == "false" &&
            profile.filterKeys { it !in managedKeys } == historicalFactory
        val requestedSupport = profile[SUPPORTS_CAPTURE].takeUnless { inheritedLegacyFalse }
        val support = explicit(requestedSupport) ?: available.toString()
        val automaticCapture = available && support == "true"
        val size = "width=${dimensions.width}&height=${dimensions.height}"
        return linkedMapOf(
            SUPPORTS_CAPTURE to support,
            VIDEO_ENCODINGS to (explicit(profile[VIDEO_ENCODINGS]) ?: if (automaticCapture) {
                "encoding=rgb888&$size"
            } else null),
            SNAPSHOT_ENCODINGS to (explicit(profile[SNAPSHOT_ENCODINGS]) ?: if (automaticCapture) {
                "encoding=jpeg&$size encoding=png&$size"
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
