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

import android.content.ContentResolver
import android.content.Context
import com.mardous.booming.data.local.MetadataReader
import com.mardous.booming.data.local.room.ExternalSongDao
import com.mardous.booming.data.local.room.ExternalSongEntity
import com.mardous.booming.data.local.room.PlayCountDao
import com.mardous.booming.data.local.room.PlaylistDao
import com.mardous.booming.data.mapper.toSong
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.asExternalIdentityUri
import com.mardous.booming.extensions.media.isUriReadable
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Stable synthetic IDs for imported external songs, in a reserved negative band
 * `[-EXTERNAL_ID_BASE, -(EXTERNAL_ID_BASE + 2^31 - 1)]` that is guaranteed disjoint
 * from MediaStore `_ID`s (non-negative, and on large/restored media DBs they can
 * exceed any positive threshold) and from the -1/-2 sentinels (`Song.emptySong`,
 * `Artist.VARIOUS_ARTISTS_ID`). Derived from the persisted content URI, so they
 * survive restarts and are identical across re-imports.
 */
const val EXTERNAL_ID_BASE = 1_000_000_000L

fun externalSongId(uri: String): Long = -(EXTERNAL_ID_BASE + (uri.hashCode() and 0x7FFFFFFF))

fun externalAlbumId(album: String, albumArtist: String?): Long =
    -(EXTERNAL_ID_BASE + ("$album\u0000${albumArtist.orEmpty()}".hashCode() and 0x7FFFFFFF))

/**
 * Tag fields freshly read from an imported external song's file (the same taglib read
 * the Song Details sheet performs for display). Null fields mean "not read or not
 * present in the file" and leave the stored value untouched during a metadata refresh.
 */
data class ExternalSongTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val track: Int? = null
)

/**
 * Builds the tag fields for an imported external song's metadata refresh from a
 * freshly-performed taglib read (the same read Song Details uses for display and
 * ReplayGain extraction). Matches the session probe's parsing of composed track
 * fields (e.g. "3/12"). Missing fields come back null so the refresh keeps the
 * stored values.
 */
