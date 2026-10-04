// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth

import android.content.Context
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import java.io.File
import kotlinx.coroutines.runBlocking

/** Creates the installed identity required by the real runtime authority in a private fixture root. */
object AudioFixtureCatalog {
    @JvmStatic
    fun install(context: Context, root: File): Long {
        val database = LibraryDatabase.open(context, root)
        try {
            return runBlocking {
                database.libraryDao().insertApp(
                    LibraryAppEntity(
                        storageKey = "fixture",
                        sourceTitle = "Audio Lifecycle Fixture",
                        sourceVendor = "JL-Mod Plus",
                        sourceVersion = "1.0",
                    ),
                )
            }
        } finally {
            database.close()
        }
    }
}
