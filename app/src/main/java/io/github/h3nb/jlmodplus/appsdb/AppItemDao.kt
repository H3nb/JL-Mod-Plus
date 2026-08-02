/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2020-2023 Yury Kharchenko
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

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.h3nb.jlmodplus.applist.AppItem
import kotlinx.coroutines.flow.Flow

@Dao
interface AppItemDao {
    @RawQuery(observedEntities = [AppItem::class])
    fun getAll(query: SupportSQLiteQuery): Flow<List<AppItem>>

    @RawQuery(observedEntities = [AppItem::class])
    suspend fun getAllSingle(query: SupportSQLiteQuery): List<AppItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: AppItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(items: List<AppItem>)

    @Update
    suspend fun update(item: AppItem)

    @Delete
    suspend fun delete(item: AppItem)

    @Delete
    suspend fun delete(items: List<AppItem>)

    @Query("DELETE FROM apps")
    suspend fun deleteAll()

    @Query("SELECT * FROM apps WHERE title = :name AND author = :vendor")
    fun get(name: String, vendor: String): AppItem?

    @Query("SELECT * FROM apps WHERE id = :id")
    fun get(id: Int): AppItem?
}
