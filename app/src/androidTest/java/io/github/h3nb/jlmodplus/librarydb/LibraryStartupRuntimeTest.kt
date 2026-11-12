/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.librarydb

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryStartupRuntimeTest {
    @Test
    fun existingCatalogLoadsAndProjects() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-startup-${System.nanoTime()}").apply { mkdirs() }
        val converted = File(root, "converted").apply { mkdirs() }
        val count = 2_000
        val rows = (1..count).map { index ->
            File(converted, "game-$index").mkdirs()
            LibraryAppEntity(
                storageKey = "game-$index",
                sourceTitle = "Game $index",
                sourceVendor = "Vendor",
                sourceVersion = "1.0",
            )
        }
        val database = LibraryDatabase.open(context, root)
        try {
            database.libraryDao().insertApps(rows)
            database.libraryDao().setLibraryState(
                LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY),
            )
        } finally {
            database.close()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = LibraryRepository(
            scope = scope,
            databaseFactory = { workdir -> LibraryDatabase.open(context, workdir) },
        )
        try {
            val start = System.nanoTime()
            repository.setEmulatorDirectory(root)
            val ready = withTimeout(90_000L) {
                repository.state.filterIsInstance<LibraryRepository.State.Ready>().first()
            }
            val repositoryMs = (System.nanoTime() - start) / 1_000_000.0
            assertEquals(count, ready.apps.size)

            val displayStart = System.nanoTime()
            val display = withTimeout(90_000L) {
                libraryDisplayStates(
                    repository.state,
                    flowOf(""),
                    flowOf(0),
                    flowOf(LibraryQuickView.All),
                    flowOf(Locale.US),
                    guestLocales = flowOf(DescriptionLocale("en-US", 1L)),
                ).filterIsInstance<LibraryViewModel.DisplayState.Ready>().first()
            }
            val projectionMs = (System.nanoTime() - displayStart) / 1_000_000.0
            assertEquals(count, display.apps.size)
            assertEquals(count, display.availableAppIds.size)
            assertEquals(count, display.iconFiles.size)
            Log.i("LibraryStartupRuntime",
                "count=$count repositoryMs=$repositoryMs projectionMs=$projectionMs")
        } finally {
            repository.close()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }
}
