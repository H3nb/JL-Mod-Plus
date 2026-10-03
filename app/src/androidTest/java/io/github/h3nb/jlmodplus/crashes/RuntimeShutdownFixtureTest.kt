// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.crashes

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in fixture for the external smoke test: shutdown deliberately kills instrumentation. */
@RunWith(AndroidJUnit4::class)
class RuntimeShutdownFixtureTest {
    @Test
    fun externalShutdownFixture() {
        val command = InstrumentationRegistry.getArguments().getString("runtimeShutdownFixture")
        assumeTrue(command in setOf("remove", "exit", "verify"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val backup = context.getSharedPreferences("runtime-shutdown-fixture", Context.MODE_PRIVATE)
        val root = File(context.filesDir, "runtime-shutdown-fixture")
        val appDir = File(root, "converted/fixture")
        when (command) {
            "remove", "exit" -> {
                assertFalse("Verify or clean up the previous fixture first", backup.contains("session"))
                assertTrue(backup.edit()
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
                } else {
                    MidletKeepAliveService.exitEmulator(context)
                }
                // Returning would let AndroidJUnitRunner force-stop the app before its asynchronous
                // shutdown callback runs. Only the production shutdown may end this invocation.
                SystemClock.sleep(30_000L)
                fail("Emulator shutdown did not terminate instrumentation")
            }
            "verify" -> {
                try {
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
                } finally {
                    val editor = preferences.edit()
                    if (backup.getBoolean("hadWorkdir", false)) {
                        editor.putString(Constants.PREF_EMULATOR_DIR, backup.getString("workdir", null))
                    } else {
                        editor.remove(Constants.PREF_EMULATOR_DIR)
                    }
                    editor.commit()
                    CrashRuntimeIsolationTest.deleteRecursively(root)
                    backup.edit().clear().commit()
                }
            }
        }
    }
}
