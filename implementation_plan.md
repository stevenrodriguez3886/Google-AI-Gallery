# Android On-Device Voice Assistant — Implementation Blueprint v2.0
### Target Agent: Google Antigravity 2.0 | Architecture: Senior Android Systems Architect
### Base: Google AI Edge Gallery fork (Apache 2.0) + Kokoro ONNX TTS + Silero VAD + JNI/C++

---

> **AGENT DIRECTIVE:** This is a strict, declarative implementation plan for a **fork** of the
> Google AI Edge Gallery (`https://github.com/google-ai-edge/gallery`). Execute each Phase in
> sequence. Do not proceed to a subsequent Phase until all tasks within the current Phase are
> verified. All architectural decisions in this document are constraints — do not substitute
> libraries, change API signatures, or alter data flow without explicit instruction.
>
> **Execution model:** This document is divided into two tracks that run sequentially:
> - **Track A (Phases 0–2):** Fork setup and native audio layer. Pure additions to the Gallery.
>   No Gallery source files are modified.
> - **Track B (Phases 3–5):** Voice mode integration. Specific Gallery source files are modified
>   at precisely identified locations. The agent MUST NOT modify any Gallery file not listed in
>   this document.

---

## SECTION 1 — Project Context & Fork Strategy

### 1.1 — What the Gallery Is

The Google AI Edge Gallery (`com.google.ai.edge.gallery`) is a production-quality, open-source
(Apache 2.0) Android application demonstrating on-device LLM inference via LiteRT-LM. It provides:

- A versioned JSON model registry fetched from GitHub, supporting any `.litertlm` model.
- HuggingFace OAuth integration for authenticated model downloads.
- `LlmChatModelHelper` — a singleton orchestration object wrapping LiteRT-LM `Engine` and
  `Conversation` for multi-turn chat, with streaming token callbacks via `MessageCallback`.
- `LlmChatViewModel` — the ViewModel managing the chat UI state for the `LLM_CHAT` task.
- `TextAndVoiceInput` and `HoldToDictate` — existing UI for hold-to-dictate voice input.
- WorkManager-based background model download with progress notifications.
- Jetpack Compose UI with Material3 theming.

The agent MUST treat all of the above as an existing, working foundation. The goal is to add
Kokoro TTS output and hands-free VAD-driven voice input to the existing chat feature — not to
replace it.

### 1.2 — Fork Setup Instructions

The agent MUST perform the following steps before writing any code:

1. Clone: `git clone https://github.com/google-ai-edge/gallery.git`
2. Working directory for all Android work: `Google-AI-Gallery/Android/src/`
3. Verify the project builds and runs on a Pixel 9 Pro XL before any modification.
4. Create a new git branch: `git checkout -b feature/voice-mode`
5. Do NOT modify `model_allowlist.json` or any file in `model_allowlists/`. Custom model
   registration is handled at runtime via the Gallery's existing import flow.

### 1.3 — Key Gallery Source File Map

The agent MUST know the location of the following Gallery files. All path references in this
document are relative to `Google-AI-Gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/`:

| Alias Used in This Document | Actual Path |
|---|---|
| `LlmChatModelHelper` | `ui/llmchat/LlmChatModelHelper.kt` |
| `LlmChatViewModel` | `ui/llmchat/LlmChatViewModel.kt` |
| `ChatPanel` | `ui/common/chat/ChatPanel.kt` |
| `MessageInputText` | `ui/common/chat/MessageInputText.kt` |
| `TextAndVoiceInput` | `ui/common/textandvoiceinput/TextAndVoiceInput.kt` |
| `HoldToDictate` | `ui/common/textandvoiceinput/HoldToDictate.kt` |
| `HoldToDictateViewModel` | `ui/common/textandvoiceinput/HoldToDictateViewModel.kt` |
| `GalleryApplication` | `GalleryApplication.kt` |
| `AppModule` (Hilt) | `data/AppModule.kt` (locate via `@InstallIn(SingletonComponent)`) |
| `libs.versions.toml` | `../gradle/libs.versions.toml` |
| `app/build.gradle.kts` | `../app/build.gradle.kts` |

### 1.4 — Architectural Principle: Additive, Not Destructive

Every modification to an existing Gallery file MUST be additive:

- New functions are added; existing functions are not renamed or removed.
- New `when` branches are added to existing state machines; existing branches are not altered.
- The existing text-input path through `MessageInputText` → `LlmChatViewModel.sendMessage()`
  MUST continue to work identically after all modifications. The voice mode is a parallel input
  path, not a replacement.
- The existing `HoldToDictate` component is NOT removed. The new hands-free mode is a toggle
  alongside it.

---

## SECTION 2 — Dependency Additions

### 2.1 — `libs.versions.toml` Additions

Add the following entries to the Gallery's existing `libs.versions.toml`. Do NOT modify any
existing entries.

**In `[versions]`:**
```toml
android-vad       = "2.0.4"          # Silero VAD via gkonovalov/android-vad
onnxruntime       = "1.19.2"         # Already present in Gallery; verify before adding
```

> **Note:** The Gallery may already include `onnxruntime-android` as a transitive dependency of
> `litertlm-android`. The agent MUST check `libs.versions.toml` for an existing `onnxruntime`
> entry before adding one. If it exists at any version ≥ 1.18.0, do not add a duplicate — use
> the existing alias for the JNI CMake link step in Section 4.

**In `[libraries]`:**
```toml
android-vad-silero = { module = "com.github.gkonovalov:android-vad-silero", version.ref = "android-vad" }
```

**In `[plugins]` — no additions required.**

### 2.2 — `app/build.gradle.kts` Additions

Add the following to the Gallery's existing `dependencies {}` block. Do NOT modify any existing
dependency entries.

