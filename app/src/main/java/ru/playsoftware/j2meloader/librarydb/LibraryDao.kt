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

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

data class LibraryAppRow(
    val id: Long,
    @androidx.room3.ColumnInfo(name = "storage_key")
    val storageKey: String,
    val title: String,
    val vendor: String,
    val version: String,
    val description: String,
    val favorite: Boolean,
    @androidx.room3.ColumnInfo(name = "added_at")
    val addedAt: Long?,
    @androidx.room3.ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long?,
    @androidx.room3.ColumnInfo(name = "icon_revision")
    val iconRevision: Long,
)

@Dao
interface LibraryDao {
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
    fun observeApps(): Flow<List<LibraryAppRow>>

    @Query("SELECT * FROM apps WHERE id = :id LIMIT 1")
    suspend fun getApp(id: Long): LibraryAppEntity?

    @Query("SELECT * FROM apps WHERE storage_key = :storageKey LIMIT 1")
    suspend fun getAppByStorageKey(storageKey: String): LibraryAppEntity?

    @Query(
        """
        SELECT * FROM apps
        WHERE source_title = :sourceTitle AND source_vendor = :sourceVendor
        ORDER BY id ASC
        """,
    )
    suspend fun findAppsBySourceIdentity(
        sourceTitle: String,
        sourceVendor: String,
    ): List<LibraryAppEntity>

    @Query("SELECT storage_key FROM apps")
    suspend fun getStorageKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertApp(app: LibraryAppEntity): Long

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
    suspend fun updateSourceMetadata(
        appId: Long,
        sourceTitle: String,
        sourceVendor: String,
        sourceVersion: String,
        sourceDescription: String?,
        iconRevision: Long,
    ): Int

    @Query("DELETE FROM apps WHERE storage_key = :storageKey")
    suspend fun deleteAppByStorageKey(storageKey: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setLibraryState(state: LibraryStateEntity)

    @Query("SELECT * FROM library_state WHERE id = 1 LIMIT 1")
    suspend fun getLibraryState(): LibraryStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlayStatReceipt(receipt: PlayStatReceiptEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCollection(collection: LibraryCollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionMembership(membership: LibraryCollectionAppEntity): Long
}
