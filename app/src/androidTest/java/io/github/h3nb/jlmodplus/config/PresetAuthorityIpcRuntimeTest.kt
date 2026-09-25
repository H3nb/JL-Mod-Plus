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
    private lateinit var root: File
    private lateinit var appDir: File
    private lateinit var configDir: File
    private lateinit var profilesRoot: File
    private var appId: Long = 0L
    private var probe: ProbeConnection? = null
    private var mainProbe: MainProbeConnection? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        stopMidletProcess()
        assertTrue(
            context.getSharedPreferences("runtime_ui_preferences", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit(),
        )
        root = File(context.filesDir, "preset-authority-ipc-fixture")
        deleteRecursively(root)
        appDir = File(root, "converted/$STORAGE_KEY")
        configDir = File(root, "configs/$STORAGE_KEY")
        profilesRoot = File(root, "templates")
        assertTrue(appDir.mkdirs())
        assertTrue(configDir.mkdirs())
        assertTrue(profilesRoot.mkdirs())
        writeConfig(configDir, 240)
        assertMainSuccess(PresetAuthorityMainProbeService.MSG_RESET_OWNERSHIP)

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
        if (::appDir.isInitialized && appDir.exists()) {
            assertMainSuccess(PresetAuthorityMainProbeService.MSG_RESET_OWNERSHIP)
        }
        mainProbe?.close()
        mainProbe = null
        deleteRecursively(root)
    }

    @Test
    fun runtimeLocalOnlyDetachIsImmediatelyVisibleToMainProcess() {
        preset("K800i", 176, validLayout(3))
        linkInMain("K800i")
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
        val linkage = readMainOwnership()
        assertFalse(linkage.getBoolean(PresetAuthorityMainProbeService.KEY_LINKED))
        assertEquals("K800i", linkage.getString(PresetAuthorityMainProbeService.KEY_ORIGIN))
    }

    @Test
    fun runtimeUpdatePublishesOnlyLayoutAndRelinksCurrentApp() {
        val source = preset("K800i", 176, validLayout(3))
        val sourceConfig = File(source, Config.MIDLET_CONFIG_FILE).readBytes()
        linkInMain("K800i")
        val runtimeId = handshake()
        writeConfig(configDir, 640)
        val payload = validLayout(5)

        val result = probe().request(
            PresetAuthorityProbeService.MSG_SAVE,
            request(runtimeId).apply {
                putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, payload)
                putString(PresetAuthorityContract.KEY_UPDATE_TARGET, "K800i")
            },
        )

        assertEquals(
            PresetAuthorityContract.RESULT_OK,
            result.getInt(PresetAuthorityContract.KEY_RESULT),
        )
        assertEquals(
            PresetAuthorityContract.UPDATE_LINKED,
            result.getInt(PresetAuthorityContract.KEY_UPDATE_OUTCOME),
        )
        assertArrayEquals(payload, layoutFile(source).readBytes())
        assertArrayEquals(sourceConfig, File(source, Config.MIDLET_CONFIG_FILE).readBytes())
        assertEquals(640, ProfilesManager.loadPreparedMidletConfig(configDir, false)!!.screenWidth)
        assertTrue(readMainOwnership().getBoolean(PresetAuthorityMainProbeService.KEY_LINKED))
    }

    @Test
    fun runtimeLayoutUpdateKeepsCustomMidletSettingsAcrossNextLoad() {
        val source = preset("K800i", 176, validLayout(3))
        val sourceConfig = File(source, Config.MIDLET_CONFIG_FILE).readBytes()
        linkInMain("K800i")
        val runtimeId = handshake()
        val localOnly = probe().request(
            PresetAuthorityProbeService.MSG_SAVE,
            request(runtimeId).apply {
                putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, validLayout(4))
            },
        )
        assertTrue(localOnly.getBoolean(PresetAuthorityProbeService.KEY_LAYOUT_COMMITTED))
        writeConfig(configDir, 640)
        val localConfig = File(configDir, Config.MIDLET_CONFIG_FILE).readBytes()
        val updatedLayout = validLayout(5)

        val update = probe().request(
            PresetAuthorityProbeService.MSG_SAVE,
            request(runtimeId).apply {
                putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, updatedLayout)
                putString(PresetAuthorityContract.KEY_UPDATE_TARGET, "K800i")
            },
        )

        assertEquals(PresetAuthorityContract.RESULT_OK,
            update.getInt(PresetAuthorityContract.KEY_RESULT))
        assertEquals(PresetAuthorityContract.UPDATE_SAVED_UNLINKED,
            update.getInt(PresetAuthorityContract.KEY_UPDATE_OUTCOME))
        assertArrayEquals(sourceConfig, File(source, Config.MIDLET_CONFIG_FILE).readBytes())
        assertArrayEquals(updatedLayout, layoutFile(source).readBytes())
        assertFalse(readMainOwnership().getBoolean(PresetAuthorityMainProbeService.KEY_LINKED))
        assertMainSuccess(PresetAuthorityMainProbeService.MSG_PREPARE_MAIN_LOAD)
        assertArrayEquals(localConfig, File(configDir, Config.MIDLET_CONFIG_FILE).readBytes())
    }

    @Test
    fun mainLoadAfterRuntimeLocalSaveDoesNotOverwriteDetachedLayout() {
        val sourceLayout = validLayout(3)
        preset("K800i", 176, sourceLayout)
        linkInMain("K800i")
        val runtimeId = handshake()
        val localLayout = validLayout(5)

        val result = probe().request(
            PresetAuthorityProbeService.MSG_SAVE,
            request(runtimeId).apply {
                putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, localLayout)
            },
        )
        assertTrue(result.getBoolean(PresetAuthorityProbeService.KEY_LAYOUT_COMMITTED))

        assertMainSuccess(PresetAuthorityMainProbeService.MSG_PREPARE_MAIN_LOAD)

        assertArrayEquals(localLayout, layoutFile(configDir).readBytes())
        assertFalse(readMainOwnership().getBoolean(PresetAuthorityMainProbeService.KEY_LINKED))
    }

    @Test
    fun pendingRuntimeUpdateCannotFollowRenamedOldTarget() {
        val before = validLayout(3)
        preset("K800i", 176, before)
        linkInMain("K800i")
        val runtimeId = handshake()

        val resolved = probe().request(
            PresetAuthorityProbeService.MSG_RESOLVE,
            request(runtimeId),
        ).getString(PresetAuthorityContract.KEY_UPDATE_TARGET)
        assertEquals("K800i", resolved)

        assertMainSuccess(
            PresetAuthorityMainProbeService.MSG_RENAME,
            Bundle().apply {
                putString(PresetAuthorityMainProbeService.KEY_PRESET_NAME, "K800i")
                putString(PresetAuthorityMainProbeService.KEY_NEW_PRESET_NAME, "Sony K800i")
            },
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
        val linkage = readMainOwnership()
        assertEquals(
            "Sony K800i",
            linkage.getString(PresetAuthorityMainProbeService.KEY_ORIGIN),
        )
        assertFalse(linkage.getBoolean(PresetAuthorityMainProbeService.KEY_LINKED))
    }

    @Test
    fun staleRuntimeIdentityCannotModifyReplacementApp() {
        val sourceLayout = validLayout(3)
        val source = preset("K800i", 176, sourceLayout)
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
        linkInMain("K800i")

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
                putString(PresetAuthorityContract.KEY_UPDATE_TARGET, "K800i")
            },
        )

        assertFalse(save.getBoolean(PresetAuthorityProbeService.KEY_LAYOUT_COMMITTED))
        assertEquals(
            PresetAuthorityContract.RESULT_STALE,
            save.getInt(PresetAuthorityContract.KEY_RESULT),
        )
        assertArrayEquals(replacementLayout, layoutFile(configDir).readBytes())
        assertArrayEquals(sourceLayout, layoutFile(source).readBytes())
        assertTrue(readMainOwnership().getBoolean(PresetAuthorityMainProbeService.KEY_LINKED))
    }

    @Test
    fun runtimeUiPreferenceWriteCannotRewriteMainPresetOwnership() {
        preset("K800i", 176, validLayout(3))
        probe() // Start :midlet and populate its stale default-preference cache first.
        linkInMain("K800i", setDefault = true)

        val result = probe().request(
            PresetAuthorityProbeService.MSG_WRITE_RUNTIME_PREFERENCE,
            Bundle(),
        )

        assertEquals(
            PresetAuthorityContract.RESULT_OK,
            result.getInt(PresetAuthorityContract.KEY_RESULT),
        )
        val linkage = readMainOwnership()
        assertEquals("K800i", linkage.getString(PresetAuthorityMainProbeService.KEY_ORIGIN))
        assertTrue(linkage.getBoolean(PresetAuthorityMainProbeService.KEY_LINKED))
        assertEquals(
            "K800i",
            linkage.getString(PresetAuthorityMainProbeService.KEY_DEFAULT_PROFILE),
        )
        assertTrue(result.getBoolean(PresetAuthorityProbeService.KEY_RUNTIME_PREFERENCE_VALUE))
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

    private fun linkInMain(name: String, setDefault: Boolean = false) {
        assertMainSuccess(
            PresetAuthorityMainProbeService.MSG_LINK,
            Bundle().apply {
                putString(PresetAuthorityMainProbeService.KEY_PRESET_NAME, name)
                putBoolean(PresetAuthorityMainProbeService.KEY_SET_DEFAULT, setDefault)
            },
        )
    }

    private fun readMainOwnership(): Bundle {
        val result = mainProbe().request(
            PresetAuthorityMainProbeService.MSG_READ,
            request(0L),
        )
        assertEquals(
            PresetAuthorityContract.RESULT_OK,
            result.getInt(PresetAuthorityContract.KEY_RESULT),
        )
        return result
    }

    private fun assertMainSuccess(what: Int, data: Bundle = Bundle()) {
        data.putString(PresetAuthorityContract.KEY_APP_PATH, appDir.absolutePath)
        val result = mainProbe().request(what, data)
        assertEquals(
            PresetAuthorityContract.RESULT_OK,
            result.getInt(PresetAuthorityContract.KEY_RESULT),
        )
    }

    private fun mainProbe(): MainProbeConnection {
        val existing = mainProbe
        if (existing != null) return existing
        return MainProbeConnection(context).also {
            it.bind()
            mainProbe = it
        }
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

    private class MainProbeConnection(private val context: Context) {
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
                    Intent(context, PresetAuthorityMainProbeService::class.java),
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
            val result = replies.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("No main-process probe result")
            assertEquals(context.packageName, result.getString(PresetAuthorityMainProbeService.KEY_PROCESS_NAME))
            assertTrue(result.getInt(PresetAuthorityMainProbeService.KEY_REMOTE_PID) > 0)
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
            assertEquals(
                context.packageName + ":midlet",
                entered.getString(PresetAuthorityProbeService.KEY_PROCESS_NAME),
            )
            assertFalse(entered.getBoolean(PresetAuthorityProbeService.KEY_PROCESS_IS_MAIN))

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
