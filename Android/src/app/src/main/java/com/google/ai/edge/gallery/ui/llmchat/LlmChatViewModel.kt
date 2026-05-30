/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.tts.AudioTrackPlayer
import com.google.ai.edge.gallery.tts.KokoroTtsEngine
import com.google.ai.edge.gallery.tts.TextChunkSentinel
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.voice.VoiceEvent
import com.google.ai.edge.gallery.voice.VoiceModeController
import com.google.ai.edge.gallery.voice.VoiceModeMuteSignal
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase(
  private val systemPromptRepository: SystemPromptRepository? = null,
  userDataDataStore: DataStore<UserData>? = null,
  private val modelFeedbackRepository: Any? = null,
  // --- Voice Mode Dependencies (optional, injected via LlmChatViewModel) ---
  protected val kokoroTts: KokoroTtsEngine? = null,
  protected val audioPlayer: AudioTrackPlayer? = null,
  protected val voiceController: VoiceModeController? = null,
  protected val muteSignal: VoiceModeMuteSignal? = null,
) : ChatViewModel(userDataDataStore) {
  private val _uiSystemPrompt = MutableStateFlow("")
  val uiSystemPrompt = _uiSystemPrompt.asStateFlow()

  // --- Voice Mode State ---
  protected val _voiceModeEnabled = MutableStateFlow(false)
  val voiceModeEnabled: StateFlow<Boolean> = _voiceModeEnabled.asStateFlow()

  protected val _vadState = MutableStateFlow<VoiceEvent>(VoiceEvent.ListeningStopped)
  val vadState: StateFlow<VoiceEvent> = _vadState.asStateFlow()

  protected val responseAccumulator = StringBuilder()
  protected var ttsJob: Job? = null
  protected var voiceEventJob: Job? = null

  /**
   * Sets the system prompt in the UI.
   *
   * This method updates the UI system prompt without saving it to the repository or resetting the
   * session. It is primarily used for initializing the UI system prompt.
   *
   * @param systemPrompt The new system prompt to set in the UI.
   */
  fun setUISystemPrompt(systemPrompt: String) {
    _uiSystemPrompt.value = systemPrompt
  }

  /**
   * Loads the system prompt for the given [task] from the repository.
   *
   * @param task The task to load the system prompt for.
   */
  fun loadSystemPrompt(task: Task) {
    viewModelScope.launch {
      val effectivePrompt =
        SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      _uiSystemPrompt.value = effectivePrompt
    }
  }

  /**
   * Applies a system prompt change to the given [task] and [model].
   *
   * This method updates the UI system prompt, saves the new prompt to the repository, and resets
   * the session with the new prompt.
   *
   * @param task The task to apply the system prompt change to.
   * @param model The model to apply the system prompt change to.
   * @param newPrompt The new system prompt to apply.
   * @param systemPromptUpdatedMessage The message to add to the chat after the system prompt is
   *   updated.
   */
  fun applySystemPromptChange(
    task: Task,
    model: Model,
    newPrompt: String,
    systemPromptUpdatedMessage: String,
  ) {
    _uiSystemPrompt.value = newPrompt
    viewModelScope.launch {
      systemPromptRepository?.updateSystemPrompt(task.id, newPrompt)
      resetSession(
        task = task,
        model = model,
        systemInstruction = Contents.of(newPrompt),
        supportImage = true,
        supportAudio = true,
        onDone = { addMessage(model, ChatMessageInfo(content = systemPromptUpdatedMessage)) },
      )
    }
  }

  fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      // Wait for instance to be initialized.
      while (model.instance == null) {
        delay(100)
      }
      delay(500)

      // Run inference.
      val audioClips: MutableList<ByteArray> = mutableListOf()
      for (audioMessage in audioMessages) {
        audioClips.add(audioMessage.genByteArrayForWav())
      }

      var firstRun = true
      val start = System.currentTimeMillis()

      try {
        val resultListener: (String, Boolean, String?) -> Unit =
          { partialResult, done, partialThinkingResult ->
            if (partialResult.startsWith("<ctrl")) {
              // Do nothing. Ignore control tokens.
            } else {
              // Remove the last message if it is a "loading" message.
              // This will only be done once.
              val lastMessage = getLastMessage(model = model)
              val wasLoading = lastMessage?.type == ChatMessageType.LOADING
              if (wasLoading) {
                removeLastMessage(model = model)
              }

              val thinkingText = partialThinkingResult
              val isThinking = thinkingText != null && thinkingText.isNotEmpty()
              var currentLastMessage = getLastMessage(model = model)

              // If thinking is enabled, add a thinking message.
              if (isThinking) {
                if (currentLastMessage?.type != ChatMessageType.THINKING) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = "",
                        inProgress = true,
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                      ),
                  )
                }
                updateLastThinkingMessageContentIncrementally(
                  model = model,
                  partialContent = thinkingText!!,
                )
              } else {
                if (currentLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = currentLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                currentLastMessage = getLastMessage(model = model)
                if (
                  currentLastMessage?.type != ChatMessageType.TEXT ||
                    currentLastMessage.side != ChatSide.AGENT
                ) {
                  // Add an empty message that will receive streaming results.
                  addMessage(
                    model = model,
                    message =
                      ChatMessageText(
                        content = "",
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                            currentLastMessage?.type == ChatMessageType.THINKING,
                      ),
                  )
                }

                // Incrementally update the streamed partial results.
                val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
                if (partialResult.isNotEmpty() || wasLoading || done) {
                  updateLastTextMessageContentIncrementally(
                    model = model,
                    partialContent = partialResult,
                    latencyMs = latencyMs.toFloat(),
                  )
                }

                // --- TTS HOOK (additive) ---
                if (_voiceModeEnabled.value && kokoroTts != null && partialResult.isNotEmpty()) {
                  responseAccumulator.append(partialResult)
                  val chunks = TextChunkSentinel.extractCompletedChunks(responseAccumulator)
                  chunks.forEach { chunk ->
                    kokoroTts.enqueue(chunk)
                  }
                }
                // --- END TTS HOOK ---
              }

              if (firstRun) {
                firstRun = false
                setPreparing(false)
                onFirstToken(model)
              }

              if (done) {
                val finalLastMessage = getLastMessage(model = model)
                if (finalLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = finalLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                // --- TTS COMPLETION HOOK (additive) ---
                if (_voiceModeEnabled.value && kokoroTts != null) {
                  val remaining = responseAccumulator.toString().trim()
                  if (remaining.isNotBlank()) {
                    kokoroTts.enqueue(remaining)
                  }
                  responseAccumulator.clear()
                  startTtsPlaybackLoop()
                }
                // --- END TTS COMPLETION HOOK ---
                setInProgress(false)
                onDone()
              }
            }
          }

        val cleanUpListener: () -> Unit = {
          setInProgress(false)
          setPreparing(false)
        }

        val errorListener: (String) -> Unit = { message ->
          Log.e(TAG, "Error occurred while running inference")
          setInProgress(false)
          setPreparing(false)
          onError(message)
        }

        val enableThinking =
          allowThinking &&
            model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
        val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else null

        model.runtimeHelper.runInference(
          model = model,
          input = input,
          images = images,
          audioClips = audioClips,
          resultListener = resultListener,
          cleanUpListener = cleanUpListener,
          onError = errorListener,
          coroutineScope = viewModelScope,
          extraContext = extraContext,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error occurred while running inference", e)
        setInProgress(false)
        setPreparing(false)
        onError(e.message ?: "")
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    setInProgress(false)
    model.runtimeHelper.stopResponse(model)
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = listOf(),
    clearHistory: Boolean = true,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      if (clearHistory) {
        clearAllMessages(model = model)
      }
      stopResponse(model = model)

      while (true) {
        try {
          model.runtimeHelper.resetConversation(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemInstruction = systemInstruction,
            tools = tools,
            enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
            initialMessages = initialMessages,
          )
          break
        } catch (e: Exception) {
          Log.d(TAG, "Failed to reset session. Trying again")
        }
        delay(200)
      }
      setIsResettingSession(false)
      onDone()
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(
            context = context,
            task = task,
            model = model,
            onDone = {
              // Add a warning message for re-initializing the session.
              addMessage(
                model = model,
                message = ChatMessageWarning(content = "Session re-initialized"),
              )
            },
            onError = {
              addMessage(
                model = model,
                message = ChatMessageError(content = "Failed to re-initialize session"),
              )
            },
          )
        },
      )
    }
  }
  protected fun startTtsPlaybackLoop() {
    if (kokoroTts == null || audioPlayer == null || muteSignal == null) return
    ttsJob?.cancel()
    ttsJob = viewModelScope.launch(Dispatchers.IO) {
      // Signal VAD to mute while TTS is speaking — AEC handles most of this,
      // but muting eliminates any residual false triggers.
      muteSignal.setMuted(true)
      audioPlayer.play()

      // Wait for Kokoro synthesis to produce the first samples before draining.
      // Cap the wait at 5 seconds to avoid hanging forever if synthesis fails.
      val synthesisWaitStart = System.currentTimeMillis()
      while (kokoroTts.available() == 0) {
        if (System.currentTimeMillis() - synthesisWaitStart > 5000L) {
          Log.w(TAG, "TTS synthesis timed out after 5s — no audio produced")
          muteSignal.setMuted(false)
          return@launch
        }
        delay(20)
      }

      // Now drain until the buffer has been empty for 500ms (25 reads × 20ms).
      // 500ms is long enough to bridge gaps between synthesized sentences.
      val chunkBuffer = ShortArray(4096)
      var consecutiveEmptyReads = 0
      while (consecutiveEmptyReads < 25) {
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
}

@HiltViewModel
class LlmChatViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  kokoroTts: KokoroTtsEngine,
  audioPlayer: AudioTrackPlayer,
  voiceController: VoiceModeController,
  muteSignal: VoiceModeMuteSignal,
) : LlmChatViewModelBase(
  systemPromptRepository,
  userDataDataStore,
  null,
  kokoroTts,
  audioPlayer,
  voiceController,
  muteSignal,
) {
  override fun onCleared() {
    super.onCleared()
    if (voiceModeEnabled.value) {
      voiceController?.stopListening()
    }
    voiceController?.destroy()
    audioPlayer?.release()
    kokoroTts?.destroy()
  }

  private var activeVoiceModel: Model? = null

  fun toggleVoiceMode(model: Model) {
    if (_voiceModeEnabled.value) {
      // Turning off
      voiceController?.stopListening()
      voiceEventJob?.cancel()
      _voiceModeEnabled.value = false
      activeVoiceModel = null
    } else {
      // Turning on
      activeVoiceModel = model
      _voiceModeEnabled.value = true
      startVoiceEventCollection()
      voiceController?.startListening(viewModelScope)
    }
  }

  private fun startVoiceEventCollection() {
    voiceEventJob = viewModelScope.launch {
      voiceController?.events?.collect { event ->
        _vadState.value = event
        when (event) {
          is VoiceEvent.UtteranceReady -> {
            if (event.transcript.isNotBlank()) {
              Log.d(TAG, "Voice utterance ready: ${event.transcript}")
              val currentModel = activeVoiceModel ?: return@collect
              addMessage(
                model = currentModel,
                message = ChatMessageText(
                  content = event.transcript,
                  side = ChatSide.USER,
                )
              )
              generateResponse(
                model = currentModel,
                input = event.transcript,
                onError = { Log.e(TAG, "Voice inference error: $it") },
              )
            }
          }
          is VoiceEvent.RecognitionError -> {
            Log.e(TAG, "Voice recognition error: ${event.code}")
          }
          else -> { /* UI state updates handled via _vadState */ }
        }
      }
    }
  }

}

@HiltViewModel
class LlmAskImageViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
) : LlmChatViewModelBase(systemPromptRepository, userDataDataStore, null)

@HiltViewModel
class LlmAskAudioViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  ) : LlmChatViewModelBase(systemPromptRepository, userDataDataStore, null)
