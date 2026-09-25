/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.settings

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.config.ProfileModel
import io.github.h3nb.jlmodplus.config.ProfilesManager
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import io.github.h3nb.jlmodplus.util.Constants
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyMapperInstalledIdentityTest {
    private var fixtureRoot: File? = null

    @After
    fun tearDown() {
        fixtureRoot?.let(::deleteRecursively)
    }

    @Test
    fun staleMapperCannotPublishIntoReplacementIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "key-mapper-stale-identity-fixture")
        fixtureRoot = root
        val appDir = File(File(root, "converted"), "fixture")
        val configDir = File(File(root, "configs"), "fixture")
        assertTrue(appDir.mkdirs() || appDir.isDirectory)
        writeConfig(configDir, 176)
        val originalId = insertIdentity(context, root, "Original")
        val intent = Intent(
            Constants.ACTION_EDIT,
            Uri.parse(configDir.absolutePath),
            context,
            KeyMapperActivity::class.java,
        )
            .putExtra(Constants.KEY_INSTALLED_APP_PATH, appDir.absolutePath)
            .putExtra(Constants.KEY_LIBRARY_APP_ID, originalId)

        ActivityScenario.launch<KeyMapperActivity>(intent).use { scenario ->
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
                activity.androidToMIDP.put(KeyEvent.KEYCODE_F1, 424242)
                assertFalse(activity.save())
                assertTrue(activity.isFinishing)
            }

            val replacement = ProfilesManager.loadPreparedMidletConfig(configDir, false)!!
            assertEquals(640, replacement.screenWidth)
            assertFalse(replacement.keyMappings?.indexOfKey(KeyEvent.KEYCODE_F1)?.let { it >= 0 } ?: false)
        }
    }

    private fun insertIdentity(
        context: android.content.Context,
        root: File,
        title: String,
    ): Long {
        val database = LibraryDatabase.open(context, root)
        return try {
            runBlocking {
                database.libraryDao().insertApp(
                    LibraryAppEntity(
                        storageKey = "fixture",
                        sourceTitle = title,
                        sourceVendor = "JL-Mod Plus",
                        sourceVersion = "1.0",
                    ),
                )
            }
        } finally {
            database.close()
        }
    }

    private fun writeConfig(dir: File, width: Int) {
        assertTrue(dir.mkdirs() || dir.isDirectory)
        val profile = ProfileModel().apply {
            this.dir = dir
            version = ProfileModel.VERSION
            screenWidth = width
            screenHeight = 320
            vkType = 3
            systemProperties = ""
        }
        assertTrue(ProfilesManager.saveConfig(profile))
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        file.listFiles()?.forEach(::deleteRecursively)
        check(file.delete() || !file.exists()) { "Unable to delete $file" }
    }
}
