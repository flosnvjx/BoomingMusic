/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ExternalSongDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: ExternalSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<ExternalSongEntity>)

    /**
     * In-place update used by the metadata reconcile paths (refreshTags/refreshMetadata).
     * Deliberately an `@Update` rather than `INSERT OR REPLACE`: REPLACE is DELETE +
     * INSERT, which churns the rowid so the row re-appends to the table tail (re-ordering
     * every query without an ORDER BY, and needlessly rewriting the row). [all] pins the
     * Songs tab's DateAdded tie-break with `ORDER BY date_added, uri`; updating in place
     * additionally keeps all other unordered `external_songs` reads stable across
     * reconciles.
     */
    @Update
    suspend fun update(song: ExternalSongEntity)

    @Query("SELECT * FROM external_songs ORDER BY date_added, uri")
    suspend fun all(): List<ExternalSongEntity>

    @Query("SELECT * FROM external_songs WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): ExternalSongEntity?

    @Query("SELECT * FROM external_songs WHERE song_id = :songId LIMIT 1")
    suspend fun bySongId(songId: Long): ExternalSongEntity?

    @Query("SELECT * FROM external_songs WHERE album_id = :albumId")
    suspend fun byAlbumId(albumId: Long): List<ExternalSongEntity>

    @Query(
        "SELECT * FROM external_songs WHERE title LIKE :query OR artist LIKE :query OR album LIKE :query"
    )
    suspend fun search(query: String): List<ExternalSongEntity>

    @Query("DELETE FROM external_songs WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM external_songs WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM external_songs")
    suspend fun clear()
}
