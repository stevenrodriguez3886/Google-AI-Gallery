## Sherpa-ONNX TTS Replacement Plan

### Step 1 — Download Required Files

**Sherpa-ONNX Android AAR**
Download the pre-built AAR from the Sherpa-ONNX GitHub releases page:
`https://github.com/k2-fsa/sherpa-onnx/releases`
Find the latest stable release and download `sherpa-onnx-android.aar`. Place it at `app/libs/sherpa-onnx-android.aar`.

**Kokoro Model Package**
The existing `kokoro-v1.0.int8.onnx` and `voices-v1.0.bin` in assets are missing two required files that Sherpa-ONNX needs for correct phoneme conversion. Download the complete Sherpa-ONNX Kokoro model package:
`https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models`
Download `kokoro-en-v0_19.tar.bz2`, extract it, and copy these into `app/src/main/assets/kokoro/`:
- `model.int8.onnx`
- `voices.bin`
- `tokens.txt`
- `espeak-ng-data/` (entire directory)

The existing `kokoro-v1.0.int8.onnx` and `voices-v1.0.bin` in the root of assets can be deleted — Sherpa-ONNX will use the package files instead.

---

### Step 2 — Delete the Entire Custom C++ TTS Stack

**Delete these files entirely:**
- `app/src/main/cpp/kokoro_engine.h`
- `app/src/main/cpp/kokoro_engine.cpp`
- `app/src/main/cpp/audio_buffer.h`
- `app/src/main/cpp/audio_buffer.cpp` (if it exists)
- `app/src/main/cpp/jni_bridge.cpp`
- `app/src/main/cpp/onnxruntime_cxx_api.h`
- `app/src/main/cpp/onnxruntime_c_api.h`
- `app/src/main/cpp/onnxruntime_cxx_inline.h`
- `app/src/main/cpp/onnxruntime_float16.h`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/.../tts/KokoroTtsEngine.kt`
- `app/src/main/java/.../tts/AudioTrackPlayer.kt`

**Keep:**
- `app/src/main/java/.../tts/TextChunkSentinel.kt` — still needed
- `app/src/main/java/.../voice/` — all voice files unchanged

---

### Step 3 — `app/build.gradle.kts`

Remove:
- The entire `externalNativeBuild { cmake { ... } }` block
- The `ndk { abiFilters }` block inside `defaultConfig`
- The `onnxruntimeExtraction` configuration
- The `extractOnnxRuntime` task
- The `tasks.named("preBuild")` hook
- `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")`

Add:
```kotlin
implementation(files("libs/sherpa-onnx-android.aar"))
```

