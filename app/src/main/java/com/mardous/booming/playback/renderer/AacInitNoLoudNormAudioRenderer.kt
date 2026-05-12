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
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@UnstableApi
class AacInitNoLoudNormAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink
) {

    override fun supportsFormat(
        mediaCodecSelector: MediaCodecSelector,
        format: Format
    ): Int {
        if (!MimeTypes.AUDIO_AAC.equals(format.sampleMimeType, ignoreCase = true)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        return super.supportsFormat(mediaCodecSelector, format)
    }

    @SuppressLint("InlinedApi") // KEY_AAC_DRC_TARGET_REFERENCE_LEVEL added in API 21
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxInputSize: Int,
        codecOperatingRate: Float
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate)
        // Disable loudness normalisation for all AAC profiles.
        mediaFormat.setInteger(MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL, -1)
        return mediaFormat
    }
}
