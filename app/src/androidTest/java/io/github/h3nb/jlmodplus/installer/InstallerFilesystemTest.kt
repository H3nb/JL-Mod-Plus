package io.github.h3nb.jlmodplus.installer

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.Single
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.librarydb.LibraryViewModel
import io.github.h3nb.jlmodplus.util.Constants
import io.github.h3nb.jlmodplus.jar.Descriptor
import io.github.h3nb.jlmodplus.config.Config
import io.github.h3nb.jlmodplus.config.ProfileModel
import io.github.h3nb.jlmodplus.config.ProfilesManager

@RunWith(AndroidJUnit4::class)
class InstallerFilesystemTest {
    @Test fun freshJadInstallReinstallAndReconversionPreserveSourcesAndUserFiles() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val oldWorkdir = preferences.getString(Constants.PREF_EMULATOR_DIR, null)
        val hadDefault = preferences.contains(Constants.PREF_DEFAULT_PROFILE)
        val oldDefault = preferences.getString(Constants.PREF_DEFAULT_PROFILE, null)
        val root = File(app.cacheDir, "installer-test-${System.nanoTime()}").apply { mkdirs() }
        val workdir = File(root, "work").apply { mkdir() }
        val jar = File(root, "Fixture.jar")
        val manifest = "Manifest-Version: 1.0\nMIDlet-Name: Fixture\nMIDlet-Vendor: Tests\nMIDlet-Version: 1.0\nMIDlet-1: Fixture,,Fixture\n"
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); zip.write(manifest.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("Fixture.class")); zip.write(Base64.decode(CLASS_BYTES, Base64.DEFAULT)); zip.closeEntry()
        }
        val jad = File(root, "Fixture.jad").apply {
            writeText("MIDlet-Name: Fixture\r\nMIDlet-Vendor: Tests\r\nMIDlet-Version: 1.0\r\n" +
                "MIDlet-Jar-URL: Fixture.jar\r\nMIDlet-Jar-Size: ${jar.length()}\r\nVendor-Option: retained\r\n")
        }
        val originalJad = jad.readBytes()
        val store = ViewModelStore()
        lateinit var library: LibraryViewModel
        try {
            instrumentation.runOnMainSync {
                preferences.edit()
                    .putString(Constants.PREF_EMULATOR_DIR, workdir.path)
                    .remove(Constants.PREF_DEFAULT_PROFILE)
                    .commit()
                library = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[LibraryViewModel::class.java]
                library.setEmulatorDirectory(workdir.path)
            }
            val generation = withTimeout(15_000) {
                while (library.readyGeneration() == null) delay(20)
                requireNotNull(library.readyGeneration())
            }
            assertFalse(File(workdir, "converted").exists())
            val installer = AppInstaller(jad, Uri.fromFile(jad), library)
            assertEquals(AppInstaller.STATUS_NEW, Single.create<Int>(installer::loadInfo).blockingGet())
            // Confirmation refers to the reviewed snapshot even if external sources change.
            jad.writeText("changed after review")
            jar.writeText("changed after review")
            assertEquals(AppInstaller.STATUS_SUCCESS, Single.create<Int>(installer::install).blockingGet())
            val id = installer.installedId
            val installed = File(installer.installedPath)
            val installedWorkdir = requireNotNull(requireNotNull(installed.parentFile).parentFile)
            val installedConfigDir = File(installedWorkdir, "configs/${installed.name}")
            val installedConfig = File(installedConfigDir, Config.MIDLET_CONFIG_FILE)
            val freshBuiltInBytes = installedConfig.readBytes()
            val builtInKey = ProfileModel.builtInThemePreferenceKey(installedConfigDir)
            assertTrue("Missing $builtInKey in ${preferences.all}",
                preferences.getBoolean(builtInKey, false))
            assertArrayEquals(originalJad, File(installed, AppReconverter.RETAINED_JAD).readBytes())
            val save = File(workdir, "data/${installed.name}/save").apply { parentFile!!.mkdirs(); writeText("save") }
            val config = File(workdir, "configs/${installed.name}/custom").apply { parentFile!!.mkdirs(); writeText("config") }

            preferences.edit().putString(Constants.PREF_DEFAULT_PROFILE, "Unavailable").commit()

            val reinstall = AppInstaller(id, generation.generation, workdir, installed.name, library)
            assertEquals(AppInstaller.STATUS_EQUAL, Single.create<Int>(reinstall::loadInfo).blockingGet())
            assertEquals(AppInstaller.STATUS_SUCCESS, Single.create<Int>(reinstall::install).blockingGet())
            assertEquals(id, reinstall.installedId)
            assertArrayEquals(originalJad, File(installed, AppReconverter.RETAINED_JAD).readBytes())
            assertArrayEquals(freshBuiltInBytes, installedConfig.readBytes())
            assertTrue(preferences.getBoolean(
                ProfileModel.builtInThemePreferenceKey(installedConfigDir), false))

            assertTrue(File(installed, "converted.dex.conf").delete())
            AppReconverter.reconvert(installed)
            assertFalse(AppReconverter.needsReconversion(installed))
            assertEquals("retained", Descriptor(File(installed, "converted.dex.conf"), false).attrs["Vendor-Option"])
            assertArrayEquals(originalJad, File(installed, AppReconverter.RETAINED_JAD).readBytes())
            assertEquals("save", save.readText())
            assertEquals("config", config.readText())

            val updateJar = File(root, "update.jar")
            ZipOutputStream(updateJar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write(manifest.replace("MIDlet-Version: 1.0", "MIDlet-Version: 2.0").toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("Fixture.class"))
                zip.write(Base64.decode(CLASS_BYTES, Base64.DEFAULT))
                zip.closeEntry()
            }
            val update = AppInstaller(updateJar, Uri.fromFile(updateJar), library)
            val customProfile = requireNotNull(ProfilesManager.loadConfig(installedConfigDir)).apply {
                screenWidth = 777
            }
            assertTrue(ProfilesManager.saveConfig(customProfile))
            assertTrue(preferences.edit()
                .remove(ProfileModel.builtInThemePreferenceKey(installedConfigDir))
                .commit())
            val customBytes = installedConfig.readBytes()
            assertEquals(AppInstaller.STATUS_NEWER, Single.create<Int>(update::loadInfo).blockingGet())
            assertEquals(AppInstaller.STATUS_SUCCESS, Single.create<Int>(update::install).blockingGet())
            assertEquals(id, update.installedId)
            assertFalse(File(installed, AppReconverter.RETAINED_JAD).exists())
            assertNull(Descriptor(File(installed, "converted.dex.conf"), false).attrs["Vendor-Option"])
            assertEquals("save", save.readText())
            assertEquals("config", config.readText())
            assertArrayEquals(customBytes, installedConfig.readBytes())
            assertFalse(preferences.getBoolean(
                ProfileModel.builtInThemePreferenceKey(installedConfigDir), false))

            val originKey = "config_profile_origin:${installedConfigDir.absolutePath}"
            val linkedKey = "config_profile_linked:${installedConfigDir.absolutePath}"
            assertTrue(preferences.edit()
                .putString(originKey, "K800i")
                .putBoolean(linkedKey, true)
                .commit())
            val linkedBytes = installedConfig.readBytes()
            val linkedReinstall = AppInstaller(id, generation.generation, workdir, installed.name, library)
            assertEquals(AppInstaller.STATUS_EQUAL,
                Single.create<Int>(linkedReinstall::loadInfo).blockingGet())
            assertEquals(AppInstaller.STATUS_SUCCESS,
                Single.create<Int>(linkedReinstall::install).blockingGet())
            assertArrayEquals(linkedBytes, installedConfig.readBytes())
            assertEquals("K800i", preferences.getString(originKey, null))
            assertTrue(preferences.getBoolean(linkedKey, false))
        } finally {
            instrumentation.runOnMainSync {
                store.clear()
                val editor = preferences.edit().putString(Constants.PREF_EMULATOR_DIR, oldWorkdir)
                if (hadDefault) editor.putString(Constants.PREF_DEFAULT_PROFILE, oldDefault)
                else editor.remove(Constants.PREF_DEFAULT_PROFILE)
                editor.commit()
            }
            root.deleteRecursively()
        }
    }

    companion object {
        // javac --release 8 output of the test-owned source: public class Fixture {}
        private const val CLASS_BYTES = "yv66vgAAADQADQoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWBwAIAQAHRml4dHVyZQEABENvZGUBAA9MaW5lTnVtYmVyVGFibGUBAApTb3VyY2VGaWxlAQAMRml4dHVyZS5qYXZhACEABwACAAAAAAABAAEABQAGAAEACQAAAB0AAQABAAAABSq3AAGxAAAAAQAKAAAABgABAAAAAQABAAsAAAACAAw="
    }
}
