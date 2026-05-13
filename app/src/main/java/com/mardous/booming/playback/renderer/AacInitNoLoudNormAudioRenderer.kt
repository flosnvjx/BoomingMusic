package com.mardous.booming.playback.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.mardous.booming.data.model.replaygain.ReplayGainMode
import com.mardous.booming.playback.processor.ReplayGainAudioProcessor

@UnstableApi
class AacInitNoLoudNormAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    private val replayGainProcessor: ReplayGainAudioProcessor
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink
) {

    /** The DRC level that was last used when configuring the codec. */
    private var lastConfiguredDrcLevel: Int = Int.MIN_VALUE

    override fun supportsFormat(
        mediaCodecSelector: MediaCodecSelector,
        format: Format
    ): Int {
        if (!MimeTypes.AUDIO_AAC.equals(format.sampleMimeType, ignoreCase = true)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        return super.supportsFormat(mediaCodecSelector, format)
    }

    @SuppressLint("InlinedApi")
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxInputSize: Int,
        codecOperatingRate: Float
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val drcLevel = computeDrcLevel(format)
            mediaFormat.setInteger(MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL, drcLevel)
            lastConfiguredDrcLevel = drcLevel
        }
        return mediaFormat
    }

    /**
     * Forces a codec re‑initialization when the DRC level required
     * for the new track differs from the level that is currently active.
     */
    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format
    ): DecoderReuseEvaluation {
        val newDrcLevel = computeDrcLevel(newFormat)
        if (lastConfiguredDrcLevel != Int.MIN_VALUE && newDrcLevel != lastConfiguredDrcLevel) {
            return DecoderReuseEvaluation(
                codecInfo.name,
                oldFormat,
                newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED
            )
        }
        return super.canReuseCodec(codecInfo, oldFormat, newFormat)
    }

    /** Determines the DRC level to be used for the given AAC [format]. */
    private fun computeDrcLevel(format: Format): Int {
        return when {
            !isXHeAac(format) && lastConfiguredDrcLevel != Int.MIN_VALUE -> lastConfiguredDrcLevel

            isXHeAac(format) && replayGainProcessor.mode != ReplayGainMode.Off && !replayGainProcessor.hasReplayGain -> 72

            else -> -1
        }
    }

    private fun isXHeAac(format: Format): Boolean {
        val codecs = format.codecs
        return codecs != null && codecs.contains("mp4a.40.42")
    }
}
