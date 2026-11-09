/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.github.h3nb.jlmodplus.installer

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.jar.Descriptor
import io.github.h3nb.jlmodplus.librarydb.LibraryViewModel
import io.github.h3nb.jlmodplus.util.Constants
import io.reactivex.Single
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalContentJadImportTest {
    @Test
    fun externalJadRequiresExplicitJarAndPreservesDescriptorValidation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val previousWorkdir = preferences.getString(Constants.PREF_EMULATOR_DIR, null)
        val root = File(app.cacheDir, "library-share/content-jad-test-${System.nanoTime()}").apply { mkdirs() }
        val workdir = File(root, "work").apply { mkdir() }
        val manifest = "Manifest-Version: 1.0\nMIDlet-Name: Fixture\nMIDlet-Vendor: Tests\nMIDlet-Version: 1.0\nMIDlet-1: Fixture,,Fixture\n"
        val jar = createJar(File(root, "Fixture.jar"), manifest)
        val differentJar = createJar(File(root, "Other.jar"), manifest.replace("Fixture", "Other"))
        val jad = File(root, "Fixture.jad").apply {
            writeText("MIDlet-Name: Fixture\r\nMIDlet-Vendor: Tests\r\nMIDlet-Version: 1.0\r\n" +
                    "MIDlet-Jar-URL: Fixture.jar\r\nMIDlet-Jar-Size: ${jar.length()}\r\n")
        }
        val uri = contentUri(app, jad)
        val jarUri = contentUri(app, jar)
        val wrongJarUri = contentUri(app, differentJar)
        val store = ViewModelStore()
        lateinit var library: LibraryViewModel
        try {
            instrumentation.runOnMainSync {
                preferences.edit().putString(Constants.PREF_EMULATOR_DIR, workdir.path).commit()
                library = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))
                    [LibraryViewModel::class.java]
                library.setEmulatorDirectory(workdir.path)
            }
            withTimeout(15_000) {
                while (library.readyGeneration() == null) delay(20)
            }

            val installer = AppInstaller(null as File?, uri, library)
            assertEquals(AppInstaller.STATUS_NEED_JAR,
                Single.create<Int>(installer::loadInfo).blockingGet())
            assertNotNull(installer.newDescriptor)
            assertEquals(AppInstaller.STATUS_NEW, Single.create<Int> { emitter ->
                installer.loadSelectedExternalJar(jarUri, emitter)
            }.blockingGet())
            assertEquals("Fixture", installer.newDescriptor.name)
            assertEquals("Fixture", installer.manifest.name)

            val wrong = AppInstaller(null as File?, uri, library)
            assertEquals(AppInstaller.STATUS_NEED_JAR,
                Single.create<Int>(wrong::loadInfo).blockingGet())
            assertEquals(AppInstaller.STATUS_UNMATCHED, Single.create<Int> { emitter ->
                wrong.loadSelectedExternalJar(wrongJarUri, emitter)
            }.blockingGet())
            installer.clearCache()
            wrong.clearCache()
        } finally {
            instrumentation.runOnMainSync {
                store.clear()
                preferences.edit().putString(Constants.PREF_EMULATOR_DIR, previousWorkdir).commit()
            }
            root.deleteRecursively()
        }
    }

    private fun contentUri(app: Application, file: File): Uri =
        FileProvider.getUriForFile(app, "${app.packageName}.diagnostic-files", file)

    private fun createJar(file: File, manifest: String): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        return file
    }
}
