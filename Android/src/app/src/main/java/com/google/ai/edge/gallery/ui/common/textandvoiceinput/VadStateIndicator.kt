package com.google.ai.edge.gallery.ui.common.textandvoiceinput

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.voice.VoiceEvent

@Composable
fun VadStateIndicator(vadState: VoiceEvent, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "vad")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (vadState is VoiceEvent.SpeechDetected) 1.5f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val (icon, color, bgAlpha) = when (vadState) {
        is VoiceEvent.ListeningStopped -> 
            Triple(Icons.Rounded.MicOff, MaterialTheme.colorScheme.onSurfaceVariant, 0.1f)
        is VoiceEvent.ListeningStarted, is VoiceEvent.SilenceTimeout -> 
            Triple(Icons.Rounded.Mic, MaterialTheme.colorScheme.primary, 0.2f)
        is VoiceEvent.SpeechDetected -> 
            Triple(Icons.Rounded.GraphicEq, MaterialTheme.colorScheme.primary, 0.4f)
        is VoiceEvent.UtteranceReady -> 
            Triple(Icons.Rounded.GraphicEq, MaterialTheme.colorScheme.tertiary, 0.4f)
        is VoiceEvent.RecognitionError -> 
            Triple(Icons.Rounded.MicOff, MaterialTheme.colorScheme.error, 0.2f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .scale(scale)
            .background(color.copy(alpha = bgAlpha), CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Voice State",
            tint = color,
            modifier = Modifier.size(24.dp)
        )
    }
}
