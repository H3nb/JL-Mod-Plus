/*
 * R&D-only adapter: prepare a real workdir-scoped installed Library identity.
 * The active runtime installer/authority contract must not be bypassed by tests.
 */
package io.github.h3nb.jlmodplus.memory

import android.content.Context
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryBootstrapState
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import io.github.h3nb.jlmodplus.librarydb.LibraryStateEntity
import java.io.File
import kotlinx.coroutines.runBlocking

internal object ResearchLibraryFixture {
    @JvmStatic
    fun register(context: Context, root: File): Long = runBlocking {
        val database = LibraryDatabase.open(context, root)
        try {
            database.libraryDao().setLibraryState(
                LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY),
            )
            database.libraryDao().insertApp(
                LibraryAppEntity(
                    storageKey = "fixture",
                    sourceTitle = "Memory IPC Fixture",
                    sourceVendor = "JL-Mod Plus",
                    sourceVersion = "1.0",
                ),
            )
        } finally {
            database.close()
        }
    }
}
