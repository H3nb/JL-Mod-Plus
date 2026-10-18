/* Licensed under the Apache License, Version 2.0. */
package io.github.h3nb.jlmodplus.config

import android.content.Intent
import android.net.Uri
import android.graphics.RectF
import android.os.Handler
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.View
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.input.ControllerConfig
import io.github.h3nb.jlmodplus.settings.KeyMapperMappingRules
import io.github.h3nb.jlmodplus.mmapi.synth.AudioFixtureCatalog
import io.github.h3nb.jlmodplus.util.Constants
import java.io.File
import java.util.UUID
import javax.microedition.lcdui.keyboard.KeyMapper
import javax.microedition.lcdui.keyboard.VirtualControlsKeyboard
import javax.microedition.lcdui.keyboard.VirtualKeyboard
import io.github.h3nb.jlmodplus.input.VirtualDpadGeometry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileMigrationInstrumentationTest {
    @Test fun encodingFailurePreservesCommittedFilesAndLinkedOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "gson-write-failure-${UUID.randomUUID()}").apply { mkdirs() }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        try {
            val model = ProfileModel.fresh("")
            assertTrue(ProfilesManager.saveConfig(dir, model))
            File(dir, "VirtualKeyboardLayout").writeBytes(byteArrayOf(7, 8, 9))
            File(dir, "save").writeText("user save")
            assertTrue(preferences.edit()
                .putString(PresetLinkage.originPreferenceKey(dir), "Audit")
                .putBoolean(PresetLinkage.linkedPreferenceKey(dir), true).commit())
            val before = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }
            val invalid = model.copy(virtualDpadCenterX = Float.NaN)
            assertFalse(PresetLocalOverride.runDetachedWrite(context, dir) {
                ProfilesManager.saveConfig(dir, invalid)
            })
            assertEquals(before, dir.listFiles()!!.associate { it.name to it.readBytes().toList() })
            val ownership = PresetLinkage(preferences, dir)
            assertTrue(ownership.isLinked)
            assertEquals("Audit", ownership.origin)
        } finally {
            ProfilesManager.clearMidletOwnershipMetadata(preferences, dir)
            dir.deleteRecursively()
        }
    }

    @Test fun openingAndCancellingShaderTuningDoesNotMaterializeDefaultParameters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "gson-shader-cancel-${UUID.randomUUID()}")
        val source = File(root, "templates/CRT").apply { mkdirs() }
        File(root, "shaders").apply { mkdirs() }.resolve("crt.ini").writeText("""
            [CRT]
            Name=CRT
            Author=tester
            Fragment=crt.fsh
            Vertex=crt.vsh
            SettingName1=Amount
            SettingDefaultValue1=1
            SettingMinValue1=0
            SettingMaxValue1=2
            SettingStep1=0.25
        """.trimIndent())
        val model = ProfileModel.fresh("").apply {
            shader = ShaderSelection.fromInfo(ShaderInfo("CRT", "tester").apply {
                fragment = "crt.fsh"; vertex = "crt.vsh"
            })
        }
        try {
            assertTrue(ProfilesManager.saveConfig(source, model))
            val before = File(source, "config.json").readBytes()
            val intent = Intent(Constants.ACTION_EDIT_PROFILE, Uri.fromFile(source), context, ConfigActivity::class.java)
            ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val params = ConfigActivity::class.java.getDeclaredField("params").apply { isAccessible = true }
                    val loaded = params.get(activity) as ProfileModel
                    assertNull(loaded.shader!!.values)
                    ConfigActivity::class.java.getDeclaredMethod("showShaderSettings").apply {
                        isAccessible = true
                    }.invoke(activity)
                    activity.supportFragmentManager.executePendingTransactions()
                    assertNotNull(activity.supportFragmentManager.findFragmentByTag("ShaderTuning"))
                }
                // Keep the actual dialog open across recreation, exercising fragment arguments.
                scenario.recreate()
                scenario.onActivity { activity ->
                    val params = ConfigActivity::class.java.getDeclaredField("params").apply { isAccessible = true }
                    val dialog = activity.supportFragmentManager.findFragmentByTag("ShaderTuning") as ShaderTuneAlert
                    val restored = dialog.requireArguments().getParcelable<ShaderInfo>("shader")!!
                    assertTrue(restored.hasTunableSettings())
                    assertEquals(1f, restored.values[0], 0f)
                    dialog.dismiss()
                    activity.supportFragmentManager.executePendingTransactions()
                    assertNull((params.get(activity) as ProfileModel).shader!!.values)
                    val form = ConfigActivity::class.java.getDeclaredField("currentForm").apply { isAccessible = true }
                    assertNull((form.get(activity) as ConfigFormState).shader!!.values)
                }
                scenario.recreate()
            }
            assertArrayEquals(before, File(source, "config.json").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun shaderDialogParcelAndDraftKeepParametersIndependent() {
        val info = ShaderInfo("CRT", "tester").apply {
            values = floatArrayOf(1.25f, 2.5f, 3f, 4f)
            settings[0] = ShaderInfo.Setting().apply { name = "Amount"; max = 5f; step = 0.25f }
        }
        val baseline = ShaderSelection.fromInfo(info)!!
        val parcel = android.os.Parcel.obtain()
        try {
            info.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = ShaderInfo.CREATOR.createFromParcel(parcel)
            assertArrayEquals(info.values, restored.values, 0f)
            assertTrue(restored.hasTunableSettings())
            restored.values[0] = 4f
            assertEquals(1.25f, baseline.values!![0])
            assertEquals(1.25f, info.values[0], 0f)
        } finally {
            parcel.recycle()
        }
    }

    @Test fun actualXmlV0AndSparseOverridesSurviveDurableSaveAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "gson-xml-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(dir, "config.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
                <map>
                  <int name="ScreenWidth" value="128" />
                  <int name="ScreenHeight" value="160" />
                  <boolean name="HwAcceleration" value="true" />
                  <boolean name="ScreenScaleToFit" value="true" />
                  <boolean name="ScreenKeepAspectRatio" value="false" />
                  <string name="KeyMappings">[{"key":131,"value":0},{"key":132,"value":-2147483648}]</string>
                  <string name="SystemProperties">custom: Ω</string>
                  <int-array name="IgnoredHistory" num="2"><item value="1" /><item value="2" /></int-array>
                  <boolean-array name="IgnoredFlags" num="2"><item value="true" /><item value="false" /></boolean-array>
                  <map name="Shader">
                    <string name="name">CRT</string>
                    <double-array name="Settings" num="2"><item value="1.25" /><item value="2.5" /></double-array>
                  </map>
                </map>""")
            val model = checkNotNull(ProfilesManager.loadConfig(dir, true,
                ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE, false))
            assertEquals(128, model.screenWidth)
            assertEquals(3, model.graphicsMode)
            assertEquals(2, model.screenScaleType)
            assertEquals(listOf(1.25f, 2.5f), model.shader!!.values)
            assertFalse(model.fontAA)
            assertTrue(model.systemProperties!!.contains("custom: Ω"))
            assertTrue(model.systemProperties!!.contains("microedition."))
            assertFalse(File(dir, "config.xml").exists())
            val reopened = checkNotNull(ProfilesManager.loadConfig(dir))
            assertEquals(7, reopened.version)
            assertEquals(128, reopened.screenWidth)
            assertEquals(listOf(1.25f, 2.5f), reopened.shader!!.values)

            val sparse = ProfileKeyMappings.toSparse(model.keyMappings)
            assertEquals(0, sparse.get(131, 123))
            assertEquals(Int.MIN_VALUE, sparse.get(132))
            val restored = ProfileKeyMappings.decode(ProfileKeyMappings.encode(sparse))
            assertEquals(model.keyMappings, ProfileKeyMappings.fromSparse(restored))
            val snapshot = ProfileKeyMappings.fromSparse(restored)
            restored.put(131, 99)
            assertEquals(0, snapshot[131])
            try {
                (snapshot as MutableMap<Int, Int>)[131] = 99
                fail("Prepared key mappings must be read-only")
            } catch (_: UnsupportedOperationException) {
                // Android's runtime JUnit version predates assertThrows.
            }
            restored.put(131, 0)
            val effective = KeyMapperMappingRules.resolve(SparseIntArray().apply { put(132, 42) }, restored)
            assertTrue(effective.indexOfKey(131) >= 0)
            assertTrue(effective.indexOfKey(132) < 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun controllerMergeRetainsUnknownNullAndLargeIntegerAndRejectsCoercedTypes() {
        val tree = Json.parseToJsonElement("""{"schemaVersion":2,"enabled":true,"future":{"n":9007199254740993,"nil":null}}""").jsonObject
        val edited = ControllerConfig.parse(tree).toBuilder().enabled(false).build().toJson()
        assertEquals(tree["future"], edited["future"])
        assertFalse(ControllerConfig.parse("""{"schemaVersion":2,"enabled":"true"}""").isSupported)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "gson-controller-${UUID.randomUUID()}")
        try {
            val config = ProfileModel.fresh("").apply { controller = edited }
            assertTrue(ProfilesManager.saveConfig(dir, config))
            assertEquals(edited, checkNotNull(ProfilesManager.loadPreparedMidletConfig(dir, false)).controller)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun invalidAndFutureNamedEditorCannotPublishDefaultsOrRemoveSideData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "gson-editor-${UUID.randomUUID()}")
        val source = File(root, "templates/Existing").apply { mkdirs() }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val oldWorkdir = preferences.getString(Constants.PREF_EMULATOR_DIR, null)
        try {
            assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.path).commit())
            File(source, "VirtualKeyboardLayout").writeBytes(byteArrayOf(1, 2, 3))
            File(source, "save").writeText("save")
            for (payload in listOf("broken", """{"Version":8,"ScreenWidth":999}""")) {
                File(source, "config.json").writeText(payload)
                val before = source.listFiles()!!.associate { it.name to it.readBytes().toList() }
                val intent = Intent(Constants.ACTION_EDIT_PROFILE, Uri.fromFile(source), context, ConfigActivity::class.java)
                ActivityScenario.launch<ConfigActivity>(intent).use { }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(before, source.listFiles()!!.associate { it.name to it.readBytes().toList() })
            }
        } finally {
            val edit = preferences.edit()
            if (oldWorkdir == null) edit.remove(Constants.PREF_EMULATOR_DIR) else edit.putString(Constants.PREF_EMULATOR_DIR, oldWorkdir)
            assertTrue(edit.commit())
            root.deleteRecursively()
        }
    }

    @Test fun missingConfigRecoveryPreservesExistingLayoutAndStaysCustom() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "gson-missing-${UUID.randomUUID()}").apply { mkdirs() }
        val preferences = context.getSharedPreferences("gson-missing-test", 0)
        var keyboard: VirtualControlsKeyboard? = null
        try {
            val portrait = RectF(0f, 0f, 600f, 1200f)
            fun resize(value: VirtualControlsKeyboard, bounds: RectF) =
                value.resize(bounds, 0f, 0f, bounds.width(), bounds.height())
            fun geometry(value: VirtualControlsKeyboard) = VirtualControlsKeyboard::class.java
                .getDeclaredMethod("dpadGeometry").apply { isAccessible = true }.invoke(value) as VirtualDpadGeometry
            fun drag(value: VirtualControlsKeyboard) {
                val g = geometry(value)
                assertTrue(value.pointerPressed(0, g.centerX, g.centerY))
                assertTrue(value.pointerDragged(0, g.centerX + 30f, g.centerY - 18f))
                assertTrue(value.pointerReleased(0, g.centerX + 30f, g.centerY - 18f))
            }
            val initial = VirtualControlsKeyboard(ProfileModel.fresh(""), dir).apply {
                setView(View(context)); resize(this, portrait)
                setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD)
                setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS)
            }
            keyboard = initial
            drag(initial)
            assertTrue(initial.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM))
            val expectedGeometry = geometry(initial)
            val layout = File(dir, "VirtualKeyboardLayout").apply {
                writeBytes(initial.encodeCurrentLayoutForPersistence())
            }
            val before = layout.readBytes()
            disposeKeyboard(initial)
            keyboard = null
            assertTrue(FreshInstalledMidletInitializer.publishMissingConfig(preferences, dir, false))
            assertArrayEquals(before, layout.readBytes())
            assertFalse(preferences.getBoolean(ProfileModel.builtInThemePreferenceKey(dir), false))
            val working = checkNotNull(ProfilesManager.loadPreparedMidletConfig(dir, false))
            val baseline = ProfileConfigMatcher.copyConfig(working)
            val baselineJson = ProfileJson.encode(baseline)
            val active = VirtualControlsKeyboard(working, dir, false).apply {
                setView(View(context)); resize(this, portrait)
                setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS)
            }
            keyboard = active
            assertEquals(VirtualKeyboard.TYPE_CUSTOM, active.layout)
            assertTrue(working.virtualDpadEnabled)
            assertEquals(expectedGeometry.centerX, geometry(active).centerX, 0.5f)
            assertEquals(expectedGeometry.centerY, geometry(active).centerY, 0.5f)
            val editBaseline = active.captureLayoutEditState()
            drag(active)
            assertNotEquals(expectedGeometry.centerX, geometry(active).centerX, 0.5f)
            resize(active, RectF(0f, 0f, 1200f, 600f))
            resize(active, portrait)
            active.restoreLayoutEditState(editBaseline)
            assertEquals(expectedGeometry.centerX, geometry(active).centerX, 0.5f)
            assertEquals(expectedGeometry.centerY, geometry(active).centerY, 0.5f)
            assertEquals(baselineJson, ProfileJson.encode(baseline))
            assertArrayEquals(before, layout.readBytes())
        } finally {
            keyboard?.let(::disposeKeyboard)
            preferences.edit().clear().commit()
            dir.deleteRecursively()
        }
    }

    @Test fun resumeAfterDraftBecomesInvalidOrFutureClosesWithoutPublishing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "gson-resume-${UUID.randomUUID()}")
        val source = File(root, "templates/Existing").apply { mkdirs() }
        try {
            assertTrue(ProfilesManager.saveConfig(source, ProfileModel.fresh("")))
            File(source, "save").writeText("save data")
            val sourceBefore = source.listFiles()!!.associate { it.name to it.readBytes().toList() }
            for (payload in listOf("broken", """{"Version":8,"ScreenWidth":999}""")) {
                val intent = Intent(Constants.ACTION_EDIT_PROFILE, Uri.fromFile(source), context, ConfigActivity::class.java)
                ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
                    lateinit var draft: File
                    scenario.onActivity { activity ->
                        draft = ConfigActivity::class.java.getDeclaredField("configDir").apply {
                            isAccessible = true
                        }.get(activity) as File
                    }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    File(draft, "config.json").writeText(payload)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitClosed(scenario)
                }
                assertEquals(sourceBefore, source.listFiles()!!.associate { it.name to it.readBytes().toList() })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun installedEditorResumePreservesInvalidOrFutureCommittedConfig() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "gson-installed-resume-${UUID.randomUUID()}")
        val app = File(root, "converted/fixture").apply { mkdirs() }
        val dir = File(root, "configs/fixture").apply { mkdirs() }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = preferences.getString(Constants.PREF_EMULATOR_DIR, null)
        try {
            val appId = AudioFixtureCatalog.install(context, root)
            assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.path).commit())
            File(dir, "save").writeText("user data")
            for (payload in listOf("broken", """{"Version":8,"ScreenWidth":999}""")) {
                assertTrue(ProfilesManager.saveConfig(dir, ProfileModel.fresh("")))
                val intent = Intent(Constants.ACTION_EDIT, Uri.fromFile(app), context, ConfigActivity::class.java)
                    .putExtra(Constants.KEY_LIBRARY_APP_ID, appId)
                ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
                    scenario.moveToState(Lifecycle.State.CREATED)
                    File(dir, "config.json").writeText(payload)
                    val before = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitClosed(scenario)
                    assertEquals(before, dir.listFiles()!!.associate { it.name to it.readBytes().toList() })
                }
            }
        } finally {
            ProfilesManager.clearMidletOwnershipMetadata(preferences, dir)
            val edit = preferences.edit()
            if (previous == null) edit.remove(Constants.PREF_EMULATOR_DIR) else edit.putString(Constants.PREF_EMULATOR_DIR, previous)
            assertTrue(edit.commit())
            root.deleteRecursively()
        }
    }

    private fun disposeKeyboard(keyboard: VirtualKeyboard) {
        keyboard.cancel()
        val handler = VirtualKeyboard::class.java.getDeclaredField("handler").apply {
            isAccessible = true
        }.get(keyboard) as Handler
        handler.looper.quitSafely()
    }

    private fun awaitClosed(scenario: ActivityScenario<ConfigActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
