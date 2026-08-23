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
    @ColumnInfo(name = "storage_key") val storageKey: String,
    @ColumnInfo(name = "source_title") val sourceTitle: String,
    @ColumnInfo(name = "source_vendor") val sourceVendor: String,
    @ColumnInfo(name = "source_version") val sourceVersion: String,
    val title: String,
    val vendor: String,
    val version: String,
    val description: String,
    @ColumnInfo(name = "source_description") val sourceDescription: String = "",
    val favorite: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long?,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
    @ColumnInfo(name = "play_count") val playCount: Long = 0,
    @ColumnInfo(name = "total_play_time_ms") val totalPlayTimeMs: Long = 0,
    @ColumnInfo(name = "icon_revision") val iconRevision: Long,
)

data class LibraryCollectionRow(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "app_count") val appCount: Int,
)

data class InstalledAppMetadata(
    val storageKey: String,
    val sourceTitle: String,
    val sourceVendor: String,
    val sourceVersion: String,
    val sourceDescription: String?,
    val iconRevision: Long,
    val addedAt: Long,
)

enum class LibraryPlayStatReconcileResult {
    Applied,
    AlreadyReceipted,
    TargetMissing,
}

@Dao
abstract class LibraryDao {
    @Query(
        """
        SELECT
            id,
            storage_key,
            source_title,
            source_vendor,
            source_version,
            COALESCE(custom_title, source_title) AS title,
            COALESCE(custom_vendor, source_vendor) AS vendor,
            COALESCE(custom_version, source_version) AS version,
            COALESCE(custom_description, source_description, '') AS description,
            COALESCE(source_description, '') AS source_description,
            favorite,
            added_at,
            last_played_at,
            play_count,
            total_play_time_ms,
            icon_revision
        FROM apps
        ORDER BY title COLLATE NOCASE ASC, id ASC
        """,
    )
    abstract fun observeApps(): Flow<List<LibraryAppRow>>

    @Query(
        """
        SELECT
            c.id,
            c.name,
            c.sort_order,
            c.created_at,
            COUNT(ca.app_id) AS app_count
        FROM collections AS c
        LEFT JOIN collection_apps AS ca ON ca.collection_id = c.id
        GROUP BY c.id, c.name, c.sort_order, c.created_at
        ORDER BY c.sort_order ASC, c.name COLLATE NOCASE ASC, c.id ASC
        """,
    )
    abstract fun observeCollections(): Flow<List<LibraryCollectionRow>>

    @Query("SELECT * FROM apps WHERE id = :id LIMIT 1")
    abstract suspend fun getApp(id: Long): LibraryAppEntity?

    @Query("SELECT * FROM apps WHERE storage_key = :storageKey LIMIT 1")
    abstract suspend fun getAppByStorageKey(storageKey: String): LibraryAppEntity?

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    abstract suspend fun getCollection(id: Long): LibraryCollectionEntity?

    @Query("SELECT app_id FROM collection_apps WHERE collection_id = :collectionId ORDER BY added_at DESC, app_id ASC")
    abstract suspend fun getCollectionAppIds(collectionId: Long): List<Long>

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

