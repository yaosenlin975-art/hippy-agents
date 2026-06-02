package com.lin.hippyagent.core.notification

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.MainActivity
import com.lin.hippyagent.ui.theme.HippyTheme

class LockScreenMessageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val agentName = intent.getStringExtra(EXTRA_AGENT_NAME).orEmpty()
        val sessionName = intent.getStringExtra(EXTRA_SESSION_NAME).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()

        setContent {
            HippyTheme {
                LockScreenContent(
                    agentName = agentName,
                    sessionName = sessionName,
                    message = message,
                    onTap = {
                        openSession(sessionId)
                        finish()
                    }
                )
            }
        }
    }

    private fun openSession(sessionId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("deep_link_session_id", sessionId)
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_AGENT_NAME = "extra_agent_name"
        const val EXTRA_SESSION_NAME = "extra_session_name"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}

@Composable
private fun LockScreenContent(
    agentName: String,
    sessionName: String,
    message: String,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable { onTap() }
            .systemBarsPadding()
            .statusBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                val title = if (sessionName.isNotEmpty() && sessionName != agentName) {
                    "$agentName · $sessionName"
                } else {
                    agentName.ifEmpty { "智能体" }
                }
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    color = Color(0xFFE0E0E0),
                    fontSize = 14.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "点击进入会话",
                    color = Color(0xFF80CBC4),
                    fontSize = 12.sp
                )
            }
        }
    }
}
