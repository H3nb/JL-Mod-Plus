// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.config.ProfileModel
import io.github.h3nb.jlmodplus.config.ProfilesManager
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import io.github.h3nb.jlmodplus.util.Constants
import java.io.File
import javax.microedition.shell.MicroActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in launcher, never opens hardware during ordinary instrumentation runs. */
class CameraDeviceProbeTest {
    @Test fun launch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cameraDeviceProbe") == "true")
        val context = instrumentation.targetContext
        val root = File(requireNotNull(PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Constants.PREF_EMULATOR_DIR, null)))
        val storageKey = args.getString("cameraProbeApp") ?: "Camera175Probe"
        require(storageKey != "." && storageKey != ".." && !storageKey.contains('/') && !storageKey.contains('\\'))
        val app = File(root, "converted/$storageKey")
        if (args.getString("cameraProbeAction") == "cleanup") {
            require(storageKey == "Camera175Probe")
            check(File(app, "converted.dex.conf").readText().contains(
                "MIDlet-1: Camera175Probe,,jlmod.runtimefixture.CameraSnapshotMidlet"))
            val database = LibraryDatabase.open(context, root)
            try { runBlocking { database.libraryDao().deleteAppByStorageKey(storageKey) } }
            finally { database.close() }
            for (parent in listOf("converted", "configs", "data")) {
                val directory = File(root, "$parent/$storageKey")
                check(directory.canonicalFile.parentFile == File(root, parent).canonicalFile)
                check(!directory.exists() || directory.deleteRecursively())
            }
            File(context.filesDir, "camera175-probe").deleteRecursively()
            return
        }
        if (storageKey == "Camera175Probe" && args.getString("cameraProbeReuse") != "true") {
            check(!app.exists()) { "Probe already exists; remove its owned fixture explicitly before setup" }
            check(app.mkdirs())
            File(context.applicationInfo.sourceDir).copyTo(File(app, "converted.zip"))
            val output = File(context.filesDir, "camera175-probe").apply { mkdirs() }
            File(app, "converted.dex.conf").writeText("""
                Manifest-Version: 1.0
                MIDlet-Name: Camera175Probe
                MIDlet-Vendor: JL-Mod Plus
                MIDlet-Version: 1.0
                MIDlet-1: Camera175Probe,,jlmod.runtimefixture.CameraSnapshotMidlet
                JLMod-Camera-Probe-Root: ${output.absolutePath}

            """.trimIndent())
            val config = File(root, "configs/$storageKey").apply { mkdirs() }
            val profile = ProfileModel.fresh().apply {
                screenWidth = 640
                screenHeight = 800
                showKeyboard = false
                touchInput = true
            }
            check(ProfilesManager.saveConfig(config, profile))
            val database = LibraryDatabase.open(context, root)
            try {
                runBlocking { database.libraryDao().insertApp(LibraryAppEntity(
                    storageKey = storageKey, sourceTitle = storageKey,
                    sourceVendor = "JL-Mod Plus", sourceVersion = "1.0")) }
            } finally { database.close() }
        }
        context.startActivity(Intent(Intent.ACTION_DEFAULT, Uri.parse(app.absolutePath), context,
            MicroActivity::class.java).putExtra(Constants.KEY_MIDLET_NAME, storageKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (args.getString("cameraProbeHold") == "true") {
            val stop = File(context.filesDir, "camera175-probe.stop")
            stop.delete()
            val deadline = android.os.SystemClock.elapsedRealtime() + 1_200_000
            while (!stop.exists() && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(250)
            }
            stop.delete()
        }
    }
}
