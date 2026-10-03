// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.crashes

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.LauncherActivity
import io.github.h3nb.jlmodplus.MainActivity
import io.github.h3nb.jlmodplus.runtime.MidletKeepAliveService
import io.github.h3nb.jlmodplus.runtime.RuntimeStorageLease
import io.github.h3nb.jlmodplus.util.Constants
import java.io.File
import jlmod.runtimefixture.LifecycleMidlet
import javax.microedition.shell.CrashRuntimeLifecycleControlActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in fixture for the external smoke test: shutdown deliberately kills instrumentation. */
@RunWith(AndroidJUnit4::class)
class RuntimeShutdownFixtureTest {
    @Test
    fun externalShutdownFixture() {
        val command = InstrumentationRegistry.getArguments().getString("runtimeShutdownFixture")
        assumeTrue(command in setOf("remove", "exit", "fallback", "verify", "cleanup"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val backup = context.getSharedPreferences("runtime-shutdown-fixture", Context.MODE_PRIVATE)
        val root = File(context.filesDir, "runtime-shutdown-fixture")
        val appDir = File(root, "converted/fixture")
        when (command) {
            "remove", "exit", "fallback" -> {
                assertFalse("Clean up the previous fixture first",
                    backup.contains("hadWorkdir") || backup.contains("session"))
                assertTrue(backup.edit()
                    .putString("action", command)
                    .putBoolean("hadWorkdir", preferences.contains(Constants.PREF_EMULATOR_DIR))
                    .putString("workdir", preferences.getString(Constants.PREF_EMULATOR_DIR, null))
                    .putStringSet("reports", LocalDiagnosticRepository.load(context)
                        .map { it.id }.toSet())
                    .commit())
                CrashRuntimeIsolationTest.deleteRecursively(root)
                CrashRuntimeIsolationTest.prepareLifecycleFixture(context, root, appDir)
                assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.path).commit())
                val marker = File(root, "ready.marker")
                CrashRuntimeIsolationTest.writeLifecycleManifest(appDir, LifecycleMidlet.MODE_HOLD, marker)
                CrashRuntimeIsolationTest.launchLifecycleMidlet(context, appDir)
                CrashRuntimeIsolationTest.awaitMarker(marker)
                val state = MidletSessionStore.read(context)
                assertNotNull(state)
                assertTrue(backup.edit().putString("session", state!!.generation).commit())
                val deadline = SystemClock.uptimeMillis() + 10_000L
                while (CrashRuntimeIsolationTest.processPid(context,
                        context.packageName + ":memory_engine") == 0
                    && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100L)
                }
                assertNotEquals(0, CrashRuntimeIsolationTest.processPid(context,
                    context.packageName + ":memory_engine"))
                CrashRuntimeIsolationTest.sendAndroidTaskHome()
                CrashRuntimeIsolationTest.awaitJournalStage(context, state.generation,
                    MidletSessionJournal.Stage.PAUSED)
                assertNotEquals(0, CrashRuntimeIsolationTest.processPid(context, context.packageName))
                assertNotEquals(0, CrashRuntimeIsolationTest.processPid(context,
                    context.packageName + ":midlet"))
                assertNotEquals(0, CrashRuntimeIsolationTest.processPid(context,
                    context.packageName + ":memory_engine"))
                instrumentation.sendStatus(0, Bundle().apply { putString("shutdownRequested", command) })
                if (command == "remove") {
                    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val task = manager.appTasks.firstOrNull { it.taskInfo?.baseIntent?.data?.path == appDir.path }
                    assertNotNull("Live MIDlet task must exist before dismissal", task)
                    task!!.finishAndRemoveTask()
                } else if (command == "fallback") {
                    context.startActivity(Intent(context, CrashRuntimeLifecycleControlActivity::class.java)
                        .putExtra(CrashRuntimeLifecycleControlActivity.EXTRA_COMMAND,
                            CrashRuntimeLifecycleControlActivity.COMMAND_SHUTDOWN_DISPATCH_FAILURE)
                        .putExtra(CrashRuntimeLifecycleControlActivity.EXTRA_DISPATCH_DENIED_MARKER,
                            File(root, "dispatch-denied.marker").path)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    MidletKeepAliveService.exitEmulator(context)
                }
                // Returning would let AndroidJUnitRunner force-stop the app before its asynchronous
                // shutdown callback runs. Only the production shutdown may end this invocation.
                SystemClock.sleep(30_000L)
                fail("Emulator shutdown did not terminate instrumentation")
            }
            "verify" -> {
                assertTrue("No committed fixture backup", backup.contains("hadWorkdir"))
                if (backup.getString("action", null) == "fallback") {
                    assertTrue("Runtime must have exercised dispatch failure",
                        File(root, "dispatch-denied.marker").isFile)
                }
                assertNull(MidletSessionStore.read(context))
                assertFalse(RuntimeStorageLease.isActive(context.filesDir, appDir))
                val session = ProcessExitStore.findSession(context, backup.getString("session", null))
                assertNotNull(session)
                assertEquals(MidletSessionJournal.Stage.COMPLETED, session!!.stage)
                assertEquals(MidletSessionJournal.Outcome.USER_STOP, session.outcome)
                val previousReports = backup.getStringSet("reports", emptySet())!!
                assertTrue("User shutdown must not create a diagnostic report",
                    LocalDiagnosticRepository.load(context).all { it.id in previousReports })
                context.startActivity(Intent(context, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CrashRuntimeIsolationTest.awaitActivityOnTop(context, MainActivity::class.java)
            }
            "cleanup" -> cleanupFixture(context)
        }
    }

    private fun cleanupFixture(context: Context) {
        val backup = context.getSharedPreferences("runtime-shutdown-fixture", Context.MODE_PRIVATE)
        // This marker is committed before setup touches a preference, directory, or runtime.
        // A missing session marker does not disown an early failed setup; a missing backup does.
        if (!backup.contains("hadWorkdir")) return
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = preferences.edit()
        if (backup.getBoolean("hadWorkdir", false)) {
            editor.putString(Constants.PREF_EMULATOR_DIR,
                checkNotNull(backup.getString("workdir", null)) { "Missing workdir backup" })
        } else {
            editor.remove(Constants.PREF_EMULATOR_DIR)
        }
        assertTrue("Unable to restore workdir preference; fixture backup preserved", editor.commit())
        val root = File(context.filesDir, "runtime-shutdown-fixture")
        val state = MidletSessionStore.read(context)
        if (state?.appPath == File(root, "converted/fixture").path) {
            MidletSessionStore.clear(context, state.generation)
        }
        CrashRuntimeIsolationTest.deleteRecursively(root)
        assertFalse("Unable to remove fixture files; backup preserved", root.exists())
        assertTrue("Unable to clear restored fixture backup", backup.edit().clear().commit())
    }

    @Test
    fun cleanupWithoutCommittedOwnershipDoesNotTouchState() = withIsolatedFixture { context ->
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val root = File(context.filesDir, "runtime-shutdown-fixture").apply { mkdirs() }
        val untouched = File(root, "untouched").apply { writeText("keep") }
        assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, "original").commit())
        MidletSessionStore.markStarted(context, File(root, "converted/fixture").path,
            "Existing", "fixture.Main", 1L, "unowned-runtime")
        cleanupFixture(context)
        assertEquals("original", preferences.getString(Constants.PREF_EMULATOR_DIR, null))
        assertEquals("keep", untouched.readText())
        assertEquals("unowned-runtime", MidletSessionStore.read(context)!!.generation)
    }