fun MetadataReader.toExternalSongTags(): ExternalSongTags = ExternalSongTags(
    title = first(MetadataReader.TITLE),
    artist = merge(MetadataReader.ARTIST),
    album = first(MetadataReader.ALBUM),
    albumArtist = first(MetadataReader.ALBUM_ARTIST),
    genre = merge(MetadataReader.GENRE),
    track = value(MetadataReader.TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull()
)

interface ExternalSongRepository {

    suspend fun all(): List<Song>

    suspend fun search(query: String): List<Song>

    suspend fun albums(): List<Album>

    suspend fun albumSongs(albumId: Long): List<Song>

    /**
     * Returns the imported [Song] for an external file identified by its content URI, or
     * null if the file was never imported. The query is canonicalized first (tree form ->
     * document form via [asExternalIdentityUri]), so a session-only ACTION_VIEW open of an
     * imported file matches its stored document-form row.
     */
    suspend fun songByUri(uri: String): Song?

    suspend fun add(song: ExternalSongEntity)

    suspend fun remove(uri: String)

    /**
     * Reconciles the stored row for an imported external song against freshly-read file
     * state: [tags] come from the taglib read the caller already performed, while size and
     * last-modified are queried from the provider here. The round-trip is bounded by a
     * timeout so a slow or hung DocumentsProvider (e.g. network-backed) can never produce a
     * stale Room write — the blocking query itself cannot be aborted mid-flight, but the
     * write window is. The Room lookup canonicalizes the URI (tree form -> document form);
     * provider queries use the raw [uri], which is what the persistable grant covers. Blank
     * tag fields leave the stored value untouched, as do unknown size/mtime; duration is
     * never refreshed (taglib cannot reliably determine it for every file). The row is
     * written only when something actually changed, and the album id is recomputed whenever
     * the album/album-artist pair changed (it is derived from those names). Returns the
     * updated row, or null when the song is no longer imported or nothing changed.
     */
    suspend fun refreshMetadata(uri: String, tags: ExternalSongTags): ExternalSongEntity?

    /**
     * Tags-only variant of [refreshMetadata]: upserts the freshly-read tag fields
     * (title/artist/album/albumArtist/genre/track, recomputing the album id when the
     * album names changed) without any provider IO. Used by the playback path, which
     * already performed the taglib read for ReplayGain and reuses it here; size and
     * last-modified are left to [refreshMetadata] (Song Details).
     */
    suspend fun refreshTags(uri: String, tags: ExternalSongTags): ExternalSongEntity?

    /**
     * Removes stored songs whose URI can no longer be read (e.g. an unplugged OTG
     * provider whose persistable grant is gone). Returns the removed URIs.
     */
    suspend fun pruneUnreadable(): List<String>
}

class RealExternalSongRepository(
    private val context: Context,
    private val dao: ExternalSongDao,
    private val playlistDao: PlaylistDao,
    private val playCountDao: PlayCountDao
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
        // Canonicalize tree-form URIs (ACTION_VIEW on tree-based providers) to the
        // document form the file picker returns, so a session-only open of an imported
        // file matches its Room row.
        dao.byUri(uri.asExternalIdentityUri())?.toSong()
    }

    override suspend fun add(song: ExternalSongEntity) = withContext(Dispatchers.IO) {
        dao.insert(song)
    }

    override suspend fun remove(uri: String) = withContext(Dispatchers.IO) {
        dao.deleteByUri(uri)
        playlistDao.deleteSongsByExternalUri(uri)
        playCountDao.deleteByExternalUri(uri)
    }

    override suspend fun refreshMetadata(uri: String, tags: ExternalSongTags): ExternalSongEntity? =
        withContext(Dispatchers.IO) {
            withTimeout(METADATA_REFRESH_TIMEOUT_MS) {
                // Canonicalize tree-form URIs (ACTION_VIEW on tree-based providers) to
                // the document form the picker stored, matching songByUri(). Provider
                // queries below use the raw uri — the persistable grant covers it.
                val entity = dao.byUri(uri.asExternalIdentityUri()) ?: return@withTimeout null
                ensureActive()
                val size = ExternalFileMetadata.size(context.contentResolver, uri.toUri())
                val lastModified =
                    ExternalFileMetadata.lastModifiedSeconds(context.contentResolver, uri.toUri())
                // The blocking provider calls above cannot be aborted mid-flight, but a
                // cancellation (sheet closed) or timeout must never produce a stale write.
                ensureActive()
                upsertIfChanged(
                    uri,
                    entity,
                    applyTags(entity, tags).copy(
                        size = size?.takeIf { it > 0 } ?: entity.size,
                        dateModified = lastModified ?: entity.dateModified
                    )
                )
            }
        }

    override suspend fun refreshTags(uri: String, tags: ExternalSongTags): ExternalSongEntity? =
        withContext(Dispatchers.IO) {
            val entity = dao.byUri(uri.asExternalIdentityUri()) ?: return@withContext null
            upsertIfChanged(uri, entity, applyTags(entity, tags))
        }

    /** Applies the freshly-read tag fields to a stored row; blank fields keep stored values. */
    private fun applyTags(entity: ExternalSongEntity, tags: ExternalSongTags): ExternalSongEntity {
        val newAlbum = tags.album?.takeIf { it.isNotBlank() } ?: entity.album
        val newAlbumArtist = tags.albumArtist?.takeIf { it.isNotBlank() } ?: entity.albumArtist
        return entity.copy(
            title = tags.title?.takeIf { it.isNotBlank() } ?: entity.title,
            artist = tags.artist?.takeIf { it.isNotBlank() } ?: entity.artist,
            album = newAlbum,
            albumArtist = newAlbumArtist,
            genre = tags.genre?.takeIf { it.isNotBlank() } ?: entity.genre,
            // Duration is deliberately not refreshed: taglib cannot reliably
            // determine it for every file, so the import-time value is kept.
            track = tags.track ?: entity.track,
            // albumId is derived from the album/album-artist names — recompute it
            // whenever that pair changed, or the row would land under a stale id.
            albumId = if (newAlbum != entity.album || newAlbumArtist != entity.albumArtist) {
                externalAlbumId(newAlbum, newAlbumArtist)
            } else {
                entity.albumId
            }
        )
    }

    /**
     * Writes [updated] only when it differs from [entity] and the row still exists (it may
     * have been removed while we were reading, so the upsert must not resurrect a deleted
     * song). Returns the written row, or null when nothing changed.
     */
    private suspend fun upsertIfChanged(
        uri: String,
        entity: ExternalSongEntity,
        updated: ExternalSongEntity
    ): ExternalSongEntity? {
        if (updated == entity) return null
        if (dao.byUri(uri.asExternalIdentityUri()) == null) return null
        dao.insert(updated)
        return updated
    }

    override suspend fun pruneUnreadable(): List<String> = withContext(Dispatchers.IO) {
        // One cheap IPC: the app's persisted URI grants. A content URI without a grant can
        // never be read, so it is pruned without probing the (possibly hung/dead) provider;
        // only granted content URIs (and file URIs, which need no grant) get the probe.
        val persistedGrantUris = context.contentResolver.persistedUriPermissions
            .map { it.uri }
            .toHashSet()
        val removed = dao.all().filter { entity ->
            val uri = entity.uri.toUri()
            val mayBeReadable = uri.scheme == ContentResolver.SCHEME_FILE ||
                (uri.scheme == ContentResolver.SCHEME_CONTENT && uri in persistedGrantUris)
            !mayBeReadable || !context.isUriReadable(uri)
        }.map { it.uri }
        if (removed.isNotEmpty()) {
            dao.deleteByUris(removed)
            playlistDao.deleteSongsByExternalUris(removed)
            playCountDao.deleteByExternalUris(removed)
        }
        removed
    }

    private companion object {
        // Bounds the write window; a hung DocumentsProvider (e.g. network-backed) can
        // never produce a stale Room write — the blocking query itself cannot be aborted
        // mid-flight, but the coroutine is cancelled at the deadline.
        const val METADATA_REFRESH_TIMEOUT_MS = 5_000L
    }
}
