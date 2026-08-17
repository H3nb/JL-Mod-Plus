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

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

data class LibraryAppRow(
    val id: Long,
    @ColumnInfo(name = "storage_key")
    val storageKey: String,
    val title: String,
    val vendor: String,
    val version: String,
    val description: String,
    val favorite: Boolean,
    @ColumnInfo(name = "added_at")
    val addedAt: Long?,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long?,
    @ColumnInfo(name = "icon_revision")
    val iconRevision: Long,
)

@Dao
abstract class LibraryDao {
    @Query(
        """
        SELECT
            id,
            storage_key,
            COALESCE(custom_title, source_title) AS title,
            COALESCE(custom_vendor, source_vendor) AS vendor,
            COALESCE(custom_version, source_version) AS version,
            COALESCE(custom_description, source_description, '') AS description,
            favorite,
            added_at,
            last_played_at,
            icon_revision
        FROM apps
        ORDER BY title COLLATE NOCASE ASC, id ASC
        """,
    )
    abstract fun observeApps(): Flow<List<LibraryAppRow>>

    @Query("SELECT * FROM apps WHERE id = :id LIMIT 1")
    abstract suspend fun getApp(id: Long): LibraryAppEntity?

    @Query("SELECT * FROM apps WHERE storage_key = :storageKey LIMIT 1")
    abstract suspend fun getAppByStorageKey(storageKey: String): LibraryAppEntity?

    @Query(
        """
        SELECT * FROM apps
        WHERE source_title = :sourceTitle AND source_vendor = :sourceVendor
        ORDER BY id ASC
        """,
    )
    abstract suspend fun findAppsBySourceIdentity(
        sourceTitle: String,
        sourceVendor: String,
    ): List<LibraryAppEntity>

    @Query("SELECT storage_key FROM apps")
    abstract suspend fun getStorageKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertApp(app: LibraryAppEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertApps(apps: List<LibraryAppEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAppsIgnoringExisting(apps: List<LibraryAppEntity>): List<Long>

    /**
     * Installer/reinstall source update. This deliberately leaves all Library-owned/user state
     * untouched instead of replacing the entire entity with default values.
     */
    @Query(
        """
        UPDATE apps SET
            source_title = :sourceTitle,
            source_vendor = :sourceVendor,
            source_version = :sourceVersion,
            source_description = :sourceDescription,
            icon_revision = :iconRevision
        WHERE id = :appId
        """,
    )
    abstract suspend fun updateSourceMetadata(
        appId: Long,
        sourceTitle: String,
        sourceVendor: String,
        sourceVersion: String,
        sourceDescription: String?,
        iconRevision: Long,
    ): Int

    @Query("DELETE FROM apps WHERE storage_key = :storageKey")
    abstract suspend fun deleteAppByStorageKey(storageKey: String): Int

    @Query("DELETE FROM apps WHERE storage_key IN (:storageKeys)")
    abstract suspend fun deleteAppsByStorageKeys(storageKeys: Set<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setLibraryState(state: LibraryStateEntity)

    @Query("SELECT * FROM library_state WHERE id = 1 LIMIT 1")
    abstract suspend fun getLibraryState(): LibraryStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPlayStatReceipt(receipt: PlayStatReceiptEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCollection(collection: LibraryCollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCollectionMembership(membership: LibraryCollectionAppEntity): Long

    @Query("DELETE FROM collection_apps")
    abstract suspend fun clearCollectionMemberships()

    @Query("DELETE FROM collections")
    abstract suspend fun clearCollections()

    @Query("DELETE FROM apps")
    abstract suspend fun clearApps()

    /**
     * Atomically publishes the first indexed catalog. Callers must only invoke this while the
     * workdir database has not reached READY; an established Library contains user-owned state
     * that must never be destroyed and reconstructed from the filesystem.
     */
    @Transaction
    open suspend fun replaceIncompleteCatalog(apps: List<LibraryAppEntity>) {
        clearCollectionMemberships()
        clearCollections()
        clearApps()
        insertApps(apps)
        setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY))
    }

    /** Applies one confident filesystem-name diff without touching unchanged rows. */
    @Transaction
    open suspend fun applyFilesystemReconciliation(
        addedApps: List<LibraryAppEntity>,
        removedStorageKeys: Set<String>,
    ) {
        if (removedStorageKeys.isNotEmpty()) {
            deleteAppsByStorageKeys(removedStorageKeys)
        }
        if (addedApps.isNotEmpty()) {
            // IGNORE makes a racing direct installer update win rather than turning a recovery pass
            // into a duplicate-key failure. Normal app installation still uses explicit mutations.
            insertAppsIgnoringExisting(addedApps)
        }
    }
}
