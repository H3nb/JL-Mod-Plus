// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import javax.microedition.media.MediaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraCaptureOptionsTest {
    @Test fun configuredResolutionOverridesScreenOnlyForDefaultRequests() {
        for (portraitScreen in listOf(false, true)) {
            for ((setting, expected) in listOf(
                CameraCaptureOptions.RESOLUTION_PORTRAIT to (480 to 640),
                CameraCaptureOptions.RESOLUTION_LANDSCAPE to (640 to 480),
            )) {
                val size = CameraCaptureOptions.configuredDimensions(setting, portraitScreen)
                assertEquals(expected, size.width to size.height)
                val snapshot = CameraCaptureOptions.parseSnapshot(null, size)
                assertEquals(expected, snapshot.width to snapshot.height)
                val explicit = CameraCaptureOptions.parseSnapshot("encoding=png&width=320&height=320", size)
                assertEquals(320 to 320, explicit.width to explicit.height)
            }
            assertEquals(CameraCaptureOptions.defaultsForOrientation(portraitScreen),
                CameraCaptureOptions.configuredDimensions(99, portraitScreen))
        }
    }

    @Test fun preferredLensFallsBackButExplicitDevicesAndSwapsRemainStrict() {
        for (preferred in listOf(CameraCaptureOptions.BACK, CameraCaptureOptions.FRONT)) {
            assertEquals(preferred, CameraCaptureOptions.selectFacing(null, preferred, true, true))
            assertEquals(CameraCaptureOptions.BACK, CameraCaptureOptions.selectFacing(null, preferred, true, false))
            assertEquals(CameraCaptureOptions.FRONT, CameraCaptureOptions.selectFacing(null, preferred, false, true))
            assertThrows(MediaException::class.java) {
                CameraCaptureOptions.selectFacing(null, preferred, false, false)
            }
            for (explicit in listOf(CameraCaptureOptions.BACK, CameraCaptureOptions.FRONT)) {
                assertEquals(explicit, CameraCaptureOptions.selectFacing(explicit, preferred, true, true))
                assertThrows(MediaException::class.java) {
                    CameraCaptureOptions.selectFacing(explicit, preferred,
                        explicit != CameraCaptureOptions.BACK, explicit != CameraCaptureOptions.FRONT)
                }
            }
        }
    }

    @Test fun virtualScreenOrientationSelectsOnlyOmittedDimensions() {
        for ((screen, expected) in listOf(
            (240 to 320) to (480 to 640),
            (320 to 240) to (640 to 480),
            (240 to 240) to (640 to 480),
        )) {
            val defaults = CameraCaptureOptions.defaultsForScreen(screen.first, screen.second)
            val source = CameraCaptureOptions.parseLocator("capture://video", defaults)
            assertEquals(expected, source.width to source.height)
            for (request in listOf(null, "encoding=png", "encoding=jpeg&quality=80")) {
                val snapshot = CameraCaptureOptions.parseSnapshot(request, defaults)
                assertEquals(expected, snapshot.width to snapshot.height)
            }
            val explicit = CameraCaptureOptions.parseSnapshot("encoding=png&width=320&height=240", defaults)
            assertEquals(320 to 240, explicit.width to explicit.height)
            val explicitSource = CameraCaptureOptions.parseLocator("capture://video?width=240&height=320", defaults)
            assertEquals(240 to 320, explicitSource.width to explicitSource.height)
        }
    }

    @Test fun defaultSourceAndExplicitDeviceSelectionAreIndependentOfAndroidIds() {
        val source = CameraCaptureOptions.parseLocator("capture://video")
        assertNull(source.facing)
        assertEquals(640, source.width)
        assertEquals(480, source.height)
        assertEquals(CameraCaptureOptions.BACK, CameraCaptureOptions.parseLocator("capture://devcam0").facing)
        assertEquals(CameraCaptureOptions.FRONT, CameraCaptureOptions.parseLocator("capture://devcam1").facing)
    }

    @Test fun legacyStillCaptureLocatorUsesTheExistingCameraSource() {
        // Nokia-era MIDlets use capture://image for getSnapshot, not video recording.
        // This is an explicit compatibility alias, not a new capture backend.
        assertEquals(
            CameraCaptureOptions.parseLocator("capture://video"),
            CameraCaptureOptions.parseLocator("capture://image"),
        )
        assertThrows(MediaException::class.java) {
            CameraCaptureOptions.parseLocator("capture://imagegarbage")
        }
    }

    @Test fun rawSourceParametersDoNotSelectSnapshotEncoding() {
        assertEquals(
            CameraCaptureOptions.Source(CameraCaptureOptions.FRONT, 320, 240),
            CameraCaptureOptions.parseLocator("capture://devcam1?encoding=rgb888&width=320&height=240"),
        )
        assertEquals(640, CameraCaptureOptions.parseLocator("capture://video?encoding=video/raw").width)
        assertThrows(MediaException::class.java) {
            CameraCaptureOptions.parseLocator("capture://video?encoding=jpeg")
        }
    }

    @Test fun nullSnapshotDefaultsAndAdvertisedFormatsHaveExactDimensions() {
        assertEquals(
            CameraCaptureOptions.Snapshot(CameraCaptureOptions.Format.JPEG, 640, 480, 90),
            CameraCaptureOptions.parseSnapshot(null),
        )
        assertEquals(
            CameraCaptureOptions.Snapshot(CameraCaptureOptions.Format.PNG, 176, 144, 90),
            CameraCaptureOptions.parseSnapshot("encoding=png&width=176&height=144"),
        )
        assertEquals(100, CameraCaptureOptions.parseSnapshot("encoding=image/jpeg&quality=100").quality)
        assertEquals(CameraCaptureOptions.Format.PNG, CameraCaptureOptions.parseSnapshot("encoding=image/png").format)
    }

    @Test fun limitsAllowPortraitLandscapeAndArbitraryAspectWithoutLargeAllocations() {
        for ((width, height) in listOf(1280 to 960, 960 to 1280, 320 to 320, 176 to 144)) {
            val result = CameraCaptureOptions.parseSnapshot("encoding=jpeg&width=$width&height=$height")
            assertEquals(width, result.width)
            assertEquals(height, result.height)
        }
        for (size in listOf("1280&height=1280", "1281&height=1", "2147483647&height=2147483647")) {
            assertThrows(MediaException::class.java) {
                CameraCaptureOptions.parseSnapshot("encoding=jpeg&width=$size")
            }
        }
    }

    @Test fun malformedUnsupportedOrAmbiguousParametersFailInsteadOfSilentlyChangingOutput() {
        val requests = listOf(
            "", "jpeg", "encoding=gif", "encoding=png&quality=50", "quality=0", "quality=101",
            "width=0&height=240", "width=-1&height=240", "width=+1&height=240",
            "width=2.5&height=240", "width=320", "height=240", "encoding=jpeg&encoding=png",
            "width=320&height=240&crop=true", "encoding=jpeg&", "encoding=jpeg=png",
        )
        for (request in requests) {
            assertThrows("request=$request", MediaException::class.java) {
                CameraCaptureOptions.parseSnapshot(request)
            }
        }
        for (locator in listOf("capture://devcam2", "capture://videogarbage", "capture://video?camera=front", "capture://video?")) {
            assertThrows("locator=$locator", MediaException::class.java) {
                CameraCaptureOptions.parseLocator(locator)
            }
        }
    }
}