    @Test
    fun earlySetupCleanupRestoresPreferenceAndPreservesUnrelatedRuntime() {
        for (hadWorkdir in listOf(false, true)) withIsolatedFixture { context ->
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val backup = context.getSharedPreferences("runtime-shutdown-fixture", Context.MODE_PRIVATE)
            val root = File(context.filesDir, "runtime-shutdown-fixture").apply { mkdirs() }
            File(root, "partial-setup").writeText("fixture")
            val outside = File(context.filesDir, "user-data").apply { writeText("keep") }
            assertTrue(backup.edit().putBoolean("hadWorkdir", hadWorkdir)
                .putString("workdir", if (hadWorkdir) "original" else null).commit())
            assertFalse(backup.contains("session"))
            assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.path)
                .putString("unrelated-setting", "keep").commit())
            MidletSessionStore.markStarted(context, "unrelated-app", "Existing",
                "unrelated.Main", 1L, "unrelated-runtime")
            cleanupFixture(context)
            assertEquals(if (hadWorkdir) "original" else null,
                preferences.getString(Constants.PREF_EMULATOR_DIR, null))
            assertEquals("keep", preferences.getString("unrelated-setting", null))
            assertEquals("keep", outside.readText())
            assertEquals("unrelated-runtime", MidletSessionStore.read(context)!!.generation)
            assertFalse(root.exists())
            assertTrue(backup.all.isEmpty())
        }
    }

    @Test
    fun failedPreferenceRestorationPreservesOwnedFixtureForRetry() = withIsolatedFixture { context ->
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val backup = context.getSharedPreferences("runtime-shutdown-fixture", Context.MODE_PRIVATE)
        val root = File(context.filesDir, "runtime-shutdown-fixture").apply { mkdirs() }
        assertTrue(backup.edit().putBoolean("hadWorkdir", true).putString("workdir", "original").commit())
        assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.path).commit())
        MidletSessionStore.markStarted(context, File(root, "converted/fixture").path,
            "Fixture", "fixture.Main", 1L, "owned-runtime")
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val stored = super.getSharedPreferences(name, mode)
                if (stored !== preferences) return stored
                return object : SharedPreferences by stored {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = stored.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit() = false
                        }
                    }
                }
            }
        }
        var restorationFailed = false
        try {
            cleanupFixture(failingContext)
        } catch (expected: AssertionError) {
            restorationFailed = true
        }
        assertTrue("Restoration failure must be reported", restorationFailed)
        assertEquals(root.path, preferences.getString(Constants.PREF_EMULATOR_DIR, null))
        assertTrue(backup.contains("hadWorkdir"))
        assertTrue(root.exists())
        assertEquals("owned-runtime", MidletSessionStore.read(context)!!.generation)
        cleanupFixture(context)
        assertEquals("original", preferences.getString(Constants.PREF_EMULATOR_DIR, null))
        assertNull(MidletSessionStore.read(context))
        assertFalse(root.exists())
        assertTrue(backup.all.isEmpty())
    }

    private fun withIsolatedFixture(test: (Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = TemporaryFolder(target.cacheDir).apply { create() }
        val prefix = "runtime-cleanup-test-${UUID.randomUUID()}"
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory.root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$prefix-$name"
                preferenceNames.add(isolatedName)
                return target.getSharedPreferences(isolatedName, mode)
            }
        }
        try {
            test(context)
        } finally {
            for (name in preferenceNames) {
                target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
            directory.delete()
        }
    }
}
