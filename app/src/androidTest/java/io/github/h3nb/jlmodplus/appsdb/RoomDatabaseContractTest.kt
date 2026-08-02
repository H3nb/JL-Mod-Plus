/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.appsdb

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.applist.AppItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the version-1 Room contract against a synthetic database file.
 * This deliberately uses the production DAO and coroutine return types so an
 * upgrade cannot silently change persistence or asynchronous operations.
 */
class RoomDatabaseContractTest {
    @Test
    fun versionOneDatabaseSupportsCrudFilteringAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseFile = File(
            context.getDatabasePath("room-contract-test.db").parentFile,
            "room-contract-test.db"
        )
        deleteIfPresent(databaseFile)

        var database: AppDatabase? = null
        try {
            database = open(context, databaseFile)
            assertEquals(1, database.openHelper.readableDatabase.version)

            val dao = database.appItemDao()
            val alpha = AppItem("alpha", "Alpha", "Vendor", "1.0")
            val beta = AppItem("beta", "Beta", "Vendor", "2.0")
            dao.insert(listOf(alpha, beta))

            val filtered = dao.getAllSingle(
                SimpleSQLiteQuery(
                    "SELECT * FROM apps WHERE title LIKE ? ORDER BY title ASC",
                    arrayOf<Any?>("%Alpha%")
                )
            )
            assertEquals(1, filtered.size)
            assertEquals("alpha", filtered[0].path)

            val stored = dao.get("Alpha", "Vendor")
            assertNotNull(stored)
            checkNotNull(stored).title = "Alpha Updated"
            dao.update(stored)
            assertNotNull(dao.get("Alpha Updated", "Vendor"))

            dao.delete(stored)
            assertNull(dao.get("Alpha Updated", "Vendor"))
            dao.deleteAll()
            assertTrue(
                dao.getAllSingle(SimpleSQLiteQuery("SELECT * FROM apps ORDER BY title ASC"))
                    .isEmpty()
            )
        } finally {
            database?.close()
        }

        val reopened = open(context, databaseFile)
        try {
            assertEquals(1, reopened.openHelper.readableDatabase.version)
            assertTrue(
                reopened.appItemDao().getAllSingle(SimpleSQLiteQuery("SELECT * FROM apps"))
                    .isEmpty()
            )
        } finally {
            reopened.close()
            deleteIfPresent(databaseFile)
        }
    }

    @Test
    fun configurableDatabasePathsRemainIndependent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstFile = File(
            context.getDatabasePath("room-contract-first.db").parentFile,
            "room-contract-first.db"
        )
        val secondFile = File(
            context.getDatabasePath("room-contract-second.db").parentFile,
            "room-contract-second.db"
        )
        deleteIfPresent(firstFile)
        deleteIfPresent(secondFile)

        var first: AppDatabase? = null
        var second: AppDatabase? = null
        try {
            first = open(context, firstFile)
            first.appItemDao().insert(AppItem("first", "First", "Vendor", "1"))
            second = open(context, secondFile)
            assertTrue(
                second.appItemDao().getAllSingle(SimpleSQLiteQuery("SELECT * FROM apps"))
                    .isEmpty()
            )
            assertNotNull(first.appItemDao().get("First", "Vendor"))
        } finally {
            second?.close()
            first?.close()
            deleteIfPresent(firstFile)
            deleteIfPresent(secondFile)
        }
    }

    private fun open(context: Context, file: File): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).build()

    private fun deleteIfPresent(file: File) {
        if (file.exists()) {
            assertTrue(file.delete())
        }
    }
}
