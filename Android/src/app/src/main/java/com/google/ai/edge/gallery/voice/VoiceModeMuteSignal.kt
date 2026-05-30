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
