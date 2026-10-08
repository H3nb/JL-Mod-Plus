/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import android.graphics.Bitmap
import android.os.Debug
import android.os.Trace
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryAppRow
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import io.github.h3nb.jlmodplus.librarydb.LibraryIconRevision
import io.github.h3nb.jlmodplus.librarydb.LibraryListProjection
import io.github.h3nb.jlmodplus.util.Constants
import io.github.h3nb.jlmodplus.util.FileUtils
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in characterization on a disposable installation; never a wall-clock CI gate. */
@RunWith(AndroidJUnit4::class)
class LibraryPerformanceProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun requireDisposableInstallation() {
        assumeTrue("Only the disposable profiling workflow selects this fixture",
            InstrumentationRegistry.getArguments().getString("libraryPerformanceProbe") == "true")
        assertTrue(context.packageName.endsWith(".debug"))
    }

    @Test
    fun seedReadyCatalog() = runBlocking {
        requireDisposableInstallation()
        val root = File(context.filesDir, "library-performance-probe")
        // No user-selected root is read, modified, or deleted.
        assertTrue(root.mkdirs() || root.isDirectory)
        assertTrue(FileUtils.initWorkDir(root))
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) for (x in 0 until 128) {
            bitmap.setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) 0xff224466.toInt() else 0xffcc8844.toInt())
        }
        val png = File(root, "probe-source.png")
        png.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val entities = List(1_000) { index ->
            val key = "probe-${index.toString().padStart(4, '0')}"
            val directory = File(root, "converted/$key").apply { assertTrue(mkdirs() || isDirectory) }
            File(directory, "converted.dex").writeBytes(byteArrayOf(1))
            val title = "Probe Game ${index.toString().padStart(4, '0')}"
            File(directory, "converted.dex.conf").writeText(
                "Manifest-Version: 1.0\nMIDlet-Name: $title\nMIDlet-Vendor: Probe Vendor\n" +
                    "MIDlet-Version: 1.0\nMIDlet-1: $title,,probe.NotRunnable\n",
            )
            val icon = File(directory, "icon.png")
            png.copyTo(icon, overwrite = true)
            LibraryAppEntity(storageKey = key, sourceTitle = title, sourceVendor = "Probe Vendor",
                sourceVersion = "1.0", favorite = index % 2 == 0,
                iconRevision = LibraryIconRevision.fromFile(icon))
        }
        val database = LibraryDatabase.open(context, root)
        try {
            val dao = database.libraryDao()
            dao.replaceIncompleteCatalog(entities)
            val rows = dao.observeApps().first()
            assertEquals(1_000, rows.size)
            val collectionId = dao.createCollection("Probe Collection", 1L)
            dao.setCollectionMemberships(collectionId, rows.take(500).map { it.id }, true, 1L)
        } finally {
            database.close()
        }
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Constants.PREF_EMULATOR_DIR, root.absolutePath)
            .putInt(Constants.PREF_APP_SORT, 0)
            .putInt(Constants.PREF_APPS_VIEW, Constants.LIBRARY_LAYOUT_LIST)
            .commit())
    }

    @Test
    fun characterizeSearchCpu() {
        requireDisposableInstallation()
        val results = JSONArray()
        for (count in intArrayOf(1_000, 5_000)) {
            val rows = List(count) { index ->
                LibraryAppRow(id = index + 1L, storageKey = "probe-$index",
                    sourceTitle = "GAME $index", sourceVendor = "VENDOR ${index % 37}", sourceVersion = "1.0",
                    title = "GAME $index", vendor = "VENDOR ${index % 37}", version = "1.0",
                    description = "A LONG DESCRIPTION WITH MIXED METADATA FOR THIS MIDLET. ".repeat(8),
                    favorite = false, addedAt = index.toLong(), lastPlayedAt = null, iconRevision = 0)
            }
            for (sort in intArrayOf(LibraryListProjection.SORT_DATE, LibraryListProjection.SORT_TITLE)) {
                for (query in arrayOf("GAME", "absent")) {
                    val expected = if (query == "GAME") count else 0
                    repeat(5) { assertEquals(expected, LibraryListProjection.project(rows, query, sort, Locale.US).size) }
                    val cpuTimes = JSONArray()
                    repeat(20) {
                        Trace.beginSection("LibraryProbe/search/$count/$sort/$query")
                        val start = Debug.threadCpuTimeNanos()
                        val projected = try {
                            LibraryListProjection.project(rows, query, sort, Locale.US)
                        } finally {
                            Trace.endSection()
                        }
                        cpuTimes.put((Debug.threadCpuTimeNanos() - start) / 1_000_000.0)
                        assertEquals(expected, projected.size)
                    }
                    results.put(JSONObject().put("rows", count).put("sort", sort)
                        .put("query", query).put("threadCpuMs", cpuTimes))
                }
            }
        }
        File(context.filesDir, "library-search-cpu.json").writeText(results.toString(2))
    }
}
