// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import io.github.h3nb.jlmodplus.config.ProfileConfigMatcher
import io.github.h3nb.jlmodplus.config.ProfileModel
import io.github.h3nb.jlmodplus.config.SystemPropertyLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilitiesTest {
    private val factory = """
        microedition.platform: Nokia 6233
        supports.video.capture: true
        supports.audio.capture: true
        supports.recording: false
    """.trimIndent()

    @Test fun factoryTrueAdvertisesSupportedCaptureFormatsWhenCameraExists() {
        val size = CameraCaptureOptions.defaultsForScreen(240, 320)
        val resolved = CameraCapabilities.resolve(factory, true, size)
        assertEquals("true", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        val source = CameraCaptureOptions.parseLocator(
            "capture://video?" + resolved[CameraCapabilities.VIDEO_ENCODINGS], size
        )
        assertEquals(480 to 640, source.width to source.height)
        val snapshotEncoding = resolved[CameraCapabilities.SNAPSHOT_ENCODINGS]!!.substringBefore(' ')
        assertEquals(
            CameraCaptureOptions.parseSnapshot(null, size),
            CameraCaptureOptions.parseSnapshot(snapshotEncoding, size)
        )
        for (encoding in resolved[CameraCapabilities.SNAPSHOT_ENCODINGS]!!.split(' ')) {
            CameraCaptureOptions.parseSnapshot(encoding)
        }
    }

    @Test fun explicitFormatsTakePrecedenceWhenCaptureIsEnabled() {
        val custom = factory +
            "\nvideo.encodings: encoding=rgb888&width=320&height=240" +
            "\nvideo.snapshot.encodings: encoding=png&width=320&height=240"
        val resolved = CameraCapabilities.resolve(custom, true)
        assertEquals(
            "encoding=rgb888&width=320&height=240",
            resolved[CameraCapabilities.VIDEO_ENCODINGS]
        )
        assertEquals(
            "encoding=png&width=320&height=240",
            resolved[CameraCapabilities.SNAPSHOT_ENCODINGS]
        )
        assertEquals(
            320 to 240,
            CameraCaptureOptions.parseSnapshot(resolved[CameraCapabilities.SNAPSHOT_ENCODINGS]).let {
                it.width to it.height
            }
        )
    }

    @Test fun oldFalseRemainsFalseWithoutGuessingItsOrigin() {
        val legacy = """
            microedition.configuration: CDLC-1.1
            microedition.platform: Nokia 6233
            supports.video.capture: false
        """.trimIndent()
        val resolved = CameraCapabilities.resolve(legacy, true)
        assertEquals("false", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        assertNull(resolved[CameraCapabilities.VIDEO_ENCODINGS])
        assertNull(resolved[CameraCapabilities.SNAPSHOT_ENCODINGS])
    }

    @Test fun explicitFalseDisablesCaptureEvenWithExplicitEncodings() {
        val custom = factory.replace("supports.video.capture: true", "supports.video.capture: false") +
            "\nvideo.encodings: encoding=rgb888&width=320&height=240" +
            "\nvideo.snapshot.encodings: encoding=png"
        val resolved = CameraCapabilities.resolve(custom, true)
        assertEquals("false", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        assertNull(resolved[CameraCapabilities.VIDEO_ENCODINGS])
        assertNull(resolved[CameraCapabilities.SNAPSHOT_ENCODINGS])
    }

    @Test fun falseDoesNotDependOnUnrelatedPropertiesOrLineOrder() {
        val custom = (factory.replace("supports.video.capture: true", "supports.video.capture: false") +
            "\ncustom.flag: 1").lineSequence().toList().reversed().joinToString("\r\n")
        assertEquals("false", CameraCapabilities.resolve(custom, true)[CameraCapabilities.SUPPORTS_CAPTURE])
    }

    @Test fun absentCameraKeyUsesHardwareAvailabilityWithoutChangingProfile() {
        val custom = "microedition.platform: Custom device\nsupports.recording: false"
        val supported = CameraCapabilities.resolve(custom, true)
        assertEquals("true", supported[CameraCapabilities.SUPPORTS_CAPTURE])
        assertEquals(
            "encoding=jpeg&width=640&height=480 encoding=png&width=640&height=480",
            supported[CameraCapabilities.SNAPSHOT_ENCODINGS]
        )
        val unsupported = CameraCapabilities.resolve(custom, false)
        assertEquals("false", unsupported[CameraCapabilities.SUPPORTS_CAPTURE])
        assertNull(unsupported[CameraCapabilities.VIDEO_ENCODINGS])
    }

    @Test fun unavailableHardwareDisablesDeclaredTrue() {
        val resolved = CameraCapabilities.resolve(factory, false)
        assertEquals("false", resolved[CameraCapabilities.SUPPORTS_CAPTURE])
        assertNull(resolved[CameraCapabilities.VIDEO_ENCODINGS])
        assertNull(resolved[CameraCapabilities.SNAPSHOT_ENCODINGS])
    }

    @Test fun resolutionDoesNotChangeLinkedProfileIdentity() {
        val configured = factory.replace("supports.video.capture: true", "supports.video.capture: false")
        val baseline = ProfileModel.fresh(configured)
        val linked = ProfileConfigMatcher.copyConfig(baseline)
        val before = ProfileConfigMatcher.comparisonSnapshot(linked)

        assertEquals("false", CameraCapabilities.resolve(linked.systemProperties!!, true)[CameraCapabilities.SUPPORTS_CAPTURE])
        assertEquals("false", CameraCapabilities.resolve(linked.systemProperties!!, false)[CameraCapabilities.SUPPORTS_CAPTURE])
        assertEquals(SystemPropertyLayers.effective(emptyMap(), configured), linked.systemProperties)
        assertEquals(before, ProfileConfigMatcher.comparisonSnapshot(linked))
        assertTrue(ProfileConfigMatcher.sameConfig(baseline, linked))
    }
}
