package com.lin.hippyagent.ui.chat

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun VoiceBubble(
    filePath: String,
    durationMs: Long,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(isPlaying) {
        while (isPlaying && durationMs > 0) {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    progress = mp.currentPosition.toFloat() / durationMs.toFloat()
                }
            }
            delay(100)
        }
    }

    DisposableEffect(filePath) {
        onDispose {
            mediaPlayer?.apply {
                runCatching {
                    if (isPlaying) stop()
                    release()
                }
            }
            mediaPlayer = null
            isPlaying = false
            isPrepared = false
        }
    }

    val durationSec = (durationMs / 1000).toInt()
    val durationText = if (durationSec >= 60) "${durationSec / 60}:${String.format("%02d", durationSec % 60)}"
    else "${durationSec}\""

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable {
                val mp = mediaPlayer
                if (mp != null && isPrepared) {
                    if (mp.isPlaying) {
                        runCatching { mp.pause() }
                        isPlaying = false
                    } else {
                        runCatching { mp.start() }
                        isPlaying = true
                    }
                } else {
                    val file = File(filePath)
                    if (!file.exists()) return@clickable

                    mp?.runCatching { release() }
                    val newMp = MediaPlayer()
                    isPrepared = false
                    runCatching {
                        newMp.setDataSource(filePath)
                        newMp.prepareAsync()
                    }.onFailure {
                        runCatching { newMp.release() }
                        return@clickable
                    }

                    newMp.setOnPreparedListener {
                        isPrepared = true
                        it.start()
                        isPlaying = true
                    }

                    newMp.setOnCompletionListener {
                        isPlaying = false
                        progress = 0f
                        runCatching { it.release() }
                        mediaPlayer = null
                        isPrepared = false
                    }

                    newMp.setOnErrorListener { _, _, _ ->
                        isPlaying = false
                        runCatching { newMp.release() }
                        mediaPlayer = null
                        isPrepared = false
                        true
                    }

                    mediaPlayer = newMp
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            modifier = Modifier.size(28.dp),
            tint = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = durationText,
            fontSize = 13.sp,
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
