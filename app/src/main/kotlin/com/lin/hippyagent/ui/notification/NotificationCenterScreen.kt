package com.lin.hippyagent.ui.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.core.notification.NotificationEvent
import com.lin.hippyagent.core.notification.NotificationType
import com.lin.hippyagent.ui.components.HippyTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TYPE_TO_CHANNEL_ID: Map<NotificationType, String> = mapOf(
    NotificationType.TASK_COMPLETED to "task_events",
    NotificationType.AGENT_ERROR to "agent_errors",
    NotificationType.APPROVAL_REQUEST to "approvals",
    NotificationType.STATUS_CHANGE to "status_changes",
    NotificationType.SYSTEM_MESSAGE to "system_messages"
)

private val TYPE_COLOR: Map<NotificationType, Color> = mapOf(
    NotificationType.TASK_COMPLETED to Color(0xFF4CAF50),
    NotificationType.AGENT_ERROR to Color(0xFFE53935),
    NotificationType.APPROVAL_REQUEST to Color(0xFFFF9800),
    NotificationType.STATUS_CHANGE to Color(0xFF2196F3),
    NotificationType.SYSTEM_MESSAGE to Color(0xFF9E9E9E)
)

private val TYPE_ICON: Map<NotificationType, ImageVector> = mapOf(
    NotificationType.TASK_COMPLETED to Icons.Default.CheckCircle,
    NotificationType.AGENT_ERROR to Icons.Default.Error,
    NotificationType.APPROVAL_REQUEST to Icons.Default.Warning,
    NotificationType.STATUS_CHANGE to Icons.Default.Info,
    NotificationType.SYSTEM_MESSAGE to Icons.Default.Notifications
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    viewModel: NotificationCenterViewModel,
    onBackClick: () -> Unit,
    onNotificationClick: (NotificationEvent) -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    onOpenLog: (String) -> Unit = {},
    onApprovalAction: (NotificationEvent, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val tabTypes = remember { listOf<NotificationType?>(null) + NotificationType.entries }
    val tabResIds = remember {
        listOf(
            R.string.notification_center_tab_all,
            R.string.notification_center_tab_task,
            R.string.notification_center_tab_agent_error,
            R.string.notification_center_tab_approval,
            R.string.notification_center_tab_status,
            R.string.notification_center_tab_system
        )
    }
    val tabLabels = tabResIds.map { stringResource(it) }
    val selectedIndex = tabTypes.indexOf(uiState.activeType).coerceAtLeast(0)

    val filteredEvents by remember(uiState.events, uiState.activeType) {
        derivedStateOf {
            val type = uiState.activeType
            if (type == null) uiState.events else uiState.events.filter { it.type == type }
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.notification_center_title),
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabTypes.forEachIndexed { index, type ->
                    Tab(
                        selected = uiState.activeType == type,
                        onClick = { viewModel.setActiveType(type) },
                        text = {
                            Text(
                                text = tabLabels[index],
                                maxLines = 1,
                                fontWeight = if (uiState.activeType == type) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            if (filteredEvents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.notification_center_empty),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = filteredEvents, key = { it.id }) { event ->
                    NotificationCard(
                        event = event,
                        onNotificationClick = onNotificationClick,
                        onOpenTask = onOpenTask,
                        onOpenLog = onOpenLog,
                        onApprovalAction = onApprovalAction,
                        onMarkRead = { viewModel.markRead(event.id) },
                        onAcknowledge = { viewModel.acknowledge(event.id) },
                        onDismiss = { viewModel.dismiss(event.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    event: NotificationEvent,
    onNotificationClick: (NotificationEvent) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenLog: (String) -> Unit,
    onApprovalAction: (NotificationEvent, Boolean) -> Unit,
    onMarkRead: () -> Unit,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit
) {
    val accent = TYPE_COLOR[event.type] ?: MaterialTheme.colorScheme.primary
    val icon = TYPE_ICON[event.type] ?: Icons.Default.Info
    val isUnread = event.readAt == null
    val isAcked = event.ackedAt != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isUnread) onMarkRead()
                onNotificationClick(event)
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnread) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isUnread) 2.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (isUnread) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp, end = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = event.type.name,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = event.title,
                        fontSize = 15.sp,
                        fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (event.body.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = event.body,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = TYPE_TO_CHANNEL_ID[event.type].orEmpty(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatTimestamp(event.createdAt),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isUnread) {
                    TextAction(
                        label = stringResource(R.string.notification_action_mark_read),
                        onClick = onMarkRead
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (!isAcked) {
                    TextAction(
                        label = stringResource(R.string.notification_action_acknowledge),
                        onClick = onAcknowledge
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                event.actions.forEach { action ->
                    ActionButton(
                        action = action,
                        onNotificationClick = onNotificationClick,
                        onOpenTask = onOpenTask,
                        onOpenLog = onOpenLog,
                        onApprovalAction = onApprovalAction,
                        onMarkRead = onMarkRead,
                        onDismiss = onDismiss,
                        event = event
                    )
                }
            }
        }
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun ActionButton(
    action: String,
    onNotificationClick: (NotificationEvent) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenLog: (String) -> Unit,
    onApprovalAction: (NotificationEvent, Boolean) -> Unit,
    onMarkRead: () -> Unit,
    onDismiss: () -> Unit,
    event: NotificationEvent
) {
    when (action) {
        "open_task" -> IconAction(
            icon = Icons.Default.OpenInNew,
            contentDescription = stringResource(R.string.notification_action_open_task),
            tint = MaterialTheme.colorScheme.primary,
            onClick = {
                onMarkRead()
                onOpenTask(event.source)
            }
        )
        "open_log" -> IconAction(
            icon = Icons.Default.List,
            contentDescription = stringResource(R.string.notification_action_open_log),
            tint = MaterialTheme.colorScheme.primary,
            onClick = {
                onMarkRead()
                onOpenLog(event.source)
            }
        )
        "open" -> IconAction(
            icon = Icons.Default.ArrowForward,
            contentDescription = stringResource(R.string.notification_action_open),
            tint = MaterialTheme.colorScheme.primary,
            onClick = {
                onMarkRead()
                onNotificationClick(event)
            }
        )
        "approve" -> IconAction(
            icon = Icons.Default.Check,
            contentDescription = stringResource(R.string.notification_action_approve),
            tint = Color(0xFF4CAF50),
            onClick = { onApprovalAction(event, true) }
        )
        "reject" -> IconAction(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.notification_action_reject),
            tint = Color(0xFFE53935),
            onClick = { onApprovalAction(event, false) }
        )
        "dismiss" -> IconAction(
            icon = Icons.Default.Delete,
            contentDescription = stringResource(R.string.notification_action_dismiss),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onDismiss
        )
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
