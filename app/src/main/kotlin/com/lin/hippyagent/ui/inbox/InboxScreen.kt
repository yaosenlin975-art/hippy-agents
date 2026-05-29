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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.lin.hippyagent.core.agent.session.PendingApproval
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.ExperimentalFoundationApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val approvals by viewModel.approvals.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()

    val tabTitles = listOf(stringResource(R.string.inbox_approval_requests), stringResource(R.string.inbox_push_messages))

    val pagerState = rememberPagerState(initialPage = 0) { tabTitles.size }
    val coroutineScope = rememberCoroutineScope()

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
                    if (unreadCount > 0) {
                        IconButton(onClick = { viewModel.markAllRead() }) {
                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.inbox_mark_all_read))
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_logs))
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = {
                            Text(
                                text = if (index == 0 && approvals.isNotEmpty()) {
                                    "$title (${approvals.size})"
                                } else if (index == 1 && unreadCount > 0) {
                                    "$title ($unreadCount)"
                                } else {
                                    title
                                },
                                fontWeight = if (pagerState.currentPage == index) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> ApprovalsTab(
                        approvals = approvals,
                        onApprove = { viewModel.approve(it) },
                        onDeny = { viewModel.deny(it) }
                    )
                    1 -> EventsTab(
                        events = events,
                        onMarkRead = { viewModel.markRead(it) },
                        onDelete = { viewModel.deleteEvent(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalsTab(
    approvals: List<PendingApproval>,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit
) {
    if (approvals.isEmpty()) {
        EmptyStateView(message = stringResource(R.string.inbox_no_approvals))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(items = approvals, key = { it.requestId }) { approval ->
            ApprovalCard(
                approval = approval,
                onApprove = { onApprove(approval.requestId) },
                onDeny = { onDeny(approval.requestId) }
            )
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: PendingApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val isPending = approval.status == "pending"
    val cardAlpha = if (isPending) 1f else 0.6f
    val statusLabel = when (approval.status) {
        "approved" -> stringResource(R.string.inbox_approved)
        "denied" -> stringResource(R.string.inbox_denied)
        "timeout" -> stringResource(R.string.inbox_timed_out)
        else -> null
    }
    val statusColor = when (approval.status) {
        "approved" -> Color(0xFF4CAF50)
        "denied" -> Color(0xFF9E9E9E)
        "timeout" -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = cardAlpha)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPending) 1.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                SeverityIcon(severity = approval.severity)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = approval.toolName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = cardAlpha)
                )
                Spacer(modifier = Modifier.weight(1f))
                if (statusLabel != null) {
                    Text(
                        text = statusLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                        modifier = Modifier
                            .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = formatTimestamp(approval.createdAt),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = cardAlpha)
                )
            }

            if (approval.findingsSummary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = approval.findingsSummary,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = cardAlpha),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (approval.findingsCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.inbox_findings_count, approval.findingsCount),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = cardAlpha),
                    fontWeight = FontWeight.Medium
                )
            }

            if (isPending) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.inbox_deny))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.inbox_approve))
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsTab(
    events: List<InboxEvent>,
    onMarkRead: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    if (events.isEmpty()) {
        EmptyStateView(message = stringResource(R.string.inbox_no_events))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
private fun SeverityIcon(severity: String) {
    val (icon, tint) = when (severity) {
        "critical" -> Icons.Default.Error to Color(0xFFD32F2F)
        "error" -> Icons.Default.Error to Color(0xFFE53935)
        "warning" -> Icons.Default.Warning to Color(0xFFFF9800)
        else -> Icons.Default.Info to Color(0xFF2196F3)
    }
    Icon(
        imageVector = icon,
        contentDescription = severity,
        tint = tint,
        modifier = Modifier.size(20.dp)
    )
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

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
