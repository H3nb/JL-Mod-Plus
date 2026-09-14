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

package io.github.h3nb.jlmodplus.input

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerConfigTest {
    @Test
    fun absentControllerUsesAnUnwrittenAnalogDefault() {
        val config = ControllerConfig.resolve(null)

        assertTrue(config.enabled)
        assertEquals(StickMode.DIRECTIONS, config.leftStick.mode)
        assertEquals(StickMode.UNASSIGNED, config.rightStick.mode)
        assertFalse(config.triggers.enabled)
        assertEquals(PointerMode.OFF, config.pointer.mode)
        assertEquals(VirtualAnalogStickMode.FIXED, config.pointer.joystickMode)
        assertEquals(ControllerConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion)
        assertFalse(config.shouldWriteController)
        assertEquals(ResolutionSource.DEFAULT, config.resolutionSource)
        assertTrue(config.validate().isValid)
    }

    @Test
    fun parsesAndEmitsTheV2AnalogSchema() {
        val source = JsonParser.parseString(
            """
            {
              "schemaVersion": 2,
              "enabled": false,
              "sticks": {
                "left": {"mode": "directions", "directionMode": "fourWay"},
                "right": {"mode": "pointer", "directionMode": "diagonalNumber"}
              },
              "triggers": {"pressThreshold": 0.80, "releaseThreshold": 0.45, "enabled": true},
              "pointer": {
                "mode": "cursor", "stick": "right", "clickAction": "CLICK", "joystickMode": "floating",
                "speed": 1.50, "centerX": 0.50, "centerY": 0.50, "radius": 0.20
              },
              "futureNote": "keep me"
            }
            """.trimIndent(),
        ).asJsonObject

        val config = ControllerConfig.parse(source)

        assertTrue(config.isSupported)
        assertFalse(config.enabled)
        assertEquals(DirectionMode.FOUR, config.leftStick.directionMode)
        assertEquals(StickMode.POINTER, config.rightStick.mode)
        assertEquals(DirectionMode.DIAGONAL_NUMBER, config.rightStick.directionMode)
        assertTrue(config.triggers.enabled)
        assertEquals(PointerMode.CURSOR, config.pointer.mode)
        assertEquals(PointerAction.CLICK, config.pointer.clickAction)
        assertEquals(VirtualAnalogStickMode.FLOATING, config.pointer.joystickMode)
        assertEquals("keep me", config.toJson().get("futureNote").asString)
        assertEquals(2, config.toJson().get("schemaVersion").asInt)
        assertFalse(config.toJson().has("bindings"))
        assertFalse(config.toJson().has("mode"))
        assertEquals(
            "floating",
            config.toJson().getAsJsonObject("pointer").get("joystickMode").asString,
        )
    }

    @Test
    fun legacyDigitalPayloadIsReadWithoutRecreatingASecondMappingTable() {
        val source = JsonParser.parseString(
            """
            {
              "schemaVersion": 1,
              "mode": "enabled",
              "preset": "game",
              "directionMode": "eightWay",
              "bindings": {"button_a": {"kind": "guest", "guestKey": "NUM5"}},
              "pointer": {
                "mode": "cursor",
                "clickBinding": {"kind": "pointerInteraction", "action": "CLICK"}
              }
            }
            """.trimIndent(),
        ).asJsonObject

        val config = ControllerConfig.parse(source)

        assertTrue(config.isSupported)
        assertTrue(config.enabled)
        assertEquals(PointerAction.CLICK, config.pointer.clickAction)
        val emitted = config.toJson()
        assertFalse(emitted.has("mode"))
        assertFalse(emitted.has("preset"))
        assertFalse(emitted.has("directionMode"))
        assertFalse(emitted.has("bindings"))
        assertEquals("CLICK", emitted.getAsJsonObject("pointer").get("clickAction").asString)
        assertFalse(emitted.getAsJsonObject("pointer").has("clickBinding"))
    }

    @Test
    fun unsupportedFuturePayloadRemainsOpaqueUntilExplicitRecovery() {
        val source = JsonParser.parseString(
            """{"schemaVersion": 99, "enabled": true, "vendor": {"opaque": true}}""",
        ).asJsonObject

        val config = ControllerConfig.parse(source)

        assertFalse(config.isSupported)
        assertFalse(config.shouldWriteController)
        assertEquals(source, config.toJson())

        val recovered = config.reset()
        assertTrue(recovered.isSupported)
        assertTrue(recovered.shouldWriteController)
        assertEquals(ControllerConfig.CURRENT_SCHEMA_VERSION, recovered.toJson().get("schemaVersion").asInt)
        assertFalse(recovered.toJson().has("vendor"))
    }

    @Test
    fun malformedPayloadIsUnsupportedInsteadOfPartiallyApplied() {
        val config = ControllerConfig.parse(
            JsonParser.parseString("""{"enabled":"yes"}""").asJsonObject,
        )

        assertFalse(config.isSupported)
        assertNotNull(config.notice)
        assertEquals("yes", config.toJson().get("enabled").asString)
    }

    @Test
    fun calibrationRoundTripAndRemovalAreExplicit() {
        val calibration = GamepadCalibration(
            mapOf(
                CalibrationChannel.LEFT_X to StickProcessor.ValidatedCalibration(
                    range = StickProcessor.MotionRangeLike(-1.0f, 1.0f),
                    rest = 0.0f,
                    restSpread = 0.01f,
                    sampleCount = 12,
                    restMustBeInterior = true,
                ),
            ),
        )

        val config = ControllerConfig.defaultNavigation()
            .withCalibration("pad-capability", calibration)
        val json = config.toJson()

        assertEquals(
            calibration,
            ControllerConfig.parse(json).calibrations["pad-capability"],
        )
        assertFalse(config.withoutCalibration("pad-capability").toJson().has("calibrations"))
    }
}
