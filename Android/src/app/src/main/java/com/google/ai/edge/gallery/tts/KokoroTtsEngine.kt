package com.google.ai.edge.gallery.tts

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KokoroTtsEngine @Inject constructor(
    private val nativeHandle: Long
) {
    external fun nativeEnqueue(handle: Long, text: String)
    external fun nativePopPcm(handle: Long, outBuffer: ShortArray, maxSamples: Int): Int
    external fun nativeAvailable(handle: Long): Int
    external fun nativeReset(handle: Long)
    external fun nativeDestroy(handle: Long)

    fun enqueue(text: String) { if (nativeHandle != 0L) nativeEnqueue(nativeHandle, text) }
    fun popPcm(buf: ShortArray, max: Int): Int = if (nativeHandle != 0L) nativePopPcm(nativeHandle, buf, max) else 0
    fun available(): Int = if (nativeHandle != 0L) nativeAvailable(nativeHandle) else 0
    fun reset() { if (nativeHandle != 0L) nativeReset(nativeHandle) }
    fun destroy() { if (nativeHandle != 0L) nativeDestroy(nativeHandle) }

    companion object {
        init {
            System.loadLibrary("gallery_voice_native")
        }
        
        @JvmStatic
        external fun nativeCreate(modelPath: String, voiceData: FloatArray): Long
    }
}
