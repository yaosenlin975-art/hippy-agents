package com.lin.hippyagent.core.notification

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.ui.MainActivity
import kotlinx.coroutines.delay

private const val BUBBLE_AUTO_DISMISS_MS = 6_000L

@Composable
fun InAppMessageBubbleHost(context: Context) {
    var message by remember { mutableStateOf<InAppMessageBus.InAppMessage?>(null) }
    var visible by remember { mutableStateOf(false) }
    var lastShownId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        InAppMessageBus.events.collect { msg ->
            message = msg
            if (msg.sessionId != lastShownId) {
                lastShownId = msg.sessionId
                visible = true
                delay(BUBBLE_AUTO_DISMISS_MS)
                visible = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible && message != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val m = message ?: return@AnimatedVisibility
            InAppBubble(
                agentName = m.agentName,
                sessionName = m.sessionName,
                message = m.message,
                onTap = {
                    visible = false
                    openSession(context, m.sessionId)
                }
            )
        }
    }
}

@Composable
private fun InAppBubble(
    agentName: String,
    sessionName: String,
    message: String,
    onTap: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E1E1E),
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            val title = if (sessionName.isNotEmpty() && sessionName != agentName) {
                "$agentName · $sessionName"
            } else {
                agentName.ifEmpty { "智能体" }
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                color = Color(0xFFE0E0E0),
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun openSession(context: Context, sessionId: String) {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("deep_link_session_id", sessionId)
    }
    context.startActivity(intent)
}