Add to `androidComponents` block (the `pickFirsts` for `libonnxruntime.so` stays — Sherpa-ONNX bundles its own ORT which will conflict with litertlm's):
```kotlin
variant.packaging.jniLibs.pickFirsts.add("**/libsherpa-onnx-jni.so")
```

---

### Step 4 — Create `SherpaOnnxTtsManager.kt`

Create `app/src/main/java/.../tts/SherpaOnnxTtsManager.kt`:

```kotlin
package com.google.ai.edge.gallery.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SherpaOnnxTtsManager"

@Singleton
class SherpaOnnxTtsManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    var speakerId: Int = 0  // af_alloy by default; change to select voice

    fun initialize() {
        try {
            val dataDir = copyAssetsToFilesDir()

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$dataDir/model.int8.onnx",
                        voices = "$dataDir/voices.bin",
                        tokens = "$dataDir/tokens.txt",
                        dataDir = "$dataDir/espeak-ng-data",
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )

            tts = OfflineTts(config = config)
            Log.i(TAG, "SherpaOnnxTtsManager initialized. Sample rate: ${tts?.sampleRate}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS", e)
        }
    }

    // Returns the sample rate of the TTS engine (typically 24000 for Kokoro)
    val sampleRate: Int get() = tts?.sampleRate ?: 24000

    fun synthesize(text: String): ShortArray? {
        val t = tts ?: return null
        return try {
            val audio = t.generate(text = text, sid = speakerId, speed = 1.0f)
            // Convert float samples [-1, 1] to int16
            ShortArray(audio.samples.size) { i ->
                (audio.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed for: $text", e)
            null
        }
    }

    fun createAudioTrack(): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 4
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun destroy() {
        tts = null
    }

    private fun copyAssetsToFilesDir(): String {
        val dest = File(context.filesDir, "kokoro")
        if (dest.exists() && File(dest, "tokens.txt").exists()) return dest.absolutePath
        dest.mkdirs()
        copyAssetFolder("kokoro", dest)
        return dest.absolutePath
    }

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) {
            // It's a file
            context.assets.open(assetPath).use { input ->
                File(destDir.parent, destDir.name).outputStream().use { input.copyTo(it) }
            }
            return
        }
        destDir.mkdirs()
        for (asset in assets) {
            val subAsset = "$assetPath/$asset"
            val subDest = File(destDir, asset)
            val children = context.assets.list(subAsset)
            if (children.isNullOrEmpty()) {
                context.assets.open(subAsset).use { input ->
                    subDest.outputStream().use { input.copyTo(it) }
                }
            } else {
                copyAssetFolder(subAsset, subDest)
            }
        }
    }
}
```

---

### Step 5 — Update `AppModule.kt`

Remove:
- `provideKokoroTtsEngine()` 
- `provideAudioTrack()`
- All related imports

Add:
```kotlin
@Provides
@Singleton
fun provideSherpaOnnxTtsManager(
    @ApplicationContext context: Context,
): SherpaOnnxTtsManager {
    return SherpaOnnxTtsManager(context).also { it.initialize() }
}
```

---

### Step 6 — Update `LlmChatViewModelBase` and `LlmChatViewModel`

Replace `KokoroTtsEngine`, `AudioTrackPlayer`, and `VoiceModeMuteSignal` TTS wiring with `SherpaOnnxTtsManager`.

In `LlmChatViewModelBase`, replace `startTtsPlaybackLoop()` and the TTS hook with:

```kotlin
protected fun speakText(text: String) {
    if (!_voiceModeEnabled.value) return
    viewModelScope.launch(Dispatchers.IO) {
        muteSignal?.setMuted(true)
        val samples = ttsManager?.synthesize(text) ?: run {
            muteSignal?.setMuted(false)
            return@launch
        }
        val track = ttsManager?.createAudioTrack() ?: run {
            muteSignal?.setMuted(false)
            return@launch
        }
        track.play()
        track.write(samples, 0, samples.size)
        // Wait for playback to finish
        Thread.sleep((samples.size.toLong() * 1000L) / (ttsManager?.sampleRate ?: 24000))
        track.stop()
        track.release()
        muteSignal?.setMuted(false)
    }
}
```

In the `resultListener` `done` block, replace the TTS completion hook with:
```kotlin
if (_voiceModeEnabled.value) {
    val remaining = responseAccumulator.toString().trim()
    if (remaining.isNotBlank()) speakText(remaining)
    responseAccumulator.clear()
}
```

And replace each `kokoroTts.enqueue(chunk)` call with `speakText(chunk)`.

In `LlmChatViewModel`, replace `KokoroTtsEngine` and `AudioTrackPlayer` constructor parameters with `SherpaOnnxTtsManager`. Remove `VoiceModeMuteSignal` from TTS-related code (keep it for the VAD mute signal).

---

### What Stays Unchanged
- `VoiceModeController.kt`
- `SileroVadWrapper.kt`
- `VoiceEvent.kt`
- `VoiceModeMuteSignal.kt`
- `TextChunkSentinel.kt`
- All UI composables
- `LlmChatScreen.kt`
- `GalleryApplication.kt`

Build with `.\gradlew installDebug` when done.