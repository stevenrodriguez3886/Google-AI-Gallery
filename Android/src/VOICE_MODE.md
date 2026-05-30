# Android On-Device Voice Assistant

The Google AI Gallery now supports a fully on-device, hands-free voice assistant. This feature combines the existing LLM integration with Kokoro for Text-to-Speech (TTS) and Silero for Voice Activity Detection (VAD).

## Architecture

The voice assistant consists of three primary layers:

1. **Native C++ Layer**: Runs the Kokoro TTS model via ONNX Runtime and manages a lock-free Single-Producer Single-Consumer (SPSC) circular buffer for efficient audio synthesis.
2. **Kotlin Integration Layer**: Uses the `android-vad` library (Silero) to manage `AudioRecord` for voice activity detection. A `VoiceModeController` orchestrates the pipeline, and a JNI bridge (`KokoroTtsEngine`) communicates with the C++ layer. 
3. **UI/ViewModel Layer**: `LlmChatViewModel` hooks into the LLM streaming response to dynamically enqueue chunks of text into the TTS engine for near real-time voice playback. The UI utilizes a `VadStateIndicator` to visually reflect the microphone status.

## Usage

1. Open an LLM chat session (e.g., Gemini Nano).
2. Tap the microphone toggle next to the attachment (+/add) button.
3. The UI will indicate that it is listening. Speak clearly into the microphone.
4. When you stop speaking, the VAD detects the silence and automatically submits your speech as a prompt to the LLM.
5. The LLM's response will be spoken aloud using the Kokoro TTS engine as it is generated.
6. To exit voice mode, tap the microphone toggle again.

## Dependencies

- **ONNX Runtime**: Embedded natively to execute the `.onnx` Kokoro model.
- **android-vad**: Used for Silero VAD. Configured for 16kHz, 512-frame size in "Normal" mode.
- **Models**:
    - `kokoro-v1.0.int8.onnx` (TTS Engine)
    - `voices-v1.0.bin` (Voice Embeddings)
    These must be placed in `app/src/main/assets/` during build time.
