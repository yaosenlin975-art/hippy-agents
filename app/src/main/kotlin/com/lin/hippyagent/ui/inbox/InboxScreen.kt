package com.lin.hippyagent.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.session.InboxEvent
import com.lin.hippyagent.ui.components.HippyTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class InboxTab { Events, Tasks }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onBackClick: () -> Unit,
    onTaskClick: (String) -> Unit = {},
    initialTab: InboxTab = InboxTab.Events,
    modifier: Modifier = Modifier
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val taskListViewModel: com.lin.hippyagent.ui.task.TaskListViewModel = org.koin.androidx.compose.koinViewModel()

    var selectedTab by remember { mutableStateOf(initialTab) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.nav_inbox),
                showBackButton = false,
                onBackClick = onBackClick,
                actions = {
                    if (selectedTab == InboxTab.Events && unreadCount > 0) {
                        IconButton(onClick = { viewModel.markAllRead() }) {
                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.inbox_mark_all_read))
                        }
                    }
                    IconButton(onClick = {
                        if (selectedTab == InboxTab.Events) viewModel.refresh()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                InboxTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                when (tab) {
                                    InboxTab.Events -> stringResource(R.string.inbox_tab_events)
                                    InboxTab.Tasks -> stringResource(R.string.inbox_tab_tasks)
                                }
                            )
                        }
                    )
                }
            }
            when (selectedTab) {
                InboxTab.Events -> EventsTab(
                    events = events,
                    onMarkRead = { viewModel.markRead(it) },
                    onDelete = { viewModel.deleteEvent(it) },
                    modifier = Modifier.fillMaxSize()
                )
                InboxTab.Tasks -> TasksTab(
                    viewModel = taskListViewModel,
                    onTaskClick = onTaskClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun TasksTab(
    viewModel: com.lin.hippyagent.ui.task.TaskListViewModel,
    onTaskClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    if (tasks.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.task_list_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(items = tasks, key = { it.id }) { task ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTaskClick(task.id) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = task.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${task.status.name} · ${formatTimestamp(task.createdAt)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


@Composable
private fun EventsTab(
    events: List<InboxEvent>,
    onMarkRead: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) {
        EmptyStateView(message = stringResource(R.string.inbox_no_events))
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(items = events, key = { it.id }) { event ->
            EventCard(
                event = event,
                onMarkRead = { onMarkRead(event.id) },
                onDelete = { onDelete(event.id) }
            )
        }
    }
}

@Composable
private fun EventCard(
    event: InboxEvent,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit
) {
    val backgroundColor = if (!event.read) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!event.read) onMarkRead() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (!event.read) 2.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (!event.read) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(top = 6.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(status = event.status, severity = event.severity)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = event.title,
                        fontSize = 15.sp,
                        fontWeight = if (!event.read) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (event.body.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = event.body,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = event.sourceType,
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
    }
}

@Composable
private fun StatusIcon(status: String, severity: String) {
    val (icon, tint) = when (status) {
        "success" -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        "error" -> Icons.Default.Error to Color(0xFFE53935)
        "timeout" -> Icons.Default.Warning to Color(0xFFFF9800)
        "pending" -> Icons.Default.Info to Color(0xFF2196F3)
        "cancelled" -> Icons.Default.Warning to Color(0xFF9E9E9E)
        else -> when (severity) {
            "critical" -> Icons.Default.Error to Color(0xFFD32F2F)
            "error" -> Icons.Default.Error to Color(0xFFE53935)
            "warning" -> Icons.Default.Warning to Color(0xFFFF9800)
            else -> Icons.Default.Info to Color(0xFF2196F3)
        }
    }
    Icon(
        imageVector = icon,
        contentDescription = status,
        tint = tint,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun EmptyStateView(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val TIMESTAMP_FORMATTER = ThreadLocal.withInitial {
    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
}

private fun formatTimestamp(timestamp: Long): String {
    return TIMESTAMP_FORMATTER.get()!!.format(Date(timestamp))
}
