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
    fun absentControllerAndLegacyMapUsesReadOnlyNavigationDefault() {
        val config = ControllerConfig.resolve(null, null)

        assertTrue(config.enabled)
        assertFalse(config.legacy)
        assertEquals(Preset.NAVIGATION, config.preset)
        assertEquals(ControllerMode.ENABLED, config.mode)
        assertEquals(DirectionMode.EIGHT, config.directionMode)
        assertFalse(config.shouldWriteController)
        assertEquals(ResolutionSource.DEFAULT, config.resolutionSource)
        assertEquals(BindingKind.HOST_ACTION, config.binding("button_start")?.kind)
        assertEquals(HostAction.OPEN_MENU.token, config.binding("button_start")?.token)
    }

    @Test
    fun legacyMapOverlaysNewPresetAndExplicitInputWins() {
        val legacy = mapOf(
            "button_a" to "NUM5",
            "dpad_up" to "NUM7",
        )

        val defaultEffective = ControllerConfig.resolve(null, legacy)
        assertEquals(Preset.NAVIGATION, defaultEffective.preset)
        assertEquals(GuestKey.NUM5, defaultEffective.binding("button_a")?.guestKey)
        assertEquals(GuestKey.NUM7, defaultEffective.binding("dpad_up")?.guestKey)
        assertFalse(defaultEffective.shouldWriteController)
        assertEquals(ResolutionSource.LEGACY_MAP, defaultEffective.resolutionSource)

        val explicitLegacy = ControllerConfig.resolve(
            controllerJson = null,
            legacyMappings = legacy,
            options = ResolveOptions(explicitMode = ControllerMode.LEGACY),
        )
        assertTrue(explicitLegacy.legacy)
        assertFalse(explicitLegacy.enabled)
        assertTrue(explicitLegacy.shouldWriteController)

        val explicitNumeric = ControllerConfig.resolve(
            controllerJson = null,
            legacyMappings = legacy,
            options = ResolveOptions(explicitPreset = Preset.NUMERIC),
        )
        assertEquals(Preset.NUMERIC, explicitNumeric.preset)
        assertEquals(GuestKey.NUM7, explicitNumeric.binding("dpad_up")?.guestKey)
        assertEquals(GuestKey.NUM8, explicitNumeric.binding("dpad_down")?.guestKey)
        assertEquals(GuestKey.NUM5, explicitNumeric.binding("button_a")?.guestKey)
    }

    @Test
    fun supportedControllerUsesPresetThenBindingOverrides() {
        val json = JsonParser.parseString(
            """
            {
              "schemaVersion": 1,
              "mode": "enabled",
              "preset": "numeric",
              "directionMode": "fourWay",
              "bindings": {
                "button_a": {"kind": "guestKey", "key": "NUM7"}
              }
            }
            """.trimIndent(),
        ).asJsonObject

        val config = ControllerConfig.resolve(json, mapOf("button_a" to "FIRE"))

        assertTrue(config.isSupported)
        assertEquals(Preset.NUMERIC, config.preset)
        assertEquals(DirectionMode.FOUR, config.directionMode)
        assertEquals(GuestKey.NUM7, config.binding("button_a")?.guestKey)
        assertEquals(GuestKey.NUM8, config.binding("dpad_down")?.guestKey)
        assertEquals(ResolutionSource.CONTROLLER, config.resolutionSource)
        assertFalse(config.shouldWriteController)
    }

    @Test
    fun manyToOneBindingsAreAllowedAndRemainDeterministic() {
        val json = JsonParser.parseString(
            """
            {
              "schemaVersion": 1,
              "preset": "game",
              "bindings": {
                "button_a": {"kind": "guestKey", "key": "NUM5"},
                "button_x": {"kind": "guestKey", "key": "NUM5"}
              }
            }
            """.trimIndent(),
        ).asJsonObject

        val config = ControllerConfig.parse(json)

        assertEquals(config.binding("button_a"), config.binding("button_x"))
        assertTrue(config.validate().isValid)
        assertEquals("NUM5", config.toJson().getAsJsonObject("bindings")
            .getAsJsonObject("button_a").get("key").asString)
        assertEquals("NUM5", config.toJson().getAsJsonObject("bindings")
            .getAsJsonObject("button_x").get("key").asString)
    }

    @Test
    fun unknownSchemaFallsBackToLegacyAndReturnsOriginalJson() {
        val json = JsonParser.parseString(
            """
            {
              "schemaVersion": 99,
              "mode": "enabled",
              "futureControllerField": {"keep": true}
            }
            """.trimIndent(),
        ).asJsonObject

        val config = ControllerConfig.parse(json)

        assertFalse(config.isSupported)
        assertTrue(config.legacy)
        assertNotNull(config.notice)
        assertTrue(config.notice!!.contains("Legacy"))
        assertFalse(config.shouldWriteController)
        assertEquals(json, config.toJson())
        assertEquals(json, config.rawJson)
    }

    @Test
    fun unknownEnumAndActionAreOpaqueUntilExplicitReset() {
        val unknownEnum = JsonParser.parseString(
            """
            {"schemaVersion":1,"preset":"futurePreset","opaque":7}
            """.trimIndent(),
        ).asJsonObject
        val enumConfig = ControllerConfig.parse(unknownEnum)
        assertFalse(enumConfig.isSupported)
        assertTrue(enumConfig.legacy)
        assertEquals(unknownEnum, enumConfig.toJson())

        val unknownAction = JsonParser.parseString(
            """
            {
              "schemaVersion":1,
              "bindings": {
                "button_a": {"kind":"hostAction","action":"FUTURE_ACTION"}
              }
            }
            """.trimIndent(),
        ).asJsonObject
        val actionConfig = ControllerConfig.parse(unknownAction)
        assertFalse(actionConfig.isSupported)
        assertTrue(actionConfig.notice!!.contains("Legacy"))
        assertEquals(unknownAction, actionConfig.toJson())

        val reset = actionConfig.reset()
        assertTrue(reset.isSupported)
        assertTrue(reset.enabled)
        assertTrue(reset.shouldWriteController)
        assertFalse(reset.toJson().has("futureControllerField"))
    }

    @Test
    fun supportedRoundTripPreservesOpaqueFieldsAtEveryKnownObjectLevel() {
        val json = JsonParser.parseString(
            """
            {
              "schemaVersion":1,
              "preset":"navigation",
              "futureTop": {"version": 4},
              "sticks": {
                "left": {
                  "mode":"directions",
                  "futureStick": {"axisCurve":"future"}
                }
              },
              "bindings": {
                "button_a": {
                  "kind":"guestKey",
                  "key":"FIRE",
                  "futureBinding": "preserve"
                }
              },
              "pointer": {
                "mode":"off",
                "futurePointer": [1, 2, 3]
              }
            }
            """.trimIndent(),
        ).asJsonObject

        val config = ControllerConfig.parse(json)
        val roundTrip = config.toJson()

        assertTrue(config.isSupported)
        assertEquals(json.get("futureTop"), roundTrip.get("futureTop"))
        assertEquals(
            json.getAsJsonObject("sticks").getAsJsonObject("left").get("futureStick"),
            roundTrip.getAsJsonObject("sticks").getAsJsonObject("left").get("futureStick"),
        )
        assertEquals(
            "preserve",
            roundTrip.getAsJsonObject("bindings").getAsJsonObject("button_a")
                .get("futureBinding").asString,
        )
        assertEquals(json.get("pointer").asJsonObject.get("futurePointer"), roundTrip
            .get("pointer").asJsonObject.get("futurePointer"))
    }

    @Test
    fun builderValidationIsResetFriendlyAndDoesNotRejectManyToOne() {
        val invalid = ControllerConfig.builder()
            .triggerThresholds(0.25, 0.5)
            .buildOrNull()
        assertEquals(null, invalid)

        val valid = ControllerConfig.builder()
            .binding("button_a", Binding.guestKey(GuestKey.NUM5))
            .binding("button_x", Binding.guestKey(GuestKey.NUM5))
            .build()
        assertTrue(valid.validate().isValid)
        assertEquals(valid.binding("button_a"), valid.binding("button_x"))
    }

    @Test
    fun calibrationRoundTripPreservesUnknownFieldsAndCanBeRemoved() {
        val calibration = GamepadCalibration(
            mapOf(
                CalibrationChannel.LEFT_X to StickProcessor.ValidatedCalibration(
                    range = StickProcessor.MotionRangeLike(-1.2f, 0.9f),
                    rest = -0.1f,
                    restSpread = 0.02f,
                    sampleCount = 12,
                    restMustBeInterior = true,
                ),
                CalibrationChannel.LEFT_TRIGGER to StickProcessor.ValidatedCalibration(
                    range = StickProcessor.MotionRangeLike(0.0f, 1.0f),
                    rest = 0.0f,
                    restSpread = 0.01f,
                    sampleCount = 12,
                    restMustBeInterior = false,
                ),
            ),
        )
        val source = JsonParser.parseString(
            """
            {
              "schemaVersion": 1,
              "preset": "navigation",
              "calibrations": {
                "pad-capability": {
                  "futureCalibrationField": "keep",
                  "left_x": {"futureChannelField": true}
                }
              }
            }
            """.trimIndent(),
        ).asJsonObject

        val config = ControllerConfig.parse(source).withCalibration("pad-capability", calibration)
        val json = config.toJson()
        val channel = json.getAsJsonObject("calibrations")
            .getAsJsonObject("pad-capability")
            .getAsJsonObject("left_x")
        assertEquals("keep", json.getAsJsonObject("calibrations")
            .getAsJsonObject("pad-capability").get("futureCalibrationField").asString)
        assertTrue(channel.get("futureChannelField").asBoolean)
        assertEquals(-1.2f, channel.get("min").asFloat, 0.0001f)
        assertEquals(12, channel.get("samples").asInt)
        assertEquals(calibration, ControllerConfig.parse(json).calibrations["pad-capability"])

        val removed = config.withoutCalibration("pad-capability").toJson()
        assertFalse(removed.getAsJsonObject("calibrations").has("pad-capability"))
    }

    @Test
    fun explicitEditRecoversOpaqueSchemaWithoutDroppingUnknownPayload() {
        val source = JsonParser.parseString(
            """
            {
              "schemaVersion": 99,
              "mode": "enabled",
              "futureControllerField": {"keep": true}
            }
            """.trimIndent(),
        ).asJsonObject

        val recovered = ControllerConfig.parse(source).toBuilder()
            .enabled(true)
            .build()
        assertTrue(recovered.isSupported)
        assertEquals(ControllerConfig.CURRENT_SCHEMA_VERSION, recovered.schemaVersion)
        assertEquals("enabled", recovered.toJson().get("mode").asString)
        assertTrue(recovered.toJson().getAsJsonObject("futureControllerField").get("keep").asBoolean)
    }

    @Test
    fun cursorClickBindingIsPersistedAsAnExplicitPointerAction() {
        val json = JsonParser.parseString(
            """
            {
              "schemaVersion": 1,
              "preset": "game",
              "bindings": {
                "button_a": {"kind": "guestKey", "key": "FIRE"}
              },
              "pointer": {
                "mode": "cursor",
                "stick": "right",
                "clickBinding": {"kind": "pointerAction", "action": "CLICK"}
              }
            }
            """.trimIndent(),
        ).asJsonObject

        val config = ControllerConfig.parse(json)

        assertEquals(PointerMode.CURSOR, config.pointer.mode)
        assertEquals(PointerAction.CLICK, config.pointer.clickBinding.pointerAction)
        assertTrue(config.validate().isValid)
        assertEquals(
            "CLICK",
            config.toJson().getAsJsonObject("pointer")
                .getAsJsonObject("clickBinding").get("action").asString,
        )
    }
}