    @Query(
        """
        SELECT (
            EXISTS(SELECT 1 FROM apps LIMIT 1) OR
            EXISTS(SELECT 1 FROM collections LIMIT 1) OR
            EXISTS(SELECT 1 FROM collection_apps LIMIT 1) OR
            EXISTS(SELECT 1 FROM play_stat_receipts LIMIT 1)
        )
        """,
    )
    abstract suspend fun hasPersistentLibraryData(): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertApp(app: LibraryAppEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertApps(apps: List<LibraryAppEntity>)

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

    @Query("UPDATE apps SET icon_revision = :iconRevision WHERE id = :appId")
    abstract suspend fun updateIconRevision(appId: Long, iconRevision: Long): Int

    @Query("UPDATE apps SET custom_title = :customTitle WHERE id = :appId")
    abstract suspend fun updateCustomTitle(appId: Long, customTitle: String?): Int

    @Query(
        """
        UPDATE apps SET
            custom_title = :customTitle,
            custom_vendor = :customVendor,
            custom_version = :customVersion,
            custom_description = :customDescription
        WHERE id = :appId
        """,
    )
    abstract suspend fun updateCustomMetadata(
        appId: Long,
        customTitle: String?,
        customVendor: String?,
        customVersion: String?,
        customDescription: String?,
    ): Int

    @Query(
        """
        UPDATE apps SET
            custom_title = NULL,
            custom_vendor = NULL,
            custom_version = NULL,
            custom_description = NULL
        WHERE id = :appId
        """,
    )
    abstract suspend fun resetCustomMetadata(appId: Long): Int

    @Query("UPDATE apps SET favorite = :favorite WHERE id = :appId")
    abstract suspend fun updateFavorite(appId: Long, favorite: Boolean): Int

    @Query("DELETE FROM apps WHERE storage_key = :storageKey")
    abstract suspend fun deleteAppByStorageKey(storageKey: String): Int

    @Query("DELETE FROM apps WHERE storage_key IN (:storageKeys)")
    abstract suspend fun deleteAppsByStorageKeys(storageKeys: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setLibraryState(state: LibraryStateEntity)

    @Query("SELECT * FROM library_state WHERE id = 1 LIMIT 1")
    abstract suspend fun getLibraryState(): LibraryStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPlayStatReceipt(receipt: PlayStatReceiptEntity): Long

    @Query(
        """
        UPDATE apps SET
            last_played_at = CASE
                WHEN :firstRunningWallTimeMillis IS NULL THEN last_played_at
                WHEN last_played_at IS NULL OR :firstRunningWallTimeMillis > last_played_at
                    THEN :firstRunningWallTimeMillis
                ELSE last_played_at
            END,
            play_count = play_count + :playIncrement,
            total_play_time_ms = total_play_time_ms + :activeMillis
        WHERE id = :appId
        """,
    )
    abstract suspend fun updatePlayStats(
        appId: Long,
        firstRunningWallTimeMillis: Long?,
        playIncrement: Long,
        activeMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCollection(collection: LibraryCollectionEntity): Long

    @Query("SELECT MAX(sort_order) FROM collections")
    abstract suspend fun getMaxCollectionSortOrder(): Int?

    @Query(
        """
        UPDATE collections SET
            name = :name,
            normalized_name = :normalizedName
        WHERE id = :collectionId
        """,
    )
    abstract suspend fun updateCollectionName(
        collectionId: Long,
        name: String,
        normalizedName: String,
    ): Int

    @Query("DELETE FROM collections WHERE id = :collectionId")
    abstract suspend fun deleteCollection(collectionId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCollectionMembership(membership: LibraryCollectionAppEntity): Long

    @Query("DELETE FROM collection_apps WHERE collection_id = :collectionId AND app_id = :appId")
    abstract suspend fun deleteCollectionMembership(collectionId: Long, appId: Long): Int

    @Query("DELETE FROM collection_apps")
    abstract suspend fun clearCollectionMemberships()

    @Query("DELETE FROM collections")
    abstract suspend fun clearCollections()

    @Query("DELETE FROM apps")
    abstract suspend fun clearApps()

    /**
     * Receipt dedupe and stats mutation must commit atomically. If the app row is missing while the
     * converted directory still exists, the caller passes allowMissingTarget=false so the journal
     * remains retryable until targeted indexing recreates the row.
     */
    @Transaction
    open suspend fun reconcilePlayStat(
        sessionId: String,
        storageKey: String,
        reachedRunning: Boolean,
        firstRunningWallTimeMillis: Long?,
        accumulatedActiveMillis: Long,
        allowMissingTarget: Boolean,
    ): LibraryPlayStatReconcileResult {
        require(sessionId.isNotBlank()) { "Play-stat session id must not be blank" }
        require(storageKey.isNotBlank()) { "Play-stat storage key must not be blank" }
        require(accumulatedActiveMillis >= 0L) { "Play-stat duration must not be negative" }
        val app = getAppByStorageKey(storageKey)
        if (app == null && !allowMissingTarget) {
            return LibraryPlayStatReconcileResult.TargetMissing
        }
        val receipt = insertPlayStatReceipt(PlayStatReceiptEntity(sessionId))
        if (receipt == -1L) {
            return LibraryPlayStatReconcileResult.AlreadyReceipted
        }
        if (app != null && reachedRunning) {
            check(firstRunningWallTimeMillis != null) {
                "Reached-running session is missing first-running wall time"
            }
            check(
                updatePlayStats(
                    appId = app.id,
                    firstRunningWallTimeMillis = firstRunningWallTimeMillis,
                    playIncrement = 1L,
                    activeMillis = accumulatedActiveMillis,
                ) == 1,
            ) { "Play-stat target disappeared during transaction: ${app.id}" }
        }
        return LibraryPlayStatReconcileResult.Applied
    }

    @Transaction
    open suspend fun createCollection(name: String, createdAt: Long): Long {
        val displayName = name.trim()
        require(displayName.isNotEmpty()) { "Collection name must not be blank" }
        return insertCollection(
            LibraryCollectionEntity(
                name = displayName,
                createdAt = createdAt,
                sortOrder = (getMaxCollectionSortOrder() ?: -1) + 1,
            ),
        )
    }

    @Transaction
    open suspend fun setCollectionMembership(
        collectionId: Long,
        appId: Long,
        included: Boolean,
        addedAt: Long,
    ) {
        check(getCollection(collectionId) != null) { "Collection disappeared: $collectionId" }
        check(getApp(appId) != null) { "Library app disappeared: $appId" }
        if (included) {
            insertCollectionMembership(
                LibraryCollectionAppEntity(
                    collectionId = collectionId,
                    appId = appId,
                    addedAt = addedAt,
                ),
            )
        } else {
            deleteCollectionMembership(collectionId, appId)
        }
    }

    @Transaction
    open suspend fun setCollectionMemberships(
        collectionId: Long,
        appIds: List<Long>,
        included: Boolean,
        addedAt: Long,
    ) {
        check(getCollection(collectionId) != null) { "Collection disappeared: $collectionId" }
        appIds.distinct().forEach { appId ->
            check(getApp(appId) != null) { "Library app disappeared: $appId" }
            if (included) {
                insertCollectionMembership(
                    LibraryCollectionAppEntity(
                        collectionId = collectionId,
                        appId = appId,
                        addedAt = addedAt,
                    ),
                )
            } else {
                deleteCollectionMembership(collectionId, appId)
            }
        }
    }

    @Transaction
    open suspend fun replaceIncompleteCatalog(apps: List<LibraryAppEntity>) {
        clearCollectionMemberships()
        clearCollections()
        clearApps()
        insertApps(apps)
        setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY))
    }

    /**
     * Publish one conservative filesystem repair transaction.
     *
     * [scannedApps] contains newly discovered keys and any same-key reinstall targets selected by
     * the crash-recovery journal. Updating an existing key refreshes source metadata only, preserving
     * all Library-owned state. Removals are chunked below the API-23 SQLite bind-variable ceiling.
     */
    @Transaction
    open suspend fun applyFilesystemReconciliation(
        scannedApps: List<LibraryAppEntity>,
        removedStorageKeys: Set<String>,
    ) {
        removedStorageKeys.toList().chunked(SAFE_DELETE_BIND_COUNT).forEach { chunk ->
            deleteAppsByStorageKeys(chunk)
        }
        scannedApps.forEach { scanned ->
            val existing = getAppByStorageKey(scanned.storageKey)
            if (existing == null) {
                insertApp(scanned)
            } else {
                val updated = updateSourceMetadata(
                    appId = existing.id,
                    sourceTitle = scanned.sourceTitle,
                    sourceVendor = scanned.sourceVendor,
                    sourceVersion = scanned.sourceVersion,
                    sourceDescription = scanned.sourceDescription,
                    iconRevision = scanned.iconRevision,
                )
                check(updated == 1) { "Filesystem reconciliation lost app ${existing.id}" }
            }
        }
    }

    /**
     * Commits the catalog side of a successful filesystem install/reinstall without replacing
     * Library-owned state. Existing rows keep overrides, favorites, added_at, stats and collection
     * memberships. A storage-key change requires a separate intentional migration path.
     */
    @Transaction
    open suspend fun recordInstalledApp(existingId: Long?, metadata: InstalledAppMetadata): Long {
        val existing = when {
            existingId != null -> getApp(existingId)
                ?: error("Installed app id disappeared during catalog update: $existingId")
            else -> getAppByStorageKey(metadata.storageKey)
        }
        if (existing != null) {
            check(existing.storageKey == metadata.storageKey) {
                "Refusing implicit storage-key migration from ${existing.storageKey} to ${metadata.storageKey}"
            }
            val updated = updateSourceMetadata(
                appId = existing.id,
                sourceTitle = metadata.sourceTitle,
                sourceVendor = metadata.sourceVendor,
                sourceVersion = metadata.sourceVersion,
                sourceDescription = metadata.sourceDescription,
                iconRevision = metadata.iconRevision,
            )
            check(updated == 1) { "Installed app catalog update lost row ${existing.id}" }
            return existing.id
        }
        return insertApp(
            LibraryAppEntity(
                storageKey = metadata.storageKey,
                sourceTitle = metadata.sourceTitle,
                sourceVendor = metadata.sourceVendor,
                sourceVersion = metadata.sourceVersion,
                sourceDescription = metadata.sourceDescription,
                addedAt = metadata.addedAt,
                iconRevision = metadata.iconRevision,
            ),
        )
    }

    private companion object {
        // SQLite on the API-23 baseline may expose the historical 999-variable ceiling. Leave room
        // for any statement-internal binds and keep the transaction atomic across chunks.
        const val SAFE_DELETE_BIND_COUNT = 900
    }
}