```kotlin
// --- Silero VAD (on-device voice activity detection) ---
// Requires JitPack repository. See Section 2.3.
implementation(libs.android.vad.silero)
```

Add the following `externalNativeBuild` block inside the existing `android {}` block if not
already present. If the Gallery already has a CMake block, merge into it:

```kotlin
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
defaultConfig {
    ndk {
        abiFilters += listOf("arm64-v8a")
    }
    externalNativeBuild {
        cmake {
            arguments(
                "-DONNXRUNTIME_INCLUDE_DIR=${project.layout.buildDirectory.get()}/onnxruntime-headers"
            )
        }
    }
}
```

Add to `packagingOptions {}`:
```kotlin
pickFirst("lib/arm64-v8a/libc++_shared.so")
```

### 2.3 — JitPack Repository

The `android-vad` library is hosted on JitPack. Add JitPack to the Gallery's existing
`settings.gradle.kts` `dependencyResolutionManagement` block:

```kotlin
maven { url = uri("https://jitpack.io") }
```

### 2.4 — `AndroidManifest.xml` Additions

Add the following permissions to the Gallery's existing manifest if not already present:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

The Gallery already declares `RECORD_AUDIO` for its audio task. The agent MUST verify before
adding to avoid a duplicate declaration. The `INTERNET`, `FOREGROUND_SERVICE`, and
`POST_NOTIFICATIONS` permissions are already present in the Gallery manifest and do not need
to be added.

---

## SECTION 3 — Directory Structure: New Files Only

The agent MUST create the following new files within the existing Gallery project tree. These
are additions only — no existing directories are restructured.

```
Google-AI-Gallery/Android/src/app/src/main/
│
├── cpp/                                    ── NEW DIRECTORY (entire contents are new)
│   ├── CMakeLists.txt
│   ├── kokoro_engine.h
│   ├── kokoro_engine.cpp
│   ├── audio_buffer.h
│   ├── audio_buffer.cpp
│   └── jni_bridge.cpp
│
├── assets/                                 ── ADD to existing assets/ directory
│   ├── kokoro.onnx                         # Kokoro TTS model (8-bit quantized)
│   └── voices-v1.0.bin                     # Kokoro voice embeddings
│   # NOTE: .litertlm models are NOT placed here. The Gallery downloads them
│   # to getExternalFilesDir() at runtime via its own download system.
│
└── java/com/google/ai/edge/gallery/
    │
    ├── voice/                              ── NEW PACKAGE (entire contents are new)
    │   ├── VoiceModeController.kt          # VAD + AudioRecord + silence timer
    │   ├── VoiceEvent.kt                   # Sealed class: VAD state events
    │   ├── VoiceModeMuteSignal.kt          # SharedFlow for TTS→VAD mute coordination
    │   └── SileroVadWrapper.kt             # Thin wrapper over android-vad library
    │
    ├── tts/                                ── NEW PACKAGE (entire contents are new)
    │   ├── KokoroTtsEngine.kt              # Kotlin JNI façade
    │   ├── AudioTrackPlayer.kt             # AudioTrack lifecycle manager
    │   └── TextChunkSentinel.kt            # Sentence boundary detector
    │
    └── ui/llmchat/                         ── EXISTING PACKAGE — modifications only
        └── (LlmChatViewModel.kt modified)  # See Phase 4
```

---

## SECTION 4 — Phase 1: Native Audio Layer (C++ & JNI)

> **AGENT SCOPE:** Create all files under `app/src/main/cpp/`. These are net-new additions.
> No Gallery C++ files exist to conflict with. The TTS pipeline is identical in function to the
> original blueprint — the only change is that it wires into the Gallery's `MessageCallback`
> token stream rather than a custom `LlmInferenceEngine`.

### Task 1.1 — `CMakeLists.txt`

Create `app/src/main/cpp/CMakeLists.txt` with the following exact specification:

1. `cmake_minimum_required(VERSION 3.22.1)` and `project("gallery_voice_native")`.
2. Declare a shared library target named `gallery_voice_native` compiling:
   `kokoro_engine.cpp`, `audio_buffer.cpp`, `jni_bridge.cpp`.
3. Set C++ standard to C++17 via `set_target_properties`.
4. Locate ONNX Runtime:
   - Use `find_library(ONNXRUNTIME_LIB onnxruntime PATHS ${CMAKE_FIND_ROOT_PATH})`.
   - Use the `ONNXRUNTIME_INCLUDE_DIR` CMake variable injected by Gradle (Section 2.2) for
     header includes. Add a Gradle pre-build task that extracts the ONNX Runtime AAR from the
     Gradle cache and copies headers to the build directory path referenced by that variable.
5. Link against: `android`, `log`, `onnxruntime`.
6. Compile options for release: `-O3 -ffast-math -march=armv8-a+simd`.
7. `target_include_directories`: `cpp/` directory and ONNX Runtime headers.

### Task 1.2 — `audio_buffer.h` / `audio_buffer.cpp`

Implement a **lock-free SPSC (single-producer single-consumer) circular buffer** for raw PCM.

**`audio_buffer.h` MUST declare:**

