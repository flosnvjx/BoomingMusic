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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A song imported into the in-app library from a provider MediaStore does not manage
 * (e.g. picked via the system file picker from a DocumentsProvider). The [uri] grant is
 * persistable, so the song survives restarts; [songId] and [albumId] are synthetic IDs
 * in a reserved negative band (see [com.mardous.booming.data.local.repository.externalSongId]
 * and [com.mardous.booming.data.local.repository.externalAlbumId]), disjoint from
 * MediaStore ids and the -1/-2 sentinels.
 */
@Entity(tableName = "external_songs")
data class ExternalSongEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "album_id")
    val albumId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    @ColumnInfo(name = "album_artist")
    val albumArtist: String?,
    @ColumnInfo(name = "genre")
    val genre: String?,
    @ColumnInfo(name = "duration")
    val duration: Long,
    @ColumnInfo(name = "size")
    val size: Long,
    @ColumnInfo(name = "date_added")
    val dateAdded: Long,
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,
    @ColumnInfo(name = "track")
    val track: Int
)
