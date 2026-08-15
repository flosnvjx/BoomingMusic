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

package com.mardous.booming.data.local.repository

import android.content.Context
import com.mardous.booming.data.local.room.ExternalSongDao
import com.mardous.booming.data.local.room.ExternalSongEntity
import com.mardous.booming.data.local.room.PlaylistDao
import com.mardous.booming.data.mapper.toSong
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.isUriReadable
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stable synthetic IDs for imported external songs, in a reserved high band that never
 * collides with MediaStore `_ID`s (small positive integers) or the -1/-2 sentinels
 * (`Song.emptySong`, `Artist.VARIOUS_ARTISTS_ID`). Derived from the persisted content
 * URI, so they survive restarts and are identical across re-imports.
 */
const val EXTERNAL_ID_BASE = 1_000_000_000L

fun externalSongId(uri: String): Long = EXTERNAL_ID_BASE + (uri.hashCode() and 0x7FFFFFFF)

fun externalAlbumId(album: String, albumArtist: String?): Long =
    EXTERNAL_ID_BASE + ("$album\u0000${albumArtist.orEmpty()}".hashCode() and 0x7FFFFFFF)

interface ExternalSongRepository {

    suspend fun all(): List<Song>

    suspend fun search(query: String): List<Song>

    suspend fun albums(): List<Album>

    suspend fun albumSongs(albumId: Long): List<Song>

    suspend fun songByUri(uri: String): Song?

    suspend fun add(song: ExternalSongEntity)

    suspend fun remove(uri: String)

    /**
     * Removes stored songs whose URI can no longer be read (e.g. an unplugged OTG
     * provider whose persistable grant is gone). Returns the removed URIs.
     */
    suspend fun pruneUnreadable(): List<String>
}

class RealExternalSongRepository(
    private val context: Context,
    private val dao: ExternalSongDao,
    private val playlistDao: PlaylistDao
) : ExternalSongRepository {

    override suspend fun all(): List<Song> = withContext(Dispatchers.IO) {
        dao.all().map { it.toSong() }
    }

    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        dao.search("%$query%").map { it.toSong() }
    }

    override suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        dao.all()
            .groupBy { it.albumId }
            .map { (albumId, entries) ->
                val songs = entries.map { it.toSong() }
                Album(
                    id = albumId,
                    artistName = songs.first().artistName,
                    albumArtistName = songs.first().albumArtistName,
                    year = -1,
                    songs = songs
                )
            }
    }

    override suspend fun albumSongs(albumId: Long): List<Song> = withContext(Dispatchers.IO) {
        dao.byAlbumId(albumId).map { it.toSong() }
    }

    override suspend fun songByUri(uri: String): Song? = withContext(Dispatchers.IO) {
        dao.byUri(uri)?.toSong()
    }

    override suspend fun add(song: ExternalSongEntity) = withContext(Dispatchers.IO) {
        dao.insert(song)
    }

    override suspend fun remove(uri: String) = withContext(Dispatchers.IO) {
        dao.deleteByUri(uri)
        playlistDao.deleteSongsByExternalUri(uri)
    }

    override suspend fun pruneUnreadable(): List<String> = withContext(Dispatchers.IO) {
        val removed = dao.all().filter { !context.isUriReadable(it.uri.toUri()) }.map { it.uri }
        removed.forEach {
            dao.deleteByUri(it)
            playlistDao.deleteSongsByExternalUri(it)
        }
        removed
    }
}
