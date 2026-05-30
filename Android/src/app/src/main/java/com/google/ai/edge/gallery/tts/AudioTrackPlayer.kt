package com.google.ai.edge.gallery.tts

import android.media.AudioTrack
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioTrackPlayer @Inject constructor(private val audioTrack: AudioTrack) {
    fun play() {
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play()
        }
    }

    fun write(pcmData: ShortArray, sampleCount: Int) {
        audioTrack.write(pcmData, 0, sampleCount)
    }

    fun stop() {
        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.stop()
        }
        audioTrack.flush()
    }

    fun reset() {
        stop()
    }

    fun release() {
        try {
            audioTrack.stop()
        } catch (_: IllegalStateException) {}
        audioTrack.release()
    }

    val isPlaying: Boolean get() = audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
}
