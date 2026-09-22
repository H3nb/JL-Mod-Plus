/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import io.github.h3nb.jlmodplus.util.Constants
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.shell.MicroActivity
import javax.microedition.shell.PresetAuthorityProbeService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real two-process coverage for the runtime preset authority boundary.
 *
 * The test body runs in the default/main process. The debug-only probe is declared in :midlet and
 * invokes the production PresetAuthorityClient, which synchronously crosses back to the
 * non-exported main-process ContentProvider.
 */
@RunWith(AndroidJUnit4::class)
class PresetAuthorityIpcRuntimeTest {
    companion object {
        private const val STORAGE_KEY = "fixture"
        private const val TIMEOUT_MILLIS = 10_000L
    }

    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var root: File
    private lateinit var appDir: File
    private lateinit var configDir: File
    private lateinit var profilesRoot: File
    private var appId: Long = 0L
    private var probe: ProbeConnection? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        stopMidletProcess()
        root = File(context.filesDir, "preset-authority-ipc-fixture")
        deleteRecursively(root)
        appDir = File(root, "converted/$STORAGE_KEY")
        configDir = File(root, "configs/$STORAGE_KEY")
        profilesRoot = File(root, "templates")
        assertTrue(appDir.mkdirs())
        assertTrue(configDir.mkdirs())
        assertTrue(profilesRoot.mkdirs())
        writeConfig(configDir, 240)
        clearOwnership()

