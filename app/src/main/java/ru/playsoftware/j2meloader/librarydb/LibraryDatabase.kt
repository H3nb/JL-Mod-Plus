/*
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

package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File

@Database(
    entities = [
        LibraryAppEntity::class,
        LibraryCollectionEntity::class,
        LibraryCollectionAppEntity::class,
        PlayStatReceiptEntity::class,
        LibraryStateEntity::class,
    ],
    version = LibraryDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        const val FILE_NAME = "JL-Mod-library.db"
        const val SCHEMA_VERSION = 1

        /**
         * Opens the Library catalog inside the supplied emulator/work directory. An absolute path
         * is intentional: each workdir owns its own catalog and no global app-private Library DB is
         * shared between roots.
         */
        fun open(context: Context, emulatorDir: File): LibraryDatabase {
            require(emulatorDir.isDirectory) {
                "Library work directory does not exist: ${emulatorDir.absolutePath}"
            }
            val databaseFile = File(emulatorDir, FILE_NAME)
            return Room.databaseBuilder(
                context.applicationContext,
                LibraryDatabase::class.java,
                databaseFile.absolutePath,
            )
                .setDriver(AndroidSQLiteDriver())
                .build()
        }
    }
}

object LibraryBootstrapState {
    const val CREATING = "CREATING"
    const val INDEXING = "INDEXING"
    const val READY = "READY"
}
