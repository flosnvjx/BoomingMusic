package com.mardous.booming.playback.renderer

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AacAudioRenderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink

@OptIn(UnstableApi::class)
class BoomingAacAudioRenderer(
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink
) : AacAudioRenderer(eventHandler, eventListener, audioSink, false)