        val database = LibraryDatabase.open(context, root)
        try {
            appId = runBlocking {
                database.libraryDao().insertApp(
                    LibraryAppEntity(
                        storageKey = STORAGE_KEY,
                        sourceTitle = "Fixture",
                        sourceVendor = "JL-Mod Plus",
                        sourceVersion = "1.0",
                    ),
                )
            }
        } finally {
            database.close()
        }
        assertTrue(appId > 0L)
    }

    @After
    fun tearDown() {
        probe?.close()
        probe = null
        stopMidletProcess()
        MidletSessionStoreCompat.clear(context)
        clearOwnership()
        deleteRecursively(root)
    }

    @Test
    fun runtimeLocalOnlyDetachIsImmediatelyVisibleToMainProcess() {
        preset("K800i", 176, validLayout(3))
        assertTrue(PresetLinkage(preferences, configDir).linkTo("K800i"))
        val runtimeId = handshake()
        val payload = validLayout(4)

        val result = probe().request(
            PresetAuthorityProbeService.MSG_SAVE,
            request(runtimeId).apply {
                putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, payload)
            },
        )

        assertTrue(result.getBoolean(PresetAuthorityProbeService.KEY_LAYOUT_COMMITTED))
        assertArrayEquals(payload, layoutFile(configDir).readBytes())
        val linkage = PresetLinkage(preferences, configDir)
        assertFalse(linkage.isLinked)
        assertEquals("K800i", linkage.origin)
    }

    @Test
    fun mainLoadAfterRuntimeLocalSaveDoesNotOverwriteDetachedLayout() {
        val sourceLayout = validLayout(3)
        preset("K800i", 176, sourceLayout)
        assertTrue(PresetLinkage(preferences, configDir).linkTo("K800i"))
        val runtimeId = handshake()
        val localLayout = validLayout(5)

        val result = probe().request(
            PresetAuthorityProbeService.MSG_SAVE,
            request(runtimeId).apply {
                putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, localLayout)
            },
        )
        assertTrue(result.getBoolean(PresetAuthorityProbeService.KEY_LAYOUT_COMMITTED))

        assertTrue(MidletConfigLoadBoundary.prepare(preferences, configDir, profilesRoot))

        assertArrayEquals(localLayout, layoutFile(configDir).readBytes())
        assertFalse(PresetLinkage(preferences, configDir).isLinked)
    }

    @Test
    fun pendingRuntimeUpdateCannotFollowRenamedOldTarget() {
        val before = validLayout(3)
        preset("K800i", 176, before)
        assertTrue(PresetLinkage(preferences, configDir).linkTo("K800i"))
        val runtimeId = handshake()

        val resolved = probe().request(
            PresetAuthorityProbeService.MSG_RESOLVE,
            request(runtimeId),
        ).getString(PresetAuthorityContract.KEY_UPDATE_TARGET)
        assertEquals("K800i", resolved)

        assertEquals(
            PresetLifecycle.Result.SUCCESS,
            PresetLifecycle.rename(preferences, profilesRoot, "K800i", "Sony K800i"),
        )
        val renamed = File(profilesRoot, "Sony K800i")
        val renamedBefore = layoutFile(renamed).readBytes()
        val local = validLayout(4)

        val result = probe().request(
            PresetAuthorityProbeService.MSG_SAVE,
            request(runtimeId).apply {
                putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, local)
                putString(PresetAuthorityContract.KEY_UPDATE_TARGET, resolved)
            },
        )

        assertTrue(result.getBoolean(PresetAuthorityProbeService.KEY_LAYOUT_COMMITTED))
        assertEquals(
            PresetAuthorityContract.UPDATE_FAILED,
            result.getInt(PresetAuthorityContract.KEY_UPDATE_OUTCOME),
        )
        assertFalse(File(profilesRoot, "K800i").exists())
        assertArrayEquals(renamedBefore, layoutFile(renamed).readBytes())
        assertArrayEquals(local, layoutFile(configDir).readBytes())
        val linkage = PresetLinkage(preferences, configDir)
        assertEquals("Sony K800i", linkage.origin)
        assertFalse(linkage.isLinked)
    }

    @Test
    fun staleRuntimeIdentityCannotModifyReplacementApp() {
        preset("K800i", 176, validLayout(3))
        val runtimeId = handshake()
        assertEquals(appId, runtimeId)

        val database = LibraryDatabase.open(context, root)
        val replacementId: Long
        try {
            replacementId = runBlocking {
                database.libraryDao().deleteAppByStorageKey(STORAGE_KEY)
                database.libraryDao().insertApp(
                    LibraryAppEntity(
                        storageKey = STORAGE_KEY,
                        sourceTitle = "Replacement",
                        sourceVendor = "JL-Mod Plus",
                        sourceVersion = "2.0",
                    ),
                )
            }
        } finally {
            database.close()
        }
        assertNotEquals(runtimeId, replacementId)

        val replacementLayout = validLayout(5)
        layoutFile(configDir).writeBytes(replacementLayout)
        assertTrue(PresetLinkage(preferences, configDir).linkTo("K800i"))

        val stalePrepare = probe().request(
            PresetAuthorityProbeService.MSG_PREPARE,
            request(runtimeId),
        )
        assertEquals(
            PresetAuthorityContract.RESULT_STALE,
            stalePrepare.getInt(PresetAuthorityContract.KEY_RESULT),
        )

        val save = probe().request(
            PresetAuthorityProbeService.MSG_SAVE,
            request(runtimeId).apply {
                putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, validLayout(4))
            },
        )

        assertFalse(save.getBoolean(PresetAuthorityProbeService.KEY_LAYOUT_COMMITTED))
        assertArrayEquals(replacementLayout, layoutFile(configDir).readBytes())
        assertTrue(PresetLinkage(preferences, configDir).isLinked)
    }

    @Test
    fun midletRuntimeDoesNotRecoverMainProcessLivePublication() {
        val oldLayout = validLayout(3)
        val newLayout = validLayout(4)
        layoutFile(configDir).writeBytes(oldLayout)
        val source = preset("Live", 176, newLayout)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val writerFailure = AtomicReference<Throwable?>()

        val writer = Thread {
            try {
                ProfilesManager.load(source, configDir, false, true) {
                    publicationEntered.countDown()
                    try {
                        if (!releasePublication.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            throw IOException("Timed out holding live local publication")
                        }
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException(interrupted)
                    }
                }
            } catch (failure: Throwable) {
                writerFailure.set(failure)
            }
        }
        writer.start()
        assertTrue(publicationEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val rollback = File(configDir, ".preset-sync.rollback")
        val ready = File(rollback, ".ready")
        assertTrue(ready.isFile)
        assertArrayEquals(newLayout, layoutFile(configDir).readBytes())

        val launch = Intent(
            Intent.ACTION_DEFAULT,
            Uri.parse(appDir.absolutePath),
            context,
            MicroActivity::class.java,
        ).apply {
            putExtra(Constants.KEY_MIDLET_NAME, "Authority Fixture")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(launch)
        awaitMidletProcess()
        // The activity main thread has ample time to reach the synchronous provider call. The
        // provider is blocked by the live main-process PRESET_SOURCE_LOCK held above.
        SystemClock.sleep(250L)

        assertTrue(ready.isFile)
        assertArrayEquals(newLayout, layoutFile(configDir).readBytes())

        releasePublication.countDown()
        writer.join(TIMEOUT_MILLIS)
        assertFalse(writer.isAlive)
        writerFailure.get()?.let { throw AssertionError(it) }
    }

    private fun handshake(): Long {
        val result = probe().request(
            PresetAuthorityProbeService.MSG_PREPARE,
            request(0L),
        )
        assertEquals(
            PresetAuthorityContract.RESULT_OK,
            result.getInt(PresetAuthorityContract.KEY_RESULT),
        )
        val resolved = result.getLong(PresetAuthorityContract.KEY_APP_ID)
        assertEquals(appId, resolved)
        return resolved
    }

    private fun request(expectedAppId: Long): Bundle =
        Bundle().apply {
            putString(PresetAuthorityContract.KEY_APP_PATH, appDir.absolutePath)
            putLong(PresetAuthorityContract.KEY_EXPECTED_APP_ID, expectedAppId)
        }

    private fun probe(): ProbeConnection {
        val existing = probe
        if (existing != null) return existing
        return ProbeConnection(context).also {
            it.bind()
            probe = it
        }
    }

    private fun preset(name: String, width: Int, layout: ByteArray): File {
        val dir = File(profilesRoot, name)
        assertTrue(dir.mkdir())
        writeConfig(dir, width)
        layoutFile(dir).writeBytes(layout)
        return dir
    }

    private fun writeConfig(dir: File, width: Int) {
        if (!dir.isDirectory) assertTrue(dir.mkdirs())
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

    private fun clearOwnership() {
        if (!::configDir.isInitialized) return
        PresetLinkage(preferences, configDir).clear()
        preferences.edit()
            .remove(ProfileModel.builtInThemePreferenceKey(configDir))
            .commit()
    }

    private fun layoutFile(dir: File) = File(dir, Config.MIDLET_KEY_LAYOUT_FILE)

    private fun validLayout(type: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x564B4C00)
            output.writeInt(4)
            output.writeInt(3)
            output.writeInt(1)
            output.writeByte(type)
            output.writeInt(-1)
            output.writeInt(0)
        }
        return bytes.toByteArray()
    }

    private fun awaitMidletProcess() {
        val processName = context.packageName + ":midlet"
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (processPid(processName) != 0) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("MIDlet process did not start")
    }

    private fun stopMidletProcess() {
        probe?.close()
        probe = null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.appTasks?.forEach { task ->
            val info = task.taskInfo ?: return@forEach
            if (isMicroActivity(info.baseActivity) || isMicroActivity(info.topActivity)) {
                try {
                    task.finishAndRemoveTask()
                } catch (_: RuntimeException) {
                }
            }
        }
        val pid = processPid(context.packageName + ":midlet")
        if (pid != 0) Process.killProcess(pid)
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (processPid(context.packageName + ":midlet") != 0 &&
            SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50L)
        }
    }

    private fun processPid(processName: String): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0
        return manager.runningAppProcesses
            ?.firstOrNull { it.processName == processName }
            ?.pid ?: 0
    }

    private fun isMicroActivity(component: ComponentName?): Boolean =
        component?.className == MicroActivity::class.java.name

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        file.listFiles()?.forEach(::deleteRecursively)
        if (!file.delete() && file.exists()) {
            throw AssertionError("Unable to delete fixture path: $file")
        }
    }

    private class ProbeConnection(private val context: Context) {
        private val connected = CountDownLatch(1)
        private val replies = LinkedBlockingQueue<Bundle>()
        private val replyMessenger = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                replies.offer(Bundle(message.data))
                true
            },
        )
        private var remote: Messenger? = null
        private var connection: ServiceConnection? = null

        fun bind() {
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    remote = Messenger(binder)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    remote = null
                }

                override fun onBindingDied(name: ComponentName) {
                    remote = null
                }

                override fun onNullBinding(name: ComponentName) {
                    remote = null
                    connected.countDown()
                }
            }
            connection = serviceConnection
            assertTrue(
                context.bindService(
                    Intent(context, PresetAuthorityProbeService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE,
                ),
            )
            assertTrue(connected.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertNotNull(remote)
        }

        fun request(what: Int, data: Bundle): Bundle {
            replies.clear()
            val message = Message.obtain().apply {
                this.what = what
                this.data = data
                replyTo = replyMessenger
            }
            remote!!.send(message)

            val entered = replies.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("No :midlet probe entry acknowledgement")
            assertEquals(
                PresetAuthorityProbeService.PHASE_ENTERED,
                entered.getInt(PresetAuthorityProbeService.KEY_PHASE),
            )
            assertNotEquals(Process.myPid(), entered.getInt(PresetAuthorityProbeService.KEY_REMOTE_PID))

            val result = replies.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("No :midlet probe result")
            assertEquals(
                PresetAuthorityProbeService.PHASE_RESULT,
                result.getInt(PresetAuthorityProbeService.KEY_PHASE),
            )
            assertEquals(
                entered.getInt(PresetAuthorityProbeService.KEY_REMOTE_PID),
                result.getInt(PresetAuthorityProbeService.KEY_REMOTE_PID),
            )
            return result
        }

        fun close() {
            val current = connection ?: return
            try {
                context.unbindService(current)
            } catch (_: IllegalArgumentException) {
            }
            connection = null
            remote = null
        }
    }

    /**
     * Keeps this test independent from the crash package implementation details while still
     * clearing a runtime marker left by a fixture activity that progressed after a test failure.
     */
    private object MidletSessionStoreCompat {
        fun clear(context: Context) {
            io.github.h3nb.jlmodplus.crashes.MidletSessionStore.clear(context)
        }
    }
}