- Class `AudioCircularBuffer<T>` (instantiated as `int16_t`).
- `explicit AudioCircularBuffer(size_t capacity)` — default capacity `88200`
  (2 seconds at 22050 Hz mono, Kokoro's native output sample rate).
- `bool push(const T* data, size_t count)` — non-blocking; returns `false` if full.
- `size_t pop(T* out, size_t maxCount)` — returns samples actually read.
- `size_t available() const`.
- `void reset()` — atomically zeroes heads. Called between utterances.
- Internal: `std::vector<T> buffer_`, `std::atomic<size_t> head_`, `std::atomic<size_t> tail_`.
  All atomics use `memory_order_acquire`/`release`.
- Non-copyable, non-movable.

**`audio_buffer.cpp` MUST implement:**

- `push`: handle wrap-around in two `memcpy` calls.
- `pop`: mirrors `push` symmetrically.
- `available()`: `(tail_.load(acquire) - head_.load(acquire) + capacity_) % capacity_`.

### Task 1.3 — `kokoro_engine.h` / `kokoro_engine.cpp`

Implement the Kokoro ONNX TTS inference engine in C++.

**`kokoro_engine.h` MUST declare:**

```cpp
class KokoroEngine {
public:
    KokoroEngine(const std::string& modelPath,
                 const std::string& voicesPath,
                 AudioCircularBuffer<int16_t>* outputBuffer);
    ~KokoroEngine();

    bool initialize();
    bool synthesize(const std::string& text, int voiceIndex = 0, float speed = 1.0f);
    void enqueue(const std::string& text);
    void shutdown();

    static constexpr int kSampleRate  = 22050;
    static constexpr int kNumChannels = 1;
    static constexpr int kBitDepth    = 16;

private:
    Ort::Env                          env_;
    Ort::SessionOptions               sessionOpts_;
    std::unique_ptr<Ort::Session>     session_;
    std::vector<float>                voiceEmbeddings_;
    AudioCircularBuffer<int16_t>*     outputBuffer_;     // NOT owned
    std::string                       modelPath_;
    std::string                       voicesPath_;
    std::thread                       synthesisThread_;
    std::queue<std::string>           textQueue_;
    std::mutex                        queueMutex_;
    std::condition_variable           queueCv_;
    std::atomic<bool>                 running_{false};

    void synthesisLoop();
    std::vector<int16_t> runInference(const std::string& text, int voiceIndex, float speed);
    std::vector<int64_t> textToPhonemeIds(const std::string& text);
};
```

**`kokoro_engine.cpp` MUST implement:**

1. **`initialize()`**: `Ort::SessionOptions` with `SetIntraOpNumThreads(2)` and
   `ORT_ENABLE_ALL`. Attempt NNAPI via `OrtSessionOptionsAppendExecutionProvider_Nnapi`;
   fall back to CPU silently with `__android_log_print(ANDROID_LOG_WARN, ...)`.
   Load `voices-v1.0.bin`: format is `[int32 numVoices][int32 embeddingDim][float32*]`.

2. **`textToPhonemeIds()`**: Static C++ lookup table mapping ASCII characters and common
   English phoneme sequences to integer IDs per Kokoro's vocabulary. ASCII alphanumeric +
   punctuation only. Add `TODO: replace with espeak-ng integration for production`.

3. **`runInference()`**: Input tensors: `input_ids` (`int64_t [1, seq_len]`),
   `style` (`float [1, 256]`), `speed` (`float [1]`). Run session. Convert float PCM
   `[-1.0, 1.0]` → `int16_t` via `std::clamp(sample * 32767.0f, -32767.0f, 32767.0f)`.

4. **`synthesisLoop()`**: Wait on `queueCv_` for queue non-empty or `running_ == false`.
   Pop text, call `runInference()`, call `outputBuffer_->push()`. On `push()` returning
   false (buffer full), spin-wait `std::this_thread::sleep_for(5ms)` — this creates
   natural back-pressure without dropping audio.

5. **`shutdown()`**: `running_ = false`, notify `queueCv_`, join `synthesisThread_`.

### Task 1.4 — `jni_bridge.cpp`

Expose `KokoroEngine` and `AudioCircularBuffer` to Kotlin via JNI. The JNI package path MUST
match the Gallery's package: `com.google.ai.edge.gallery.tts.KokoroTtsEngine`.

```cpp
extern "C" {
    JNIEXPORT jlong JNICALL
    Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeCreate(
        JNIEnv* env, jobject thiz,
        jstring modelPath, jstring voicesPath);

    JNIEXPORT void JNICALL
    Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeEnqueue(
        JNIEnv* env, jobject thiz, jlong handle, jstring text);

    JNIEXPORT jint JNICALL
    Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativePopPcm(
        JNIEnv* env, jobject thiz,
        jlong handle, jshortArray outBuffer, jint maxSamples);

    JNIEXPORT jint JNICALL
    Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeAvailable(
        JNIEnv* env, jobject thiz, jlong handle);

    JNIEXPORT void JNICALL
    Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeReset(
        JNIEnv* env, jobject thiz, jlong handle);

    JNIEXPORT void JNICALL
    Java_com_google_ai_edge_gallery_tts_KokoroTtsEngine_nativeDestroy(
        JNIEnv* env, jobject thiz, jlong handle);
}
```

**Implementation rules:**

- `nativeCreate`: `new KokoroEngine(...)`, call `initialize()`, throw `RuntimeException` on
  failure via `env->ThrowNew(...)`, return handle as `reinterpret_cast<jlong>`.
- `nativeEnqueue`: `GetStringUTFChars` → `engine->enqueue(text)` → `ReleaseStringUTFChars`.
- `nativePopPcm`: `outputBuffer_->pop()` into temp buffer → `SetShortArrayRegion`. Return
  actual samples copied.
- `nativeAvailable`: `(jint)engine->outputBuffer_->available()`.
- `nativeReset`: `engine->outputBuffer_->reset()`.
- `nativeDestroy`: `engine->shutdown()` → `delete engine`.

---

## SECTION 5 — Phase 2: Kotlin TTS Layer (New Files)

> **AGENT SCOPE:** Create all files in the new `tts/` and supporting packages. These are
> net-new additions with no Gallery conflicts.

### Task 2.1 — `tts/KokoroTtsEngine.kt`

```kotlin
package com.google.ai.edge.gallery.tts

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KokoroTtsEngine @Inject constructor(
    private val nativeHandle: Long
) {
    external fun nativeCreate(modelPath: String, voicesPath: String): Long
    external fun nativeEnqueue(handle: Long, text: String)
    external fun nativePopPcm(handle: Long, outBuffer: ShortArray, maxSamples: Int): Int
    external fun nativeAvailable(handle: Long): Int
    external fun nativeReset(handle: Long)
    external fun nativeDestroy(handle: Long)

    fun enqueue(text: String)                    = nativeEnqueue(nativeHandle, text)
    fun popPcm(buf: ShortArray, max: Int): Int   = nativePopPcm(nativeHandle, buf, max)
    fun available(): Int                         = nativeAvailable(nativeHandle)
    fun reset()                                  = nativeReset(nativeHandle)
    fun destroy()                                = nativeDestroy(nativeHandle)

    companion object {
        init { System.loadLibrary("gallery_voice_native") }
    }
}
```

The `nativeHandle` MUST be provided by the Hilt module (Section 5.5) as a `Long`, produced
by calling `nativeCreate(kokoroOnnxPath, voicesBinPath)` during singleton construction.
Asset extraction of `kokoro.onnx` and `voices-v1.0.bin` to `context.filesDir` MUST occur
in the Hilt provider before `nativeCreate` is called.

### Task 2.2 — `tts/AudioTrackPlayer.kt`

Thin Kotlin wrapper over an injected `AudioTrack` singleton.

```kotlin
package com.google.ai.edge.gallery.tts

import android.media.AudioTrack
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioTrackPlayer @Inject constructor(private val audioTrack: AudioTrack) {
    fun play()
    fun write(pcmData: ShortArray, sampleCount: Int)
    fun stop()   // calls audioTrack.stop() then audioTrack.flush()
    fun reset()  // calls stop() then audioTrack.flush()
    fun release()
    val isPlaying: Boolean get() = audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
}
```

The `AudioTrack` singleton MUST be created with:
- `AudioAttributes.USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`
- `AudioFormat`: 22050 Hz, `ENCODING_PCM_16BIT`, `CHANNEL_OUT_MONO`
- Buffer size: `AudioTrack.getMinBufferSize(...) * 4`
- Mode: `AudioTrack.MODE_STREAM`

### Task 2.3 — `tts/TextChunkSentinel.kt`

Stateless Kotlin `object`. Detects sentence boundaries to prevent mid-word TTS synthesis.

```kotlin
object TextChunkSentinel {
    fun extractCompletedChunks(accumulator: StringBuilder): List<String>
}
```

Detect boundaries in priority order:
1. `. ` or `.$` (period + space or end of string)
2. `? ` (question mark + space)
3. `! ` (exclamation + space)
4. `,` where the current chunk already exceeds 80 characters (to prevent overly long chunks)

For each boundary detected: extract text from chunk start to boundary (inclusive), remove it
from `accumulator`, append to return list. Return empty list if no boundary. MUST NOT mutate
`accumulator` except to remove extracted chunks from the front.

### Task 2.4 — `voice/VoiceEvent.kt`

```kotlin
package com.google.ai.edge.gallery.voice

sealed class VoiceEvent {
    data object ListeningStarted   : VoiceEvent()
    data object SpeechDetected     : VoiceEvent()
    data class  SilenceTimeout(val bufferedAudioMs: Long) : VoiceEvent()
    data class  UtteranceReady(val transcript: String)    : VoiceEvent()
    data class  RecognitionError(val code: Int)           : VoiceEvent()
    data object ListeningStopped   : VoiceEvent()
}
```

### Task 2.5 — `voice/VoiceModeMuteSignal.kt`

A `SharedFlow<Boolean>` used by the TTS layer to signal the VAD layer to mute/unmute.

```kotlin
package com.google.ai.edge.gallery.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceModeMuteSignal @Inject constructor() {
    private val _muted = MutableSharedFlow<Boolean>(replay = 1)
    val muted: SharedFlow<Boolean> = _muted.asSharedFlow()
    suspend fun setMuted(muted: Boolean) = _muted.emit(muted)
}
```

### Task 2.6 — `voice/SileroVadWrapper.kt`

Thin Kotlin wrapper over the `android-vad` Silero implementation.

```kotlin
package com.google.ai.edge.gallery.voice

import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SileroVadWrapper @Inject constructor() {

    private lateinit var vad: VadSilero

    fun initialize(context: android.content.Context) {
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.NORMAL)
            .setSpeechDurationMs(150)
            .setSilenceDurationMs(300)
            .build()
    }

    /**
     * Returns true if the frame contains speech.
     * [frame] must be exactly FrameSize.FRAME_SIZE_512 (512) samples of 16kHz mono PCM.
     */
    fun isSpeech(frame: ShortArray): Boolean = vad.isSpeech(frame)

    fun close() = vad.close()
}
```

**Configuration rationale:**
- `SAMPLE_RATE_16K`: SpeechRecognizer also expects 16kHz; avoids resampling.
- `FRAME_SIZE_512`: 32ms per frame at 16kHz. Lowest latency frame size supported by Silero.
- `Mode.NORMAL`: Balanced sensitivity. Use `AGGRESSIVE` in loud environments.
- `speechDurationMs = 150`: Speech must persist 150ms before triggering `SPEECH_ACTIVE`.
- `silenceDurationMs = 300`: Used internally by the library; the outer silence timeout
  (1200ms default) is managed separately in `VoiceModeController`.

### Task 2.7 — `voice/VoiceModeController.kt`

This is the core of the hands-free pipeline. It owns `AudioRecord`, `SileroVadWrapper`, the
silence timer, and the `SpeechRecognizer`. It emits `VoiceEvent` upward into the ViewModel.

**Class declaration:**
```kotlin
package com.google.ai.edge.gallery.voice

@Singleton
class VoiceModeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vadWrapper: SileroVadWrapper,
    private val muteSignal: VoiceModeMuteSignal,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
)
```

**Internal state:**
```kotlin
private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 8)
val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

private var audioRecord: AudioRecord? = null
private var recognizer: SpeechRecognizer? = null
private var isListening = AtomicBoolean(false)
private var isMuted = AtomicBoolean(false)
private var silenceJob: Job? = null
private val pcmBuffer = ShortArray(512)        // One Silero frame
private val utteranceBuffer = ArrayList<Short>() // Accumulates speech PCM for STT
private var speechActive = false
```

**Constants:**
```kotlin
companion object {
    const val SAMPLE_RATE        = 16000
    const val CHANNEL_CONFIG     = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT       = AudioFormat.ENCODING_PCM_16BIT
    const val SILENCE_TIMEOUT_MS = 1200L  // ms of silence before firing UtteranceReady
    const val FRAME_SAMPLES      = 512    // Must match SileroVadWrapper FrameSize
}
```

**`fun startListening(scope: CoroutineScope)`:**

1. Assert `isListening.compareAndSet(false, true)`. If false, return — already listening.
2. `vadWrapper.initialize(context)` if not already initialized.
3. Create `AudioRecord` using `MediaRecorder.AudioSource.VOICE_COMMUNICATION`.
   This source activates Android's Acoustic Echo Cancellation (AEC) preprocessor, which
   strips loudspeaker audio from the mic signal. This is the correct solution to the barge-in
   problem — the VAD will not trigger on TTS audio playing through the speaker.
   ```kotlin
   audioRecord = AudioRecord(
       MediaRecorder.AudioSource.VOICE_COMMUNICATION,
       SAMPLE_RATE,
       CHANNEL_CONFIG,
       AUDIO_FORMAT,
       AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2
   )
   ```
4. Collect `muteSignal.muted` in a separate coroutine within `scope`. On `true`, set
   `isMuted.set(true)`. On `false`, set `isMuted.set(false)`.
5. Launch the **VAD read loop** on `ioDispatcher`:
   ```
   audioRecord.startRecording()
   emit(VoiceEvent.ListeningStarted)

   while (isListening.get()) {
       val read = audioRecord.read(pcmBuffer, 0, FRAME_SAMPLES)
       if (read != FRAME_SAMPLES) continue
       if (isMuted.get()) { utteranceBuffer.clear(); speechActive = false; continue }

       val speech = vadWrapper.isSpeech(pcmBuffer)

       if (speech) {
           if (!speechActive) {
               speechActive = true
               silenceJob?.cancel()
               emit(VoiceEvent.SpeechDetected)
           }
           utteranceBuffer.addAll(pcmBuffer.toList())
       } else {
           if (speechActive) {
               // Silence detected after speech — start silence countdown
               silenceJob?.cancel()
               silenceJob = scope.launch {
                   delay(SILENCE_TIMEOUT_MS)
                   // Silence held for full timeout — treat as end of utterance
                   val durationMs = (utteranceBuffer.size / SAMPLE_RATE.toFloat() * 1000).toLong()
                   emit(VoiceEvent.SilenceTimeout(durationMs))
                   fireRecognition()
               }
           }
       }
   }
   ```

6. **`private suspend fun fireRecognition()`**:
   - Convert `utteranceBuffer` to a `ByteArray` (little-endian 16-bit PCM).
   - Pass to `SpeechRecognizer` via `ReSpeakIntent` bundle.
   - Collect the recognition result and emit `VoiceEvent.UtteranceReady(transcript)`.
   - Reset `speechActive = false`, `utteranceBuffer.clear()`.
   - **Implementation note:** Use `SpeechRecognizer` with
     `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` and
     `RecognizerIntent.EXTRA_AUDIO_SOURCE` pointing to the buffered PCM, OR pass the buffered
     audio via `RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 100` to
     signal immediate processing. The preferred approach is to write the PCM to a temp WAV file
     and pass it via `EXTRA_AUDIO_SOURCE` for accuracy. The agent MUST implement a
     `writeWav(pcm: ShortArray, sampleRate: Int, outFile: File)` utility function in
     `common/Utils.kt` (the Gallery's existing utility file) that prepends a standard 44-byte
     WAV header.
   - On `SpeechRecognizer` error, emit `VoiceEvent.RecognitionError(errorCode)`.

**`fun stopListening()`:**
- `isListening.set(false)`, `silenceJob?.cancel()`, `audioRecord?.stop()`,
  `audioRecord?.release()`, `audioRecord = null`, `speechActive = false`,
  `utteranceBuffer.clear()`, emit `VoiceEvent.ListeningStopped`.

**`fun destroy()`:** `stopListening()`, `vadWrapper.close()`, `recognizer?.destroy()`.

---

## SECTION 6 — Phase 3: Hilt Module Additions

> **AGENT SCOPE:** Add new `@Provides` bindings to the Gallery's existing Hilt `AppModule`.
> The agent MUST locate the Gallery's existing `@InstallIn(SingletonComponent::class)` module
> (likely `data/AppModule.kt` or similar — find via `@InstallIn` annotation search). Add the
> following bindings to that existing module. Do NOT create a new module file unless the
> existing module cannot be located.

Add the following bindings:

```kotlin
@Provides @Singleton
fun provideAudioTrack(): AudioTrack {
    return AudioTrack(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        AudioFormat.Builder()
            .setSampleRate(22050)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        AudioTrack.getMinBufferSize(
            22050, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) * 4,
        AudioTrack.MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE
    )
}

@Provides @Singleton
fun provideKokoroTtsEngine(
    @ApplicationContext context: Context
): KokoroTtsEngine {
    // Extract kokoro.onnx and voices-v1.0.bin from assets to filesDir if not present
    // TODO(developer): ensure kokoro.onnx and voices-v1.0.bin are in app/src/main/assets/
    val kokoroFile = extractAssetIfNeeded(context, "kokoro.onnx")
    val voicesFile = extractAssetIfNeeded(context, "voices-v1.0.bin")
    val handle = runBlocking(Dispatchers.IO) {
        KokoroTtsEngine().nativeCreate(
            kokoroFile.absolutePath,
            voicesFile.absolutePath
        )
    }
    return KokoroTtsEngine(handle)
}

@Provides @Singleton
fun provideVoiceModeMuteSignal(): VoiceModeMuteSignal = VoiceModeMuteSignal()
```

Add a private helper function `extractAssetIfNeeded(context: Context, name: String): File`
that checks `context.filesDir/name` existence before extracting, and returns the `File`.

If the Gallery already defines `@IoDispatcher` and `@DefaultDispatcher` qualifier annotations,
use them. If not, add them to a new file `di/CoroutineDispatchers.kt` and add the corresponding
`@Provides` bindings for `Dispatchers.IO` and `Dispatchers.Default`.

---

## SECTION 7 — Phase 4: LlmChatViewModel Extension

> **AGENT SCOPE:** Modify `ui/llmchat/LlmChatViewModel.kt`. This is the most critical
> modification to an existing Gallery file. Apply only additive changes. The existing
> `sendMessage()` function and all existing state flows MUST remain untouched.

### Task 4.1 — New Injected Dependencies

Add the following injected constructor parameters to `LlmChatViewModel`. The Gallery uses
`@HiltViewModel` — add parameters to the existing `@Inject constructor(...)`:

```kotlin
private val kokoroTts: KokoroTtsEngine,
private val audioPlayer: AudioTrackPlayer,
private val voiceController: VoiceModeController,
private val muteSignal: VoiceModeMuteSignal,
```

### Task 4.2 — New Voice Mode State

Add the following state declarations to `LlmChatViewModel`, below the existing state fields:

```kotlin
// --- Voice Mode State ---
private val _voiceModeEnabled = MutableStateFlow(false)
val voiceModeEnabled: StateFlow<Boolean> = _voiceModeEnabled.asStateFlow()

private val _vadState = MutableStateFlow<VoiceEvent>(VoiceEvent.ListeningStopped)
val vadState: StateFlow<VoiceEvent> = _vadState.asStateFlow()

private val responseAccumulator = StringBuilder()
private var ttsJob: Job? = null
private var voiceEventJob: Job? = null
```

### Task 4.3 — `fun toggleVoiceMode()`

Add this new public function to `LlmChatViewModel`:

```kotlin
fun toggleVoiceMode() {
    if (_voiceModeEnabled.value) {
        // Turning off
        voiceController.stopListening()
        voiceEventJob?.cancel()
        _voiceModeEnabled.value = false
    } else {
        // Turning on
        _voiceModeEnabled.value = true
        startVoiceEventCollection()
        voiceController.startListening(viewModelScope)
    }
}
```

### Task 4.4 — `private fun startVoiceEventCollection()`

Add this private function. It collects `VoiceEvent` from `VoiceModeController` and routes
`UtteranceReady` events into the existing `sendMessage()` function:

```kotlin
private fun startVoiceEventCollection() {
    voiceEventJob = viewModelScope.launch {
        voiceController.events.collect { event ->
            _vadState.value = event
            when (event) {
                is VoiceEvent.UtteranceReady -> {
                    if (event.transcript.isNotBlank()) {
                        // Route transcript into the existing Gallery send path.
                        // The Gallery's sendMessage() accepts a Model and a list of
                        // ChatMessage content items. Construct a text-only message.
                        val model = /* obtain currently selected model from existing
                                      Gallery state — see Task 4.5 */ currentModel
                        sendMessage(model, event.transcript)
                        // After sending, resume listening for next utterance.
                        // VoiceModeController auto-resumes after fireRecognition().
                    }
                }
                is VoiceEvent.RecognitionError -> {
                    // Log error; VAD loop continues automatically.
                }
                else -> { /* UI state updates handled via _vadState */ }
            }
        }
    }
}
```

### Task 4.5 — `currentModel` Access

The Gallery's `LlmChatViewModel` already holds a reference to the currently loaded `Model`
object. The agent MUST locate the existing field (likely `val model: Model` or accessed via
a `StateFlow<Model?>`) and reference it in Task 4.4 without duplicating it.

### Task 4.6 — TTS Hook into `MessageCallback`

The Gallery's `LlmChatModelHelper.runInference()` accepts a `MessageCallback` lambda that
fires on each token. The agent MUST locate where `LlmChatViewModel` constructs this callback
(likely passed to `LlmChatModelHelper.runInference()` as a parameter or lambda) and add the
following token routing logic **inside the existing callback, not replacing it**:

```kotlin
// Inside the existing MessageCallback / onPartialResponse lambda:
val newToken: String = /* the token text from the existing callback parameter */

// --- TTS HOOK (additive) ---
if (_voiceModeEnabled.value) {
    responseAccumulator.append(newToken)
    val chunks = TextChunkSentinel.extractCompletedChunks(responseAccumulator)
    chunks.forEach { chunk ->
        kokoroTts.enqueue(chunk)
    }
}
// --- END TTS HOOK ---

// Existing callback code continues unchanged below this block.
```

Add the following at the location in `LlmChatViewModel` where inference **completes** (i.e.,
`done == true` in the callback, or the completion callback fires):

```kotlin
// --- TTS COMPLETION HOOK (additive) ---
if (_voiceModeEnabled.value) {
    val remaining = responseAccumulator.toString().trim()
    if (remaining.isNotBlank()) {
        kokoroTts.enqueue(remaining)
    }
    responseAccumulator.clear()
    startTtsPlaybackLoop()
}
// --- END TTS COMPLETION HOOK ---
```

### Task 4.7 — `private fun startTtsPlaybackLoop()`

```kotlin
private fun startTtsPlaybackLoop() {
    ttsJob?.cancel()
    ttsJob = viewModelScope.launch(Dispatchers.IO) {
        // Signal VAD to mute while TTS is speaking — AEC handles most of this,
        // but muting eliminates any residual false triggers.
        muteSignal.setMuted(true)
        audioPlayer.play()

        val chunkBuffer = ShortArray(4096)
        // Drain the circular buffer until empty AND Kokoro synthesis queue is empty.
        // "Queue empty" is approximated by available() == 0 after a brief wait.
        var consecutiveEmptyReads = 0
        while (consecutiveEmptyReads < 10) {
            val read = kokoroTts.popPcm(chunkBuffer, chunkBuffer.size)
            if (read > 0) {
                audioPlayer.write(chunkBuffer, read)
                consecutiveEmptyReads = 0
            } else {
                delay(20)
                consecutiveEmptyReads++
            }
        }

        audioPlayer.stop()
        kokoroTts.reset()
        muteSignal.setMuted(false)
        // Voice controller automatically resumes listening after unmute signal.
    }
}
```

### Task 4.8 — `onCleared()` Addition

Add to the Gallery's existing `LlmChatViewModel.onCleared()` override. Do NOT replace it:

```kotlin
// At the END of the existing onCleared() body:
if (_voiceModeEnabled.value) {
    voiceController.stopListening()
}
voiceController.destroy()
audioPlayer.release()
kokoroTts.destroy()
```

---

## SECTION 8 — Phase 5: UI Modifications

> **AGENT SCOPE:** Modify `ChatPanel.kt` and `TextAndVoiceInput.kt`. These are additive
> modifications. All existing composables remain present and functional.

### Task 5.1 — Voice Mode Toggle Button

In `TextAndVoiceInput.kt`, add a new `IconToggleButton` alongside the existing
`HoldToDictate` button. This toggle switches between hold-to-dictate (existing) and
hands-free mode (new). The agent MUST identify the Row or Box in `TextAndVoiceInput` that
contains the existing mic/voice controls and add the toggle there.

```kotlin
// Add this new parameter to TextAndVoiceInput composable signature:
voiceModeEnabled: Boolean,
onVoiceModeToggle: () -> Unit,

// Add this composable inside the existing controls row:
IconToggleButton(
    checked = voiceModeEnabled,
    onCheckedChange = { onVoiceModeToggle() }
) {
    Icon(
        imageVector = if (voiceModeEnabled)
            Icons.Default.RecordVoiceOver
        else
            Icons.Default.VoiceOverOff,
        contentDescription = if (voiceModeEnabled)
            "Disable hands-free mode"
        else
            "Enable hands-free mode"
    )
}
```

When `voiceModeEnabled` is `true`, the existing `HoldToDictate` button MUST be hidden
(set `visibility = false` or remove from composition with an `if (!voiceModeEnabled)` guard).
The two modes are mutually exclusive.

### Task 5.2 — VAD State Indicator

Add a new `VadStateIndicator` composable in
`ui/common/textandvoiceinput/VadStateIndicator.kt`. This is a new file.

The composable takes `vadState: VoiceEvent` and displays:

| `VoiceEvent` | Visual |
|---|---|
| `ListeningStarted` | Animated grey pulsing ring |
| `SpeechDetected` | Solid red circle, no animation |
| `SilenceTimeout` | Orange fading ring (countdown feel) |
| `ListeningStopped` | Nothing — invisible |

All animations use `rememberInfiniteTransition()`. No external animation libraries.

Place `VadStateIndicator` in `ChatPanel.kt` directly above the input row, visible only when
`voiceModeEnabled == true`. Collect `vadState` from `LlmChatViewModel.vadState` via
`collectAsStateWithLifecycle()`.

### Task 5.3 — `ChatPanel.kt` Wiring

The agent MUST locate the call site in `ChatPanel.kt` where `TextAndVoiceInput` is
instantiated and add the new parameters:

```kotlin
TextAndVoiceInput(
    // ... all existing parameters unchanged ...
    voiceModeEnabled = voiceModeEnabled,
    onVoiceModeToggle = { viewModel.toggleVoiceMode() }
)
```

Collect `voiceModeEnabled` from `LlmChatViewModel.voiceModeEnabled` via
`collectAsStateWithLifecycle()` at the top of `ChatPanel`.

---

## SECTION 9 — Phase 6: GPU Backend & Manifest Additions

### Task 6.1 — GPU Library Loading

The Gallery's `GalleryApplication.kt` or `MainActivity.kt` may already load GPU delegate
libraries. The agent MUST check before adding. If not present, add to
`GalleryApplication.onCreate()` BEFORE any `super.onCreate()` or Hilt initialization:

```kotlin
try {
    System.loadLibrary("LiteRtGpu")
} catch (e: UnsatisfiedLinkError) {
    Log.w("GalleryVoice", "LiteRT GPU delegate unavailable, falling back to CPU: ${e.message}")
}
```

### Task 6.2 — Native Library Manifest Declarations

Add to `AndroidManifest.xml` inside `<application>` if not already present:

```xml
<uses-native-library android:name="libvndksupport.so"  android:required="false" />
<uses-native-library android:name="libOpenCL.so"       android:required="false" />
```

These are already declared in the Gallery's manifest per the DeepWiki analysis. The agent
MUST verify before adding.

---

## SECTION 10 — Critical Integration Contracts & Verification Checklist

> The agent MUST verify each contract before declaring any Phase complete.

### Contract A — The Token-to-TTS Bridge

- `TextChunkSentinel.extractCompletedChunks()` is called on every token in the existing
  `MessageCallback`, only when `_voiceModeEnabled.value == true`.
- `kokoroTts.enqueue()` is called ONLY with complete sentence chunks. Never with
  partial tokens or mid-sentence fragments.
- `startTtsPlaybackLoop()` is launched AFTER the final `enqueue()` call for the response —
  i.e., after the inference completion callback fires, not before.
- `muteSignal.setMuted(true)` fires BEFORE `audioPlayer.play()`. The VAD loop checks
  `isMuted` before every frame — there is no race condition because the mute is set
  synchronously before the playback loop starts.

### Contract B — Thread Safety

| Operation | Required Thread | Enforcement |
|---|---|---|
| `AudioRecord.read()` | IO dispatcher | VAD loop launched on `ioDispatcher` |
| `SileroVadWrapper.isSpeech()` | Same thread as `AudioRecord.read()` | Single VAD loop thread |
| `SpeechRecognizer` calls | Main thread | `withContext(Dispatchers.Main)` in `fireRecognition()` |
| `conversation.sendMessageAsync()` | IO dispatcher | Gallery's existing enforcement |
| `AudioTrack.write()` | IO dispatcher (blocking OK) | `startTtsPlaybackLoop()` on `Dispatchers.IO` |
| `KokoroEngine::synthesisLoop` | Native C++ thread | `std::thread` |
| `_voiceModeEnabled.value` writes | Any thread | `StateFlow` is thread-safe |
| `responseAccumulator.append()` | MUST be single-threaded | Called only from token callback |

### Contract C — Barge-In / Echo Prevention

- `MediaRecorder.AudioSource.VOICE_COMMUNICATION` is the ONLY permitted audio source for
  the `AudioRecord` instance. This is non-negotiable. Using `MIC` or `DEFAULT` will cause
  the VAD to trigger on TTS audio playing through the speaker.
- `muteSignal.setMuted(true)` provides a secondary software gate. Both AEC and mute signal
  MUST be active simultaneously during TTS playback.
- The agent MUST NOT implement any sleep or delay as the primary barge-in prevention
  mechanism. AEC + mute signal is the correct approach.

### Contract D — Memory Management

- `KokoroEngine` is heap-allocated in C++. `nativeDestroy()` MUST be called exactly once,
  from `LlmChatViewModel.onCleared()`. No other call site.
- `AudioTrack` is a Hilt singleton. It MUST NOT be released until `onCleared()`.
- `AudioRecord` is created and destroyed per listening session inside `VoiceModeController`.
  It MUST be released in `stopListening()` without exception.
- `SileroVadWrapper` holds an ONNX session. `close()` MUST be called in
  `VoiceModeController.destroy()`.
- The `voiceEmbeddings_` float array in `KokoroEngine` is retained in C++ heap for the
  app's lifetime. This is intentional — embeddings are small and re-loading them per
  synthesis call would be too slow.

### Contract E — Asset Deployment

- `kokoro.onnx` and `voices-v1.0.bin` MUST be placed in `app/src/main/assets/`.
- Asset extraction MUST check `File.exists()` before extracting. Extraction once per install.
- `.litertlm` model files are NEVER placed in assets. They are downloaded at runtime by the
  Gallery's existing download system to `getExternalFilesDir()`.
- No asset path is hard-coded as a string literal. All paths derived from
  `context.filesDir.absolutePath + "/" + filename`.

### Contract F — Existing Gallery Functionality Preserved

- The text input path (`MessageInputText` → `sendMessage()`) works identically with voice
  mode both on and off.
- The existing `HoldToDictate` component is present and functional when voice mode is off.
- The Gallery's model download, model switching, and settings flows are untouched.
- All existing task types (`LLM_PROMPT_LAB`, `LLM_ASK_IMAGE`, `LLM_ASK_AUDIO`) are
  unaffected. The voice mode additions are scoped to `LlmChatViewModel` only.
- The Gallery's benchmark screen, model management screen, and navigation system are untouched.

### Contract G — Silence Threshold Tuning

`SILENCE_TIMEOUT_MS = 1200L` is the default. The agent MUST expose this as a named constant
in `VoiceModeController.companion object` and add a `TODO` noting it should be exposed as a
user-configurable setting in a future iteration. Values below 800ms will feel abrupt for
natural speech. Values above 2000ms will feel sluggish.

---

## SECTION 11 — README Addendum (Agent-Generated)

The agent MUST append a `## Voice Mode` section to the Gallery's existing `README.md`
(or create a `VOICE_MODE.md` if the README is not suitable for modification). Include:

1. **Prerequisites for voice mode:** NDK r27, CMake 3.22.1, `kokoro.onnx`, `voices-v1.0.bin`.
2. **Asset placement:** Both TTS assets go in `app/src/main/assets/`.
3. **How to use:** Enable hands-free toggle in chat → speak naturally → pause ~1.2s →
   model responds in voice.
4. **Known limitations:** G2P is ASCII-only (TODO: espeak-ng); Silero VAD requires quiet
   environments for best results; TTS output is English-only (Kokoro voices-v1.0); barge-in
   (interrupting the model mid-response) stops VAD but does not interrupt TTS playback in
   this version.
5. **Model compatibility:** Voice output works with any model loaded in the Gallery.
   The TTS layer is downstream of `MessageCallback` and is model-agnostic.

---

*End of Implementation Blueprint — Version 2.0*
*Single unified document. Fork-aware throughout. Track A (Phases 0–2): pure additions.*
*Track B (Phases 3–5): targeted modifications to identified Gallery files only.*
*Reference: [Gallery Source](https://github.com/google-ai-edge/gallery) |*
*[LiteRT-LM Android](https://ai.google.dev/edge/litert-lm/android) |*
*[android-vad](https://github.com/gkonovalov/android-vad)*