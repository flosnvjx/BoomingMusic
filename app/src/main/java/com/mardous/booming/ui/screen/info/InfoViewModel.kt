package com.mardous.booming.ui.screen.info

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.mardous.booming.data.local.MetadataReader
import com.mardous.booming.data.local.ReplayGainTagExtractor
import com.mardous.booming.data.local.repository.ExternalSongRepository
import com.mardous.booming.data.local.repository.ExternalSongTags
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.local.repository.toExternalSongTags
import com.mardous.booming.data.mapper.toPlayCount
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.files.asReadableFileSize
import com.mardous.booming.extensions.files.formatFixed
import com.mardous.booming.extensions.files.getHumanReadableSize
import com.mardous.booming.extensions.files.getPrettyAbsolutePath
import com.mardous.booming.extensions.files.toAudioFile
import com.mardous.booming.extensions.media.asNumberOfTimes
import com.mardous.booming.extensions.media.isImportedExternal
import com.mardous.booming.extensions.media.replayGainStr
import com.mardous.booming.extensions.media.songDurationStr
import com.mardous.booming.extensions.utilities.dateStr
import com.mardous.booming.extensions.utilities.format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jaudiotagger.audio.AudioHeader
import java.io.File
import java.util.Date

class InfoViewModel(
    private val repository: Repository,
    private val externalSongRepository: ExternalSongRepository
) : ViewModel() {

    private val _songInfoUiState = MutableStateFlow(
        SongInfoUiState(
            isLoading = true,
            isSuccess = false
        )
    )
    val songInfoUiState = _songInfoUiState.asStateFlow()

    fun loadAlbum(id: Long): LiveData<Album> = liveData(Dispatchers.IO) {
        if (id != -1L) {
            emit(repository.albumById(id))
        } else {
            emit(Album.empty)
        }
    }

    fun loadArtist(id: Long, name: String?): LiveData<Artist> = liveData(Dispatchers.IO) {
        if (name.isNullOrEmpty()) {
            emit(repository.artistById(id))
        } else if (id == -1L) {
            emit(repository.albumArtistByName(name))
        } else {
            emit(Artist.empty)
        }
    }

    fun playInfo(songs: List<Song>): LiveData<PlayInfoResult> = liveData(Dispatchers.IO) {
        val playCountEntities = repository.findSongsInPlayCount(songs).sortedByDescending { it.playCount }
        if (playCountEntities.isEmpty()) {
            emit(PlayInfoResult(-1, -1, -1, songs.map { it.toPlayCount() }))
        } else {
            val totalPlayCount = playCountEntities.sumOf { it.playCount }
            val totalSkipCount = playCountEntities.sumOf { it.skipCount }
            val lastPlayDate = playCountEntities.maxOf { it.timePlayed }
            emit(PlayInfoResult(totalPlayCount, totalSkipCount, lastPlayDate, playCountEntities))
        }
    }

    fun refreshSongInfo(context: Context, song: Song) = viewModelScope.launch(Dispatchers.IO) {
        val uiState = SongInfoUiState(isLoading = true, isSuccess = false)
        _songInfoUiState.value = uiState

        val songInfo = runCatching {
            val playCountEntity = repository.findSongInPlayCount(song.id) ?: song.toPlayCount()
            val playCount = playCountEntity.playCount.asNumberOfTimes(context)
            val skipCount = playCountEntity.skipCount.asNumberOfTimes(context)
            val lastPlayed = context.dateStr(playCountEntity.timePlayed)

            val dateModified = song.dateModified.format(context)
            val year = if (song.year > 0) song.year.toString() else null
            val trackLength = song.songDurationStr()

            val metadataReader = MetadataReader(song.uri)

            // Reuse this same taglib read to warm the ReplayGain cache: playback and the
            // ReplayGain row below then hit the cache instead of re-reading the file.
            ReplayGainTagExtractor.cacheReplayGain(song, metadataReader.all())
            val replayGain = song.replayGainStr(context)

            // External songs have no local path (data == ""), so a File-based size
            // would always read 0. Imported ones show the Room-recorded size right
            // away (a background reconcile refreshes it from the provider); session-
            // only songs have no Room row, so the provider is the only source.
            val file = File(song.data)
            val filePath = file.getPrettyAbsolutePath()
            val fileSize = when {
                song.isImportedExternal -> song.size.takeIf { it > 0 }?.asReadableFileSize()
                song.externalUri != null -> externalSongFileSize(context, song.uri, song.size)
                else -> file.getHumanReadableSize()
            }

            if (!metadataReader.hasMetadata) {
                SongInfo(
                    playCount = playCount,
                    skipCount = skipCount,
                    lastPlayedDate = lastPlayed,
                    filePath = filePath,
                    fileSize = fileSize,
                    trackLength = trackLength,
                    dateModified = dateModified,
                    title = song.title,
                    albumYear = year,
                    replayGain = replayGain
                ) to null
            } else {
                val audioHeaderInfo = getAudioHeader(file.toAudioFile()?.audioHeader, metadataReader)

                val title = metadataReader.first(MetadataReader.TITLE)
                val album = metadataReader.first(MetadataReader.ALBUM)
                val artist = metadataReader.merge(MetadataReader.ARTIST)
                val albumArtist = metadataReader.first(MetadataReader.ALBUM_ARTIST)

                val albumYear = metadataReader.value(MetadataReader.YEAR)

                // prepare to parse composed tracknumber field (e.g. AAC format tag)
                val trackNumberRaw = metadataReader.value(MetadataReader.TRACK_NUMBER)
                val trackTotalRaw = metadataReader.value(MetadataReader.TRACK_TOTAL)
                val trackNumberDisplay = buildDisplayNumber(trackNumberRaw, trackTotalRaw)

                // same for discnumber
                val discNumberRaw = metadataReader.value(MetadataReader.DISC_NUMBER)
                val discTotalRaw = metadataReader.value(MetadataReader.DISC_TOTAL)
                val discNumberDisplay = buildDisplayNumber(discNumberRaw, discTotalRaw)

                val composer = metadataReader.merge(MetadataReader.COMPOSER)
                val conductor = metadataReader.merge(MetadataReader.PRODUCER)
                val publisher = metadataReader.publisher()
                val catalogNumber = metadataReader.first(MetadataReader.CATALOG_NUMBER)
                val lyricist = metadataReader.merge(MetadataReader.LYRICIST)
                val arranger = metadataReader.merge(MetadataReader.ARRANGER)
                val genre = metadataReader.merge(MetadataReader.GENRE)
                val comment = cleanComment(metadataReader.value(MetadataReader.COMMENT))

                SongInfo(
                    playCount = playCount,
                    skipCount = skipCount,
                    lastPlayedDate = lastPlayed,
                    filePath = filePath,
                    fileSize = fileSize,
                    trackLength = trackLength,
                    dateModified = dateModified,
                    audioHeaderInfo = audioHeaderInfo,
                    title = title ?: song.title,
                    album = album,
                    artist = artist,
                    albumArtist = albumArtist,
                    albumYear = albumYear,
                    trackNumber = trackNumberDisplay,
                    discNumber = discNumberDisplay,
                    composer = composer,
                    conductor = conductor,
                    publisher = publisher,
                    catalogNumber = catalogNumber,
                    lyricist = lyricist,
                    arranger = arranger,
                    genre = genre,
                    replayGain = replayGain,
                    comment = comment
                ) to metadataReader.toExternalSongTags()
            }
        }.onFailure { if (it is CancellationException) throw it }

        _songInfoUiState.value = uiState.copy(
            isLoading = false,
            isSuccess = songInfo.isSuccess,
            info = songInfo.getOrDefault(SongInfo.Empty to null).first
        )

        // Reconcile imported external songs against freshly-read file state (see
        // ExternalSongRepository.refreshMetadata): the taglib read above is already
        // done for display, so persisting it plus a bounded provider size/mtime query
        // keeps the Room row in sync. Sheet-scoped — closing the sheet cancels this
        // coroutine (no stale Room write); the timeout keeps a hung (e.g. network-
        // backed) DocumentsProvider from stalling us.
        val externalUri = song.externalUri
        if (song.isImportedExternal && externalUri != null) {
            val tags = songInfo.getOrNull()?.second
            val updated = try {
                externalSongRepository.refreshMetadata(externalUri, tags ?: ExternalSongTags())
            } catch (e: TimeoutCancellationException) {
                null
            }
            val info = songInfo.getOrNull()?.first
            if (updated != null && info != null) {
                _songInfoUiState.value = _songInfoUiState.value.copy(
                    info = info.copy(
                        fileSize = updated.size.takeIf { it > 0 }?.asReadableFileSize()
                            ?: info.fileSize,
                        dateModified = updated.dateModified.takeIf { it > 0 }
                            ?.let { Date(it * 1000).format(context) } ?: info.dateModified
                    )
                )
            }
        }
    }

    /**
     * 构建显示字符串，保留原始文本（含前导零），仅当解析出的 number 和 total 都为 0 时返回 null 隐藏。
     * - 如果 rawNumber 包含 '/'，直接返回 rawNumber（原始文本已包含总数）。
     * - 否则，如果 rawTotal 不为空且解析后非零，返回 "rawNumber/rawTotal"。
     * - 否则，返回 rawNumber。
     * - 如果 rawNumber 为空或解析出 number 为 null，返回 null。
     * - 如果 number == 0 且 total == 0，返回 null。
     */
    private fun buildDisplayNumber(rawNumber: String?, rawTotal: String?): String? {
        if (rawNumber.isNullOrBlank()) return null

        if (rawNumber.contains('/')) {
            // 仍需检查是否应隐藏：解析两边数字，若都为0则隐藏
            val parts = rawNumber.split('/')
            val num = parts.getOrNull(0)?.toIntOrNull()
            val den = parts.getOrNull(1)?.toIntOrNull()
            if (num == 0 && den == 0) return null
            return rawNumber
        }

        val number = rawNumber.toIntOrNull()
        if (number == null) return null

        val total = rawTotal?.toIntOrNull()

        if (number == 0 && total == 0) return null

        return if (total != null && total != 0) {
            "$rawNumber/$rawTotal"
        } else {
            rawNumber
        }
    }

    private fun cleanComment(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.lines()
        val trimmedLines = lines.map { it.trimEnd() }
        val start = trimmedLines.indexOfFirst { it.isNotBlank() }
        if (start == -1) return null
        val end = trimmedLines.indexOfLast { it.isNotBlank() }
        return trimmedLines.subList(start, end + 1).joinToString("\n")
    }

    /**
     * Resolves the on-disk size of an external song (a provider-backed content URI
     * without a local file path). Asks the provider for [OpenableColumns.SIZE] first —
     * the file may have changed since it was imported — and falls back to the size
     * recorded on the [Song]. Returns null when neither is available so the row is
     * hidden instead of showing a misleading 0.
     */
    private suspend fun externalSongFileSize(context: Context, uri: Uri, recordedSize: Long): String? {
        val liveSize = runCatching {
            withTimeout(EXTERNAL_SIZE_QUERY_TIMEOUT_MS) {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                    }
            }
        }.getOrNull()
        val size = liveSize?.takeIf { it > 0 } ?: recordedSize.takeIf { it > 0 } ?: return null
        return size.asReadableFileSize()
    }

    private fun getAudioHeader(header: AudioHeader?, metadataReader: MetadataReader): AudioHeaderInfo {
        return AudioHeaderInfo(
            format = header?.formatFixed,
            bitrate = metadataReader.bitrate(),
            sampleRate = metadataReader.sampleRate(),
            channels = metadataReader.channelName(),
            variableBitrate = header?.isVariableBitRate == true,
            lossless = header?.isLossless == true
        )
    }

    private companion object {
        // Bounds the provider size query for session-only songs (no Room row to fall
        // back on beyond the recorded value); a hung provider must not stall the sheet.
        const val EXTERNAL_SIZE_QUERY_TIMEOUT_MS = 5_000L
    }
}
