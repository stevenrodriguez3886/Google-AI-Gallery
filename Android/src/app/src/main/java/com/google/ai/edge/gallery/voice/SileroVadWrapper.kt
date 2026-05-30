package com.google.ai.edge.gallery.voice

import android.content.Context
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SileroVadWrapper @Inject constructor() {

    private var vad: VadSilero? = null
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.NORMAL)
            .setSpeechDurationMs(150)
            .setSilenceDurationMs(300)
            .build()
        initialized = true
    }

    /**
     * Returns true if the frame contains speech.
     * [frame] must be exactly 512 samples of 16kHz mono PCM.
     */
    fun isSpeech(frame: ShortArray): Boolean = vad?.isSpeech(frame) ?: false

    fun close() {
        vad?.close()
        vad = null
        initialized = false
    }
}
