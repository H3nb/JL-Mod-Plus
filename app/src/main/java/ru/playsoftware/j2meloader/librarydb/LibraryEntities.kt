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
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "apps",
    indices = [
        Index(value = ["storage_key"], unique = true),
        Index(value = ["source_title", "source_vendor"]),
        Index(value = ["favorite"]),
        Index(value = ["added_at"]),
        Index(value = ["last_played_at"]),
    ],
)
data class LibraryAppEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "storage_key")
    val storageKey: String,
    @ColumnInfo(name = "source_title")
    val sourceTitle: String,
    @ColumnInfo(name = "source_vendor")
    val sourceVendor: String,
    @ColumnInfo(name = "source_version")
    val sourceVersion: String,
    @ColumnInfo(name = "source_description")
    val sourceDescription: String? = null,
    @ColumnInfo(name = "custom_title")
    val customTitle: String? = null,
    @ColumnInfo(name = "custom_vendor")
    val customVendor: String? = null,
    @ColumnInfo(name = "custom_version")
    val customVersion: String? = null,
    @ColumnInfo(name = "custom_description")
    val customDescription: String? = null,
    val favorite: Boolean = false,
    @ColumnInfo(name = "added_at")
    val addedAt: Long? = null,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long? = null,
    @ColumnInfo(name = "play_count")
    val playCount: Long = 0,
    @ColumnInfo(name = "total_play_time_ms")
    val totalPlayTimeMs: Long = 0,
    @ColumnInfo(name = "icon_revision")
    val iconRevision: Long = 0,
)

@Entity(tableName = "collections")
data class LibraryCollectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "collection_apps",
    primaryKeys = ["collection_id", "app_id"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryCollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LibraryAppEntity::class,
            parentColumns = ["id"],
            childColumns = ["app_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["app_id"])],
)
data class LibraryCollectionAppEntity(
    @ColumnInfo(name = "collection_id")
    val collectionId: Long,
    @ColumnInfo(name = "app_id")
    val appId: Long,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)

/** Exactly-once play-stat reconciliation receipt. Intentionally has no app foreign key. */
@Entity(tableName = "play_stat_receipts")
data class PlayStatReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
)

/** Singleton bootstrap state for the workdir-scoped Library database. */
@Entity(tableName = "library_state")
data class LibraryStateEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "bootstrap_state")
    val bootstrapState: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
