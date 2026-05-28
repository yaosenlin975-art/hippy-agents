package com.lin.hippyagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.agent.session.BadgeLevel
import com.lin.hippyagent.core.agent.session.Session
import com.lin.hippyagent.ui.components.PulsingStatusDot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSessionDrawer(
    sessions: List<Session>,
    currentSessionId: String,
    sessionBadges: Map<String, BadgeLevel>,
    sessionUnreadCounts: Map<String, Int> = emptyMap(),
    sessionStatuses: Map<String, AgentStatus> = emptyMap(),
    onSessionClick: (Session) -> Unit,
    onNewSession: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(320.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "会话列表",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onNewSession) {
                    Icon(Icons.Default.Add, "新建会话", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭")
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn {
                items(sessions, key = { it.id }) { session ->
                    DrawerSessionItem(
                        session = session,
                        isCurrent = session.id == currentSessionId,
                        badgeLevel = sessionBadges[session.id] ?: BadgeLevel.NONE,
                        effectiveUnreadCount = sessionUnreadCounts[session.id] ?: session.unreadCount,
                        agentStatus = sessionStatuses[session.id],
                        onClick = { onSessionClick(session) }
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun DrawerSessionItem(
    session: Session,
    isCurrent: Boolean,
    badgeLevel: BadgeLevel,
    effectiveUnreadCount: Int = session.unreadCount,
    agentStatus: AgentStatus? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "已置顶",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = session.title.take(5).ifEmpty { session.title },
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (agentStatus != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    when (agentStatus) {
                        AgentStatus.THINKING -> PulsingStatusDot(isThinking = true, label = "思考中")
                        AgentStatus.EXECUTING_TOOL -> PulsingStatusDot(isThinking = false, label = "执行中")
                        AgentStatus.ERROR -> Text("错误", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        AgentStatus.STOPPED -> Text("已停止", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AgentStatus.IDLE -> {}
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.lastMessage ?: "暂无消息",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDrawerTime(session.lastUpdatedAt),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (badgeLevel == BadgeLevel.COUNT && effectiveUnreadCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (effectiveUnreadCount > 99) "99+" else effectiveUnreadCount.toString(),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (badgeLevel == BadgeLevel.DOT) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            )
        }
    }
}

private fun formatDrawerTime(timestamp: Instant): String {
    val localDate = timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
    val now = java.time.LocalDate.now()
    return when {
        localDate == now -> DateTimeFormatter.ofPattern("HH:mm").format(timestamp.atZone(ZoneId.systemDefault()))
        localDate == now.minusDays(1) -> "昨天"
        localDate.year == now.year -> DateTimeFormatter.ofPattern("M/d").format(localDate)
        else -> DateTimeFormatter.ofPattern("yyyy/M/d").format(localDate)
    }
}

