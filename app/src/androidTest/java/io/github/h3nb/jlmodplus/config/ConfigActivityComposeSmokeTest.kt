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

package io.github.h3nb.jlmodplus.config

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.ComposeView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import io.github.h3nb.jlmodplus.settings.KeyMapperActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.util.Constants
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConfigActivityComposeSmokeTest {
    private var fixtureRoot: File? = null
    private var previousWorkdir: String? = null
    private var workdirChanged = false
    private var linkedConfigDir: File? = null

    @After
    fun tearDown() {
        if (workdirChanged) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
            val edit = preferences.edit()
            if (previousWorkdir == null) edit.remove(Constants.PREF_EMULATOR_DIR)
            else edit.putString(Constants.PREF_EMULATOR_DIR, previousWorkdir)
            assertTrue(edit.commit())
        }
        linkedConfigDir?.let { configDir ->
            val preferences = PreferenceManager.getDefaultSharedPreferences(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
            assertTrue(ProfilesManager.clearMidletOwnershipMetadata(preferences, configDir))
        }
        fixtureRoot?.let(::deleteRecursively)
    }

    @Test
    fun freshlyInitializedAppRoutesThroughConfigUntilPlay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "first-open-config-fixture-${System.nanoTime()}")
        fixtureRoot = root
        val appDir = File(root, "converted/Bounce")
        val configDir = File(root, "configs/Bounce")
        assertTrue(appDir.mkdirs() || appDir.isDirectory)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        assertTrue(FreshInstalledMidletInitializer.initialize(
            preferences, File(root, "templates"), configDir, false).isSuccess)
        assertTrue(FreshInstalledMidletInitializer.needsReview(configDir))
        val appId = installIdentity(context, root, "Bounce")
        val intent = Intent(Intent.ACTION_DEFAULT, Uri.parse(appDir.absolutePath),
            context, ConfigActivity::class.java)
            .putExtra(Constants.KEY_MIDLET_NAME, "Bounce")
            .putExtra(Constants.KEY_LIBRARY_APP_ID, appId)

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<ComposeView>(R.id.config_compose_root))
            }
            val monitor = instrumentation.addMonitor(ConfigActivity::class.java.name, null, true)
            try {
                scenario.onActivity { activity ->
                    Config.startApp(activity, "Bounce", appDir.absolutePath, appId)
                }
                assertTrue(instrumentation.checkMonitorHit(monitor, 1))
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            assertTrue(FreshInstalledMidletInitializer.needsReview(configDir))
            scenario.onActivity { activity ->
                ConfigActivity::class.java.getDeclaredMethod("startMIDlet").apply {
                    isAccessible = true
                }.invoke(activity)
            }
        }
        assertFalse(FreshInstalledMidletInitializer.needsReview(configDir))
    }

    @Test
    fun keyMapperInitializationUsesInstalledAppWorkdirWhenAnotherIsActive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        previousWorkdir = preferences.getString(Constants.PREF_EMULATOR_DIR, null)
        workdirChanged = true
        val fixture = File(context.filesDir, "key-mapper-workdir-switch-fixture")
        fixtureRoot = fixture
        val workdirA = File(fixture, "A")
        val workdirB = File(fixture, "B")
        val appDir = File(workdirA, "converted/Bounce")
        val local = File(workdirA, "configs/Bounce")
        val sourceA = File(workdirA, "templates/K800i")
        val sourceB = File(workdirB, "templates/K800i")
        assertTrue(appDir.mkdirs())
        writeConfig(local, 176, 1)
        writeConfig(sourceA, 360, 1)
        writeConfig(sourceB, 640, 1)
        linkedConfigDir = local
        assertTrue(PresetLinkage(preferences, local).linkTo("K800i"))
        assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR,
            workdirB.absolutePath).commit())
        val appId = installIdentity(context, workdirA, "Bounce")
        val intent = Intent(Constants.ACTION_EDIT, Uri.parse(local.absolutePath),
            context, KeyMapperActivity::class.java)
            .putExtra(Constants.KEY_INSTALLED_APP_PATH, appDir.absolutePath)
            .putExtra(Constants.KEY_LIBRARY_APP_ID, appId)

        ActivityScenario.launch<KeyMapperActivity>(intent).use {
            assertEquals(360, ProfilesManager.loadPreparedMidletConfig(local, false)!!.screenWidth)
            assertEquals(640, ProfilesManager.loadPreparedMidletConfig(sourceB, false)!!.screenWidth)
        }
    }

    @Test
    fun openInstalledEditorAppliesFromItsCapturedWorkdirAfterActiveWorkdirSwitch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        previousWorkdir = preferences.getString(Constants.PREF_EMULATOR_DIR, null)
        workdirChanged = true
        val fixture = File(context.filesDir, "config-workdir-switch-fixture")
        fixtureRoot = fixture
        val workdirA = File(fixture, "A")
        val workdirB = File(fixture, "B")
        val appDir = File(workdirA, "converted/Bounce")
        val local = File(workdirA, "configs/Bounce")
        val sourceA = File(workdirA, "templates/K800i")
        val sourceB = File(workdirB, "templates/K800i")
        assertTrue(appDir.mkdirs())
        writeConfig(local, 176, 1)
        writeConfig(sourceA, 360, 1)
        writeConfig(sourceB, 640, 1)
        assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR,
            workdirA.absolutePath).commit())
        val appId = installIdentity(context, workdirA, "Bounce")
        val intent = Intent(Constants.ACTION_EDIT, Uri.parse(appDir.absolutePath),
            context, ConfigActivity::class.java)
            .putExtra(Constants.KEY_MIDLET_NAME, "Bounce")
            .putExtra(Constants.KEY_LIBRARY_APP_ID, appId)

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(File(workdirA, "templates"), activity.profilesRoot)
                assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR,
                    workdirB.absolutePath).commit())
                val cachedDefault = ConfigActivity::class.java.getDeclaredField(
                    "cachedDefaultProfileName")
                cachedDefault.isAccessible = true
                cachedDefault.set(activity, "K800i")
                val createState = ConfigActivity::class.java.getDeclaredMethod("createUiState")
                createState.isAccessible = true
                val state = createState.invoke(activity) as ConfigUiState
                assertNull(state.profileStatus.defaultProfile)
                val apply = ConfigActivity::class.java.getDeclaredMethod(
                    "applyTemplate", String::class.java,
                    ConfigFormEvents.PresetApplyScope::class.java,
                )
                apply.isAccessible = true
                assertTrue(apply.invoke(activity, "K800i",
                    ConfigFormEvents.PresetApplyScope.SETTINGS) as Boolean)
            }
        }

        assertEquals(360, ProfilesManager.loadPreparedMidletConfig(local, false)!!.screenWidth)
        assertEquals(640, ProfilesManager.loadPreparedMidletConfig(sourceB, false)!!.screenWidth)
    }

    @Test
    fun configActivityHostsComposeRootForRealProfileFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "config-compose-fixture")
        fixtureRoot = root
        val appDir = File(File(root, "converted"), "fixture")
        assertTrue(appDir.mkdirs() || appDir.isDirectory)
        val appId = installIdentity(context, root, "fixture")
        val intent = Intent(
            Constants.ACTION_EDIT,
            Uri.parse(appDir.absolutePath),
            context,
            ConfigActivity::class.java,
        )
            .putExtra(Constants.KEY_MIDLET_NAME, "Config Compose Fixture")
            .putExtra(Constants.KEY_LIBRARY_APP_ID, appId)

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val composeView = activity.findViewById<ComposeView>(R.id.config_compose_root)
                assertNotNull("ConfigActivity must host the Compose form", composeView)
                assertNotNull("ConfigActivity must retain a native window", activity.window)
            }
        }
    }

    @Test
    fun staleConfigActivityCannotSaveIntoReplacementIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "config-stale-identity-fixture")
        fixtureRoot = root
        val appDir = File(File(root, "converted"), "fixture")
        val configDir = File(File(root, "configs"), "fixture")
        assertTrue(appDir.mkdirs() || appDir.isDirectory)
        writeConfig(configDir, 176)
        val originalId = installIdentity(context, root, "fixture")
        val intent = Intent(
            Constants.ACTION_EDIT,
            Uri.parse(appDir.absolutePath),
            context,
            ConfigActivity::class.java,
        )
            .putExtra(Constants.KEY_MIDLET_NAME, "Original")
            .putExtra(Constants.KEY_LIBRARY_APP_ID, originalId)

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.recreate()
            val database = LibraryDatabase.open(context, root)
            val replacementId = try {
                runBlocking {
                    database.libraryDao().deleteAppByStorageKey("fixture")
                    database.libraryDao().insertApp(
                        LibraryAppEntity(
                            storageKey = "fixture",
                            sourceTitle = "Replacement",
                            sourceVendor = "JL-Mod Plus",
                            sourceVersion = "2.0",
                        ),
                    )
                }
            } finally {
                database.close()
            }
            assertFalse(originalId == replacementId)
            writeConfig(configDir, 640)

            scenario.onActivity { activity ->
                assertFalse(activity.saveParams())
                assertTrue(activity.isFinishing)
            }

            assertEquals(
                640,
                ProfilesManager.loadPreparedMidletConfig(configDir, false)!!.screenWidth,
            )
        }
    }

    @Test
    fun failedBackSaveKeepsInstalledConfigEditorOpenUntilRetrySucceeds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "config-failed-back-${System.nanoTime()}")
        fixtureRoot = root
        val appDir = File(root, "converted/fixture")
        val configDir = File(root, "configs/fixture")
        assertTrue(appDir.mkdirs())
        writeConfig(configDir, 176)
        val appId = installIdentity(context, root, "fixture")
        val intent = Intent(
            Constants.ACTION_EDIT, Uri.parse(appDir.absolutePath),
            context, ConfigActivity::class.java,
        ).putExtra(Constants.KEY_MIDLET_NAME, "Config Save Fixture")
            .putExtra(Constants.KEY_LIBRARY_APP_ID, appId)
        val blocker = File(configDir, Config.MIDLET_CONFIG_FILE.substring(1) + ".new")

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                updateProfileForm(activity) { it.toBuilder().screenWidth("360").build() }
                assertTrue(blocker.mkdirs())
                File(blocker, "nonempty").writeText("prevent atomic temporary cleanup")
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse("Failed save must not close the editor", activity.isFinishing)
                assertEquals("360", profileForm(activity).screenWidth)
            }
            assertEquals(
                176,
                ProfilesManager.loadPreparedMidletConfig(configDir, false)!!.screenWidth,
            )
            deleteRecursively(blocker)
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue("Editor should close after the draft is durable", activity.isFinishing)
            }
        }
        assertEquals(
            360,
            ProfilesManager.loadPreparedMidletConfig(configDir, false)!!.screenWidth,
        )
    }

    @Test
    fun newProfileCanBeSavedWithoutChangingDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "new-profile-editor-${System.nanoTime()}")
        fixtureRoot = root
        val target = File(root, "templates/New Profile")
        assertTrue(target.parentFile!!.mkdirs())
        val intent = Intent(
            Constants.ACTION_EDIT_PROFILE, Uri.parse(target.absolutePath),
            context, ConfigActivity::class.java,
        )

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(profileDraftDirty(activity))
                ConfigActivity::class.java.getDeclaredMethod("saveProfileDraft").apply {
                    isAccessible = true
                }.invoke(activity)
            }
        }
        assertTrue(target.isDirectory)
        assertNotNull(ProfilesManager.loadConfig(
            target, false, ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE, false,
        ))
    }

    @Test
    fun unchangedProfileAndNoOpFormUpdateStayCleanAfterResume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "profile-clean-resume-${System.nanoTime()}")
        fixtureRoot = root
        val target = File(root, "templates/Existing")
        writeConfig(target, 176)
        val intent = Intent(
            Constants.ACTION_EDIT_PROFILE, Uri.parse(target.absolutePath),
            context, ConfigActivity::class.java,
        )

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(profileDraftDirty(activity))
                updateProfileForm(activity) { it.toBuilder().build() }
                assertFalse(profileDraftDirty(activity))
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse(profileDraftDirty(activity))
                updateProfileForm(activity) { it.toBuilder().screenWidth("360").build() }
                assertTrue(profileDraftDirty(activity))
            }
        }
    }

    @Test
    fun externalProfileConfigEditMarksDraftDirtyOnReturn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "profile-external-edit-${System.nanoTime()}")
        fixtureRoot = root
        val target = File(root, "templates/Existing")
        writeConfig(target, 176)
        val intent = Intent(
            Constants.ACTION_EDIT_PROFILE, Uri.parse(target.absolutePath),
            context, ConfigActivity::class.java,
        )
        lateinit var draft: File

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(profileDraftDirty(activity))
                draft = profileDraftDir(activity)
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            val changed = ProfilesManager.loadConfig(
                draft, false, ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE, false,
            )!!
            changed.screenWidth = 640
            assertTrue(ProfilesManager.saveConfig(draft, changed))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(profileDraftDirty(activity))
                assertEquals("640", profileForm(activity).screenWidth)
            }
        }
    }

    @Test
    fun failedPauseSaveKeepsUnsavedFormOnResume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "profile-failed-pause-${System.nanoTime()}")
        fixtureRoot = root
        val target = File(root, "templates/Existing")
        writeConfig(target, 176)
        val intent = Intent(
            Constants.ACTION_EDIT_PROFILE, Uri.parse(target.absolutePath),
            context, ConfigActivity::class.java,
        )
        lateinit var blocker: File
        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                updateProfileForm(activity) { it.toBuilder().screenWidth("360").build() }
                blocker = File(profileDraftDir(activity),
                    Config.MIDLET_CONFIG_FILE.substring(1) + ".new")
                assertTrue(blocker.mkdirs())
                File(blocker, "nonempty").writeText("prevent atomic temporary cleanup")
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            assertEquals(176, ProfilesManager.loadConfig(
                blocker.parentFile, false,
                ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE, false,
            )!!.screenWidth)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals("360", profileForm(activity).screenWidth)
                assertTrue(profileDraftDirty(activity))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("360", profileForm(activity).screenWidth)
                assertTrue(profileDraftDirty(activity))
            }
            deleteRecursively(blocker)
        }
    }

    private fun profileDraftDirty(activity: ConfigActivity): Boolean =
        ConfigActivity::class.java.getDeclaredField("profileDraftDirty").apply {
            isAccessible = true
        }.getBoolean(activity)

    private fun profileDraftDir(activity: ConfigActivity): File =
        ConfigActivity::class.java.getDeclaredField("profileEditDraftDir").apply {
            isAccessible = true
        }.get(activity) as File

    private fun profileForm(activity: ConfigActivity): ConfigFormState =
        ConfigActivity::class.java.getDeclaredField("currentForm").apply {
            isAccessible = true
        }.get(activity) as ConfigFormState

    private fun updateProfileForm(
        activity: ConfigActivity,
        transform: (ConfigFormState) -> ConfigFormState,
    ) {
        ConfigActivity::class.java.getDeclaredMethod(
            "updateForm", ConfigFormState::class.java,
        ).apply {
            isAccessible = true
        }.invoke(activity, transform(profileForm(activity)))
    }

    private fun installIdentity(
        context: android.content.Context,
        root: File,
        storageKey: String,
    ): Long {
        val database = LibraryDatabase.open(context, root)
        return try {
            runBlocking {
                database.libraryDao().insertApp(
                    LibraryAppEntity(
                        storageKey = storageKey,
                        sourceTitle = "Fixture",
                        sourceVendor = "JL-Mod Plus",
                        sourceVersion = "1.0",
                    ),
                )
            }
        } finally {
            database.close()
        }
    }

    private fun writeConfig(dir: File, width: Int, keyboardType: Int = 3) {
        assertTrue(dir.mkdirs() || dir.isDirectory)
        val profile = ProfileModel().apply {

            version = ProfileModel.VERSION
            screenWidth = width
            screenHeight = 320
            vkType = keyboardType
            systemProperties = ""
        }
        assertTrue(ProfilesManager.saveConfig(dir, profile))
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        file.listFiles()?.forEach(::deleteRecursively)
        check(file.delete() || !file.exists()) { "Unable to delete $file" }
    }
}
