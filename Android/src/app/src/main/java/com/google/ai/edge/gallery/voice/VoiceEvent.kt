package com.google.ai.edge.gallery.voice

sealed class VoiceEvent {
    data object ListeningStarted : VoiceEvent()
    data object SpeechDetected : VoiceEvent()
    data class SilenceTimeout(val bufferedAudioMs: Long) : VoiceEvent()
    data class UtteranceReady(val transcript: String) : VoiceEvent()
    data class RecognitionError(val code: Int) : VoiceEvent()
    data object ListeningStopped : VoiceEvent()
}
