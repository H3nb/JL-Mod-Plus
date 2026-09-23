/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.installer

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.util.Base64
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.config.ProfileModel
import io.github.h3nb.jlmodplus.config.ProfilesManager
import io.github.h3nb.jlmodplus.crashes.MidletSessionStore
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import io.github.h3nb.jlmodplus.librarydb.LibraryFileOperations
import io.github.h3nb.jlmodplus.librarydb.LibraryBootstrapState
import io.github.h3nb.jlmodplus.librarydb.LibraryStateEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryViewModel
import io.github.h3nb.jlmodplus.runtime.RuntimeStorageLease
import io.github.h3nb.jlmodplus.util.Constants
import io.reactivex.Single
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.microedition.shell.MicroActivity
import jlmod.runtimefixture.LifecycleMidlet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeStorageLeaseInstrumentedTest {
    @Test fun deletedLiveRuntimeCannotShareFreshInstallStorage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val oldWorkdir = preferences.getString(Constants.PREF_EMULATOR_DIR, null)
        val root = File(app.filesDir, "runtime-storage-lease-test")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val oldApp = File(root, "converted/Bounce")
        val config = File(root, "configs/Bounce")
        val started = File(root, "started.marker")
        val trigger = File(root, "write.trigger")
        val written = File(root, "written.marker")
        assertTrue(oldApp.mkdirs())
        assertTrue(config.mkdirs())
        File(app.applicationInfo.sourceDir).copyTo(File(oldApp, "converted.zip"))
        assertTrue(ProfilesManager.saveConfig(ProfileModel(config)))
        File(oldApp, "converted.dex.conf").writeText(
            "Manifest-Version: 1.0\n" +
                "MIDlet-Name: Bounce\nMIDlet-Vendor: Tests\nMIDlet-Version: 1.0\n" +
                "MIDlet-1: Bounce,,${LifecycleMidlet.CLASS_NAME}\n" +
                "${LifecycleMidlet.MODE_PROPERTY}: ${LifecycleMidlet.MODE_STORAGE_LEASE}\n" +
                "${LifecycleMidlet.MARKER_PROPERTY}: ${started.absolutePath}\n" +
                "${LifecycleMidlet.STORAGE_TRIGGER_PROPERTY}: ${trigger.absolutePath}\n" +
                "${LifecycleMidlet.STORAGE_WRITTEN_PROPERTY}: ${written.absolutePath}\n",
        )
        val database = LibraryDatabase.open(app, root)
        val oldId = try {
            database.libraryDao().setLibraryState(
                LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY),
            )
            database.libraryDao().insertApp(
                LibraryAppEntity(
                    storageKey = "Bounce",
                    sourceTitle = "Bounce",
                    sourceVendor = "Tests",
                    sourceVersion = "1.0",
                ),
            )
        } finally {
            database.close()
        }
        val store = ViewModelStore()
        lateinit var library: LibraryViewModel
        try {
            instrumentation.runOnMainSync {
                assertTrue(preferences.edit()
                    .putString(Constants.PREF_EMULATOR_DIR, root.absolutePath).commit())
                library = ViewModelProvider(
                    store, ViewModelProvider.AndroidViewModelFactory(app),
                )[LibraryViewModel::class.java]
                library.setEmulatorDirectory(root.absolutePath)
            }
            val readyDeadline = System.currentTimeMillis() + 30_000
            while (library.readyGeneration() == null && System.currentTimeMillis() < readyDeadline) {
                delay(20)
            }
            assertTrue("Library did not become ready: ${library.displayState.value}",
                library.readyGeneration() != null)
            assertTrue(oldId > 0L)
            app.startActivity(Intent(Intent.ACTION_DEFAULT, Uri.parse(oldApp.absolutePath),
                app, MicroActivity::class.java)
                .putExtra(Constants.KEY_MIDLET_NAME, "Bounce")
                .putExtra(Constants.KEY_LIBRARY_APP_ID, oldId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            awaitFile(started)
            assertTrue(RuntimeStorageLease.isActive(app.filesDir, oldApp))

            val deleted = CompletableDeferred<LibraryFileOperations.DeleteResult>()
            library.deleteInstalledApp(oldId) { result, error ->
                if (error != null) deleted.completeExceptionally(error)
                else deleted.complete(requireNotNull(result))
            }
            withTimeout(20_000) { deleted.await() }
            assertFalse(oldApp.exists())
            assertFalse(config.exists())
            assertTrue(RuntimeStorageLease.isActive(app.filesDir, oldApp))

            val jar = File(root, "Bounce.jar")
            val manifest = "Manifest-Version: 1.0\nMIDlet-Name: Bounce\n" +
                "MIDlet-Vendor: Tests\nMIDlet-Version: 1.0\nMIDlet-1: Bounce,,Fixture\n"
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("Fixture.class"))
                zip.write(Base64.decode(CLASS_BYTES, Base64.DEFAULT))
                zip.closeEntry()
            }
            val installer = AppInstaller(jar, Uri.fromFile(jar), library)
            assertEquals(AppInstaller.STATUS_NEW, Single.create<Int>(installer::loadInfo).blockingGet())
            assertEquals(AppInstaller.STATUS_SUCCESS, Single.create<Int>(installer::install).blockingGet())
            val newApp = File(installer.installedPath)
            assertNotEquals(oldId, installer.installedId)
            assertEquals("Bounce_1", newApp.name)
            assertTrue(RuntimeStorageLease.isActive(app.filesDir, oldApp))

            // A lease reserves a path against a new identity, not a reinstall of its owner.
            RuntimeStorageLease.acquire(app.filesDir, newApp).use { sameIdentityLease ->
                assertTrue(sameIdentityLease != null)
                val reinstall = AppInstaller(installer.installedId,
                    requireNotNull(library.readyGeneration()).generation,
                    root, newApp.name, library)
                assertEquals(AppInstaller.STATUS_EQUAL,
                    Single.create<Int>(reinstall::loadInfo).blockingGet())
                assertEquals(AppInstaller.STATUS_SUCCESS,
                    Single.create<Int>(reinstall::install).blockingGet())
                assertEquals(newApp.absolutePath, reinstall.installedPath)
                assertEquals(installer.installedId, reinstall.installedId)
            }

            val lateJar = File(root, "Late.jar")
            ZipOutputStream(lateJar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write(manifest.replace("Bounce", "Late").toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("Fixture.class"))
                zip.write(Base64.decode(CLASS_BYTES, Base64.DEFAULT))
                zip.closeEntry()
            }
            val lateInstaller = AppInstaller(lateJar, Uri.fromFile(lateJar), library)
            assertEquals(AppInstaller.STATUS_NEW,
                Single.create<Int>(lateInstaller::loadInfo).blockingGet())
            RuntimeStorageLease.acquire(app.filesDir, File(root, "converted/Late")).use { lateLease ->
                assertTrue(lateLease != null)
                assertEquals(AppInstaller.STATUS_SUCCESS,
                    Single.create<Int>(lateInstaller::install).blockingGet())
                assertEquals("Late_1", File(lateInstaller.installedPath).name)
            }

            trigger.writeText("write")
            awaitFile(written)
            assertEquals(17, File(root, "data/Bounce/old-save.rms").readBytes().single().toInt())
            assertFalse(File(root, "data/Bounce_1/old-save.rms").exists())

            val runtimePid = midletPid(app)
            assertTrue(runtimePid > 0)
            Process.killProcess(runtimePid)
            withTimeout(10_000) {
                while (RuntimeStorageLease.isActive(app.filesDir, oldApp)) delay(50)
            }
            assertEquals("Bounce_2", AppInstaller.chooseTargetDirectory(
                File(root, "converted"), "Bounce", emptySet(), app.filesDir).name)
            assertTrue(File(root, "data/Bounce").deleteRecursively())
            assertEquals("Bounce", AppInstaller.chooseTargetDirectory(
                File(root, "converted"), "Bounce", emptySet(), app.filesDir).name)
        } finally {
            val pid = midletPid(app)
            if (pid > 0) Process.killProcess(pid)
            MidletSessionStore.clear(app)
            instrumentation.runOnMainSync {
                store.clear()
                val edit = preferences.edit()
                if (oldWorkdir == null) edit.remove(Constants.PREF_EMULATOR_DIR)
                else edit.putString(Constants.PREF_EMULATOR_DIR, oldWorkdir)
                edit.commit()
            }
            root.deleteRecursively()
        }
    }

    private suspend fun awaitFile(file: File) = withTimeout(15_000) {
        while (!file.isFile || file.length() == 0L) delay(50)
    }

    private fun midletPid(app: Application): Int {
        val manager = app.getSystemService(ActivityManager::class.java)
        return manager.runningAppProcesses?.firstOrNull {
            it.processName == "${app.packageName}:midlet"
        }?.pid ?: 0
    }

    companion object {
        // javac --release 8 output of test-owned source: public class Fixture {}
        private const val CLASS_BYTES = "yv66vgAAADQADQoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWBwAIAQAHRml4dHVyZQEABENvZGUBAA9MaW5lTnVtYmVyVGFibGUBAApTb3VyY2VGaWxlAQAMRml4dHVyZS5qYXZhACEABwACAAAAAAABAAEABQAGAAEACQAAAB0AAQABAAAABSq3AAGxAAAAAQAKAAAABgABAAAAAQABAAsAAAACAAw="
    }
}
