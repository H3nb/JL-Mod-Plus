// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import io.github.h3nb.jlmodplus.config.ProfileConfigMatcher
import io.github.h3nb.jlmodplus.config.ProfileModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilitiesTest {
    @Test fun portraitDiscoveryAndRequestsAgreeWithoutOverridingExplicitProfileSizes() {
        val size = CameraCaptureOptions.defaultsForScreen(240, 320)
        val resolved = CameraCapabilities.resolve(defaults, true, size)
        val source = CameraCaptureOptions.parseLocator("capture://video?" + resolved[CameraCapabilities.VIDEO_ENCODINGS], size)
        assertEquals(480 to 640, source.width to source.height)
        val first = resolved[CameraCapabilities.SNAPSHOT_ENCODINGS]!!.substringBefore(' ')
        assertEquals(CameraCaptureOptions.parseSnapshot(null, size), CameraCaptureOptions.parseSnapshot(first, size))
        val custom = defaults.replace("video.snapshot.encodings: auto", "video.snapshot.encodings: encoding=png&width=320&height=240")
        val advertised = CameraCapabilities.resolve(custom, true, size)[CameraCapabilities.SNAPSHOT_ENCODINGS]
        assertEquals("encoding=png&width=320&height=240", advertised)
        val snapshot = CameraCaptureOptions.parseSnapshot(advertised, size)
        assertEquals(320 to 240, snapshot.width to snapshot.height)
    }

    private val defaults = """
        microedition.platform: Nokia 6233
        supports.audio.capture: true
        supports.video.capture: auto
        supports.recording: false
        video.encodings: auto
        video.snapshot.encodings: auto
    """.trimIndent()

    private val legacy = """
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
        supports.video.capture: false
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
    """.trimIndent()

    @Test fun factoryLegacyFalseBecomesRuntimeCapabilityWithoutChangingProfileText() {
        val resolved = CameraCapabilities.resolve(legacy, true)
        assertEquals("true", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        assertTrue(resolved[CameraCapabilities.VIDEO_ENCODINGS]!!.contains("encoding=rgb888"))
        assertTrue(legacy.contains("supports.video.capture: false"))
        assertEquals(3, resolved.size)
    }

    @Test fun freshAutomaticCapabilitiesDescribeTheImplementedSnapshotSubset() {
        val resolved = CameraCapabilities.resolve(defaults, true)
        assertEquals("true", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        for (encoding in resolved[CameraCapabilities.SNAPSHOT_ENCODINGS]!!.split(' ')) {
            CameraCaptureOptions.parseSnapshot(encoding)
        }
        CameraCaptureOptions.parseLocator("capture://video?" + resolved[CameraCapabilities.VIDEO_ENCODINGS])
    }

    @Test fun customLegacyFalseIsPreservedBecauseItsProvenanceIsAmbiguous() {
        val custom = legacy.replace("Nokia 6233", "Custom device")
        val resolved = CameraCapabilities.resolve(custom, true)
        assertEquals("false", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        assertNull(resolved[CameraCapabilities.VIDEO_ENCODINGS])
        assertNull(resolved[CameraCapabilities.SNAPSHOT_ENCODINGS])
    }

    @Test fun explicitFalseInNewTemplateIsRespected() {
        val requestedFalse = defaults.replace("supports.video.capture: auto", "supports.video.capture: false")
        assertEquals("false", CameraCapabilities.resolve(requestedFalse, true)[CameraCapabilities.SUPPORTS_CAPTURE])
    }

    @Test fun absentCameraKeysUseAutomaticDefaultsEvenInCustomProfiles() {
        val custom = "microedition.platform: Custom device\nsupports.recording: false"
        val resolved = CameraCapabilities.resolve(custom, true)
        assertEquals("true", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        assertEquals(
            "encoding=jpeg&width=640&height=480 encoding=png&width=640&height=480",
            resolved[CameraCapabilities.SNAPSHOT_ENCODINGS],
        )
    }

    @Test fun missingHardwareRemovesAutomaticEncodingPropertiesAndReportsFalse() {
        val resolved = CameraCapabilities.resolve(defaults, false)
        assertEquals("false", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        assertNull(resolved[CameraCapabilities.VIDEO_ENCODINGS])
        assertNull(resolved[CameraCapabilities.SNAPSHOT_ENCODINGS])
    }

    @Test fun explicitEncodingOverridesRetainPrecedenceAndDefaultFormatOrder() {
        val custom = defaults.replace("video.encodings: auto", "video.encodings: encoding=rgb888&width=320&height=240")
            .replace("video.snapshot.encodings: auto", "video.snapshot.encodings: encoding=png encoding=jpeg")
        val resolved = CameraCapabilities.resolve(custom, true)
        assertEquals("encoding=rgb888&width=320&height=240", resolved[CameraCapabilities.VIDEO_ENCODINGS])
        assertEquals("encoding=png encoding=jpeg", resolved[CameraCapabilities.SNAPSHOT_ENCODINGS])
    }

    @Test fun templateRecognitionIgnoresLineOrderAndWhitespaceButNotNonCameraOverrides() {
        val reordered = legacy.lineSequence().toList().reversed().joinToString("\r\n")
            .replace("supports.video.capture: false", "supports.video.capture:  false  ")
        assertEquals("true", CameraCapabilities.resolve(legacy, true)[CameraCapabilities.SUPPORTS_CAPTURE])
        assertEquals("false", CameraCapabilities.resolve(legacy + "\ncustom.flag: 1", defaults, true)[CameraCapabilities.SUPPORTS_CAPTURE])
    }

    @Test fun factoryAndLinkedProfileIdentityStayUnchangedAfterRuntimeResolution() {
        val factory = legacy
        val baseline = ProfileModel.fresh(factory)
        val linked = ProfileConfigMatcher.copyConfig(baseline)
        val before = ProfileConfigMatcher.comparisonSnapshot(linked)

        assertEquals("true", CameraCapabilities.resolve(linked.systemProperties!!, factory, true)[CameraCapabilities.SUPPORTS_CAPTURE])
        assertEquals("false", CameraCapabilities.resolve(linked.systemProperties!!, factory, false)[CameraCapabilities.SUPPORTS_CAPTURE])

        assertEquals(factory, linked.systemProperties)
        assertEquals(before, ProfileConfigMatcher.comparisonSnapshot(linked))
        assertTrue(ProfileConfigMatcher.sameConfig(baseline, linked))
    }
}
