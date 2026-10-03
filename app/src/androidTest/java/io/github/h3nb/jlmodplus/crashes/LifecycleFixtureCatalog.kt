// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.crashes

import android.content.Context
import io.github.h3nb.jlmodplus.librarydb.LibraryAppEntity
import io.github.h3nb.jlmodplus.librarydb.LibraryBootstrapState
import io.github.h3nb.jlmodplus.librarydb.LibraryDatabase
import io.github.h3nb.jlmodplus.librarydb.LibraryStateEntity
import java.io.File
import kotlinx.coroutines.runBlocking

/** Registers real installed identity for the lifecycle fixture's converted payload. */
object LifecycleFixtureCatalog {
    @JvmStatic
    fun register(
        context: Context,
        root: File,
        appDir: File,
        title: String,
        vendor: String,
        version: String,
    ) = runBlocking {
        val database = LibraryDatabase.open(context, root)
        try {
            val dao = database.libraryDao()
            dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY))
            if (dao.getAppByStorageKey(appDir.name) == null) {
                dao.insertApp(LibraryAppEntity(
                    storageKey = appDir.name,
                    sourceTitle = title,
                    sourceVendor = vendor,
                    sourceVersion = version,
                ))
            }
        } finally {
            database.close()
        }
    }
}
