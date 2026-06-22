package com.mardous.booming.ui.screen.info

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.mardous.booming.data.local.MetadataReader
import com.mardous.booming.data.local.repository.Repository
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
import com.mardous.booming.extensions.media.replayGainStr
import com.mardous.booming.extensions.media.songDurationStr
import com.mardous.booming.extensions.utilities.dateStr
import com.mardous.booming.extensions.utilities.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaudiotagger.audio.AudioHeader
import java.io.File

class InfoViewModel(private val repository: Repository) : ViewModel() {

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
            val replayGain = song.replayGainStr(context)

            val metadataReader = MetadataReader(song.uri)
            if (!metadataReader.hasMetadata) {
                SongInfo(
                    playCount = playCount,
                    skipCount = skipCount,
                    lastPlayedDate = lastPlayed,
                    filePath = File(song.data).getPrettyAbsolutePath(),
                    fileSize = song.size.asReadableFileSize(),
                    trackLength = trackLength,
                    dateModified = dateModified,
                    title = song.title,
                    albumYear = year,
                    replayGain = replayGain
                )
            } else {
                val file = File(song.data)
                val filePath = file.getPrettyAbsolutePath()
                val fileSize = file.getHumanReadableSize()

                val audioHeaderInfo = getAudioHeader(file.toAudioFile()?.audioHeader, metadataReader)

                val title = metadataReader.first(MetadataReader.TITLE)
                val album = metadataReader.first(MetadataReader.ALBUM)
                val artist = metadataReader.merge(MetadataReader.ARTIST)
                val albumArtist = metadataReader.first(MetadataReader.ALBUM_ARTIST)

                // prepare to parse composed tracknumber field (e.g. AAC format tag)
                val trackNumberRaw = metadataReader.value(MetadataReader.TRACK_NUMBER)
                val trackTotalRaw = metadataReader.value(MetadataReader.TRACK_TOTAL)
                val (trackNumber, trackTotal) = parseNumberAndTotal(trackNumberRaw, trackTotalRaw)
                val trackNumberDisplay = formatNumberAndTotal(trackNumber, trackTotal)

                // same for discnumber
                val discNumberRaw = metadataReader.value(MetadataReader.DISC_NUMBER)
                val discTotalRaw = metadataReader.value(MetadataReader.DISC_TOTAL)
                val (discNumber, discTotal) = parseNumberAndTotal(discNumberRaw, discTotalRaw)
                val discNumberDisplay = formatNumberAndTotal(discNumber, discTotal)

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
                    albumYear = year,
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
                )
            }
        }

        _songInfoUiState.value = uiState.copy(
            isLoading = false,
            isSuccess = songInfo.isSuccess,
            info = songInfo.getOrDefault(SongInfo.Empty)
        )
    }

    private fun parseNumberAndTotal(raw: String?, totalRaw: String?): Pair<Int?, Int?> {
        if (raw.isNullOrBlank()) return null to null

        val slashIndex = raw.indexOf('/')
        if (slashIndex > 0) {
            val numberPart = raw.substring(0, slashIndex).trim()
            val totalPart = raw.substring(slashIndex + 1).trim()
            val number = numberPart.toIntOrNull()
            val total = totalPart.toIntOrNull()
            if (number != null) {
                return number to (total ?: totalRaw?.toIntOrNull())
            }
        }

        val number = raw.toIntOrNull()
        if (number != null) {
            return number to totalRaw?.toIntOrNull()
        }

        return null to null
    }

    private fun formatNumberAndTotal(number: Int?, total: Int?): String? {
        if (number == null) return null
        if (number == 0 && total == 0) return null
        if (total == null || total == 0) {
            return number.toString().padStart(2, '0')
        }
        return "%02d/%02d".format(number, total)
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
}
