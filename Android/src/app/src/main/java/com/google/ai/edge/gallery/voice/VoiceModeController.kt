package com.google.ai.edge.gallery.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.ai.edge.gallery.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceModeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vadWrapper: SileroVadWrapper,
    private val muteSignal: VoiceModeMuteSignal,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var recognizer: SpeechRecognizer? = null
    private val isListening = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    @Volatile
    private var silenceJob: Job? = null
    private val pcmBuffer = ShortArray(FRAME_SAMPLES)
    private val speechActive = AtomicBoolean(false)
    private val needsFlush = AtomicBoolean(false)

    companion object {
        private const val TAG = "VoiceModeController"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val SILENCE_TIMEOUT_MS = 1200L
        // TODO: Expose SILENCE_TIMEOUT_MS as a user-configurable setting in a future iteration.
        // Values below 800ms will feel abrupt for natural speech.
        // Values above 2000ms will feel sluggish.
        const val FRAME_SAMPLES = 512
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.firstOrNull() ?: ""
            _events.tryEmit(VoiceEvent.UtteranceReady(transcript))
            audioRecord?.startRecording()  // Resume VAD loop
            needsFlush.set(true)
            speechActive.set(false)
            silenceJob?.cancel()
            silenceJob = null
        }

        override fun onError(error: Int) {
            Log.e(TAG, "SpeechRecognizer error: $error")
            _events.tryEmit(VoiceEvent.RecognitionError(error))
            audioRecord?.startRecording()  // Resume VAD loop even on failure
            needsFlush.set(true)
            speechActive.set(false)
            silenceJob?.cancel()
            silenceJob = null
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startRecognizerListening() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer?.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }
        recognizer?.startListening(intent)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening(scope: CoroutineScope) {
        if (!isListening.compareAndSet(false, true)) return

        vadWrapper.initialize(context)

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufSize * 2
        )

        needsFlush.set(true)

        // Collect mute signal
        scope.launch {
            muteSignal.muted.collect { muted ->
                isMuted.set(muted)
                if (muted) {
                    withContext(Dispatchers.Main) {
                        recognizer?.cancel()
                    }
                }
            }
        }

        // VAD read loop
        scope.launch(ioDispatcher) {
            audioRecord?.startRecording()
            _events.emit(VoiceEvent.ListeningStarted)

            while (isListening.get()) {
                if (needsFlush.compareAndSet(true, false)) {
                    while (isListening.get()) {
                        val read = audioRecord?.read(pcmBuffer, 0, FRAME_SAMPLES) ?: -1
                        if (read == FRAME_SAMPLES) {
                            if (!vadWrapper.isSpeech(pcmBuffer)) {
                                break
                            }
                        } else {
                            delay(50)
                        }
                    }
                }

                val read = audioRecord?.read(pcmBuffer, 0, FRAME_SAMPLES) ?: -1
                if (read != FRAME_SAMPLES) {
                    delay(50)
                    continue
                }
                if (isMuted.get()) {
                    speechActive.set(false)
                    continue
                }

                val speech = vadWrapper.isSpeech(pcmBuffer)

                if (speech) {
                    if (speechActive.compareAndSet(false, true)) {
                        _events.emit(VoiceEvent.SpeechDetected)
                        withContext(Dispatchers.Main) {
                            audioRecord?.stop()  // Release mic so SpeechRecognizer can acquire it
                            startRecognizerListening()
                        }
                    }
                    silenceJob?.cancel()
                    silenceJob = null
                } else {
                    if (speechActive.get()) {
                        if (silenceJob == null) {
                            silenceJob = scope.launch {
                                delay(SILENCE_TIMEOUT_MS)
                                _events.emit(VoiceEvent.SilenceTimeout(0))
                                withContext(Dispatchers.Main) {
                                    recognizer?.stopListening()
                                }
                                speechActive.set(false)
                                silenceJob = null
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopListening() {
        isListening.set(false)
        silenceJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        speechActive.set(false)
        CoroutineScope(Dispatchers.Main).launch {
            recognizer?.stopListening()
        }
        _events.tryEmit(VoiceEvent.ListeningStopped)
    }

    fun destroy() {
        stopListening()
        vadWrapper.close()
        CoroutineScope(Dispatchers.Main).launch {
            recognizer?.destroy()
            recognizer = null
        }
    }
}
