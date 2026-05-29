package com.lin.hippyagent.ui.conversation

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.agent.session.BadgeLevel
import com.lin.hippyagent.core.agent.session.Session
import com.lin.hippyagent.ui.components.HippyTopBar
import com.lin.hippyagent.ui.components.getAvatarIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lin.hippyagent.core.agent.collaboration.GroupInfo
import com.lin.hippyagent.ui.components.PulsingStatusDot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToCreateGroup: () -> Unit,
    onNavigateToGroupChat: (String) -> Unit = {},
    onNavigateToAgentConfig: (String) -> Unit = {},
    onNavigateToCreateAgent: () -> Unit = {},
    onAgentSwitched: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasEnabledAgent by remember { derivedStateOf { uiState.agents.any { it.value.enabled } } }
    val allAgentsDisabled by remember { derivedStateOf { uiState.agents.isNotEmpty() && uiState.agents.none { it.value.enabled } } }
    var showFabMenu by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAgentDrawer by remember { mutableStateOf(false) }
    var groupsExpanded by remember { mutableStateOf(true) }
    var inactiveExpanded by rememberSaveable { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<Session?>(null) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var draggingSessionId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadSessions()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.clickable { showAgentDrawer = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val avatarUrl = uiState.currentAgent?.avatarUrl?.takeIf { it.isNotEmpty() }
                        if (avatarUrl != null && (avatarUrl.startsWith("/") || avatarUrl.startsWith("content://"))) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(avatarUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = stringResource(R.string.conversation_avatar),
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = getAvatarIcon(uiState.currentAgent?.agentId ?: ""),
                                contentDescription = stringResource(R.string.conversation_avatar),
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = uiState.currentAgent?.name ?: stringResource(R.string.conversation_agent_default_name),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "▼",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = { showSearchBar = !showSearchBar },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
            if (showSearchBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.TextField(
                        value = searchQuery,
                        onValueChange = { query ->
                            searchQuery = query
                            viewModel.searchSessions(query)
                        },
                        placeholder = { Text(stringResource(R.string.conversation_search_hint), fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.TextFieldDefaults.textFieldColors(
                            containerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                searchQuery = ""
                                viewModel.searchSessions("")
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.conversation_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val sessionMap = remember(uiState.allSessions) { uiState.allSessions.associateBy { it.id } }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val threshold = uiState.inactiveThresholdMinutes
                    val now = Instant.now()
                    val (activeSessions, inactiveSessions) = if (threshold > 0) {
                        uiState.sessions.filter { it.agentId != "group" }.partition { session ->
                            session.isPinned ||
                                session.groupId != null ||
                                ChronoUnit.MINUTES.between(session.lastUpdatedAt, now) < threshold
                        }
                    } else {
                        uiState.sessions.filter { it.agentId != "group" } to emptyList()
                    }

                    val ungroupedActive = activeSessions.filter { it.groupId == null && it.agentId != "group" }
                    val groupedActive = activeSessions.filter { it.groupId != null }
                    val agentSessionGroups = uiState.sessionGroups.filter { g ->
                        g.agentId == uiState.currentAgentId
                    }

                    // 未分组的活跃会话
                    itemsIndexed(ungroupedActive, key = { _, session -> session.id }) { index, session ->
                        val effectiveStatus = uiState.sessionStatuses[session.id]
                        SessionCard(
                            session = session,
                            agentStatus = effectiveStatus,
                            badgeLevel = uiState.unreadSummary.sessionBadges[session.id] ?: BadgeLevel.NONE,
                            effectiveUnreadCount = uiState.unreadSummary.sessionUnreadCounts[session.id] ?: session.unreadCount,
                            agentName = uiState.agents[session.agentId]?.name?.ifEmpty { session.agentId } ?: session.agentId,
                            agentAvatarUrl = uiState.agents[session.agentId]?.avatarUrl,
                            isDragging = draggingSessionId == session.id,
                            onClick = {
                                viewModel.markAsRead(session.id)
                                onNavigateToChat(session.id, session.agentId)
                            },
                            onDelete = { sessionToDelete = session },
                            onPin = { viewModel.pinSession(session.id, !session.isPinned) },
                            onToggleRead = { viewModel.markAsRead(session.id) },
                            onRename = { newTitle -> viewModel.renameSession(session.id, newTitle) },
                            onMute = { muted -> viewModel.setMuted(session.id, muted) },
                            onLongPress = { draggingSessionId = session.id }
                        )
                        if (index < ungroupedActive.lastIndex || agentSessionGroups.isNotEmpty() || inactiveSessions.isNotEmpty()) {
                            WechatDivider(modifier = Modifier.padding(start = 16.dp))
                        }
                    }

                    // 自定义分组
                    agentSessionGroups.forEach { group ->
                        val groupSessions = groupedActive.filter { it.groupId == group.id }
                        val isCollapsed = group.id in uiState.collapsedGroups
                        val isDropTarget = draggingSessionId != null

                        item(key = "group_header_${group.id}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isDropTarget) Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                draggingSessionId?.let { sid ->
                                                    viewModel.moveSessionToGroup(sid, group.id)
                                                    draggingSessionId = null
                                                }
                                            }
                                        else Modifier.clickable { viewModel.toggleGroupCollapsed(group.id) }
                                    )
                                    .padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isDropTarget) Icons.Default.FolderOpen else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isDropTarget) stringResource(R.string.conversation_drop_to_group) else if (isCollapsed) stringResource(R.string.common_expand) else stringResource(R.string.common_collapse),
                                    tint = if (isDropTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .then(if (!isDropTarget) Modifier.graphicsLayer(rotationZ = if (isCollapsed) -90f else 0f) else Modifier)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isDropTarget) stringResource(R.string.conversation_move_to, group.name) else group.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDropTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${groupSessions.size}",
                                    fontSize = 12.sp,
                                    color = if (isDropTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            WechatDivider(modifier = Modifier.padding(start = 16.dp))
                        }

                        if (!isCollapsed) {
                            itemsIndexed(groupSessions, key = { _, s -> "group_${group.id}_${s.id}" }) { index, session ->
                                val effectiveStatus = uiState.sessionStatuses[session.id]
                                SessionCard(
                                    session = session,
                                    agentStatus = effectiveStatus,
                                    badgeLevel = uiState.unreadSummary.sessionBadges[session.id] ?: BadgeLevel.NONE,
                                    effectiveUnreadCount = uiState.unreadSummary.sessionUnreadCounts[session.id] ?: session.unreadCount,
                                    agentName = uiState.agents[session.agentId]?.name?.ifEmpty { session.agentId } ?: session.agentId,
                                    agentAvatarUrl = uiState.agents[session.agentId]?.avatarUrl,
                                    isDragging = draggingSessionId == session.id,
                                    onClick = {
                                        viewModel.markAsRead(session.id)
                                        onNavigateToChat(session.id, session.agentId)
                                    },
                                    onDelete = { sessionToDelete = session },
                                    onPin = { viewModel.pinSession(session.id, !session.isPinned) },
                                    onToggleRead = { viewModel.markAsRead(session.id) },
                                    onRename = { newTitle -> viewModel.renameSession(session.id, newTitle) },
                                    onMute = { muted -> viewModel.setMuted(session.id, muted) },
                                    onLongPress = { draggingSessionId = session.id }
                                )
                                if (index < groupSessions.lastIndex) {
                                    WechatDivider(modifier = Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    }

                    // 不活跃对话折叠 Section
                    if (inactiveSessions.isNotEmpty()) {
                        item(key = "inactive_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { inactiveExpanded = !inactiveExpanded }
                                    .padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.conversation_inactive),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${inactiveSessions.size}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (inactiveExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer(rotationZ = if (inactiveExpanded) 0f else -90f)
                                )
                            }
                            WechatDivider(modifier = Modifier.padding(start = 16.dp))
                        }

                        if (inactiveExpanded) {
                            itemsIndexed(inactiveSessions, key = { _, s -> "inactive_${s.id}" }) { index, session ->
                                val effectiveStatus = uiState.sessionStatuses[session.id]
                                SessionCard(
                                    session = session,
                                    agentStatus = effectiveStatus,
                                    badgeLevel = uiState.unreadSummary.sessionBadges[session.id] ?: BadgeLevel.NONE,
                                    effectiveUnreadCount = uiState.unreadSummary.sessionUnreadCounts[session.id] ?: session.unreadCount,
                                    agentName = uiState.agents[session.agentId]?.name?.ifEmpty { session.agentId } ?: session.agentId,
                                    agentAvatarUrl = uiState.agents[session.agentId]?.avatarUrl,
                                    isDragging = draggingSessionId == session.id,
                                    onClick = {
                                        viewModel.markAsRead(session.id)
                                        onNavigateToChat(session.id, session.agentId)
                                    },
                                    onDelete = { sessionToDelete = session },
                                    onPin = { viewModel.pinSession(session.id, !session.isPinned) },
                                    onToggleRead = { viewModel.markAsRead(session.id) },
                                    onRename = { newTitle -> viewModel.renameSession(session.id, newTitle) },
                                    onMute = { muted -> viewModel.setMuted(session.id, muted) },
                                    onLongPress = { draggingSessionId = session.id }
                                )
                                if (index < inactiveSessions.lastIndex || uiState.groups.isNotEmpty()) {
                                    WechatDivider(modifier = Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    }

                    // 群组
                    if (uiState.groups.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { groupsExpanded = !groupsExpanded }
                                    .padding(start = 16.dp, top = 16.dp, bottom = 8.dp, end = 16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.conversation_group),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${uiState.groups.size}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (groupsExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer(
                                            rotationZ = if (groupsExpanded) 0f else -90f
                                        )
                                )
                            }
                        }
                        if (groupsExpanded) {
                            itemsIndexed(uiState.groups) { index, group ->
                                val groupSession = sessionMap[group.groupId]
                                GroupCard(
                                    group = group,
                                    agents = uiState.agents,
                                    badgeLevel = uiState.unreadSummary.sessionBadges[group.groupId] ?: BadgeLevel.NONE,
                                    lastMessage = groupSession?.lastMessage,
                                    lastUpdatedAt = groupSession?.lastUpdatedAt,
                                    unreadCount = uiState.unreadSummary.sessionUnreadCounts[group.groupId] ?: groupSession?.unreadCount ?: 0,
                                    onClick = { onNavigateToGroupChat(group.groupId) }
                                )
                                if (index < uiState.groups.lastIndex) {
                                    WechatDivider(modifier = Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    }

                    if (uiState.sessions.isEmpty() && uiState.groups.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (searchQuery.isNotEmpty()) stringResource(R.string.conversation_no_match) else stringResource(R.string.no_sessions),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 15.sp
                                    )
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = if (allAgentsDisabled) stringResource(R.string.conversation_all_disabled) else stringResource(R.string.conversation_create_from_bar),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
            }
        }

        if (showAgentDrawer) {
            AgentSwitcherDrawer(
                agents = uiState.agents.values.toList(),
                currentAgentId = uiState.currentAgentId,
                onAgentSelected = { agentId ->
                    viewModel.switchAgent(agentId)
                    onAgentSwitched(agentId)
                    showAgentDrawer = false
                },
                onToggleEnabled = { agentId, enabled ->
                    viewModel.toggleAgentEnabled(agentId, enabled)
                },
                onCreateAgent = {
                    showAgentDrawer = false
                    onNavigateToCreateAgent()
                },
                onDismiss = { showAgentDrawer = false }
            )
        }

        sessionToDelete?.let { session ->
            AlertDialog(
                onDismissRequest = { sessionToDelete = null },
                title = { Text(stringResource(R.string.delete_session)) },
                text = { Text(stringResource(R.string.conversation_delete_confirm, session.title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteSession(session.id)
                            sessionToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { sessionToDelete = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (showCreateGroupDialog) {
            AlertDialog(
                onDismissRequest = {
                    showCreateGroupDialog = false
                    newGroupName = ""
                },
                title = { Text(stringResource(R.string.conversation_new_group)) },
                text = {
                    TextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        placeholder = { Text(stringResource(R.string.conversation_group_name)) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newGroupName.isNotBlank()) {
                                viewModel.createSessionGroup(newGroupName.trim())
                                newGroupName = ""
                            }
                            showCreateGroupDialog = false
                        },
                        enabled = newGroupName.isNotBlank()
                    ) { Text(stringResource(R.string.common_create)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCreateGroupDialog = false
                        newGroupName = ""
                    }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}

@Composable
private fun AgentSwitcherDrawer(
    agents: List<com.lin.hippyagent.core.agent.AgentProfile>,
    currentAgentId: String?,
    onAgentSelected: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onCreateAgent: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Card(
            modifier = Modifier
                .width(260.dp)
                .padding(top = 56.dp, start = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.conversation_select_agent),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
                agents.forEach { agent ->
                    val isSelected = agent.agentId == currentAgentId
                    val isEnabled = agent.enabled
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .then(
                                if (isEnabled && !isSelected) {
                                    Modifier.clickable { onAgentSelected(agent.agentId) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val agentAvatar = agent.avatarUrl?.takeIf { it.isNotEmpty() }
                        if (agentAvatar != null && (agentAvatar.startsWith("/") || agentAvatar.startsWith("content://"))) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(agentAvatar)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = stringResource(R.string.conversation_avatar),
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = getAvatarIcon(agent.agentId),
                                contentDescription = stringResource(R.string.conversation_avatar),
                                modifier = Modifier.size(28.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = agent.name.ifEmpty { agent.agentId },
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                            if (isSelected) {
                                Text(
                                    text = stringResource(R.string.conversation_current_use),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        androidx.compose.material3.Switch(
                            checked = isEnabled,
                            onCheckedChange = { onToggleEnabled(agent.agentId, it) },
                            modifier = Modifier.height(24.dp),
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCreateAgent() }
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.conversation_create_agent),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 右上角设置按钮弹出的抽屉
 */
@Composable
private fun SettingsDrawer(
    agentName: String,
    onAgentSettings: () -> Unit,
    onSystemSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Card(
            modifier = Modifier
                .width(200.dp)
                .padding(top = 56.dp, end = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAgentSettings() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.conversation_agent_settings, agentName),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                HorizontalDivider(thickness = 0.5.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSystemSettings() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.conversation_system_settings),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: Session,
    agentStatus: AgentStatus? = null,
    badgeLevel: BadgeLevel = BadgeLevel.NONE,
    effectiveUnreadCount: Int = session.unreadCount,
    agentName: String = "",
    agentAvatarUrl: String? = null,
    isDragging: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onToggleRead: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onMute: (Boolean) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { (-80).dp.toPx() }
    val maxSwipePx = with(density) { (-180).dp.toPx() }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(MaterialTheme.colorScheme.errorContainer),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 72.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .clickable {
                        onPin()
                        coroutineScope.launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (session.isPinned) Icons.Outlined.PushPin else Icons.Default.PushPin,
                    contentDescription = if (session.isPinned) stringResource(R.string.conversation_unpin) else stringResource(R.string.conversation_pin),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 72.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable {
                        onMute(!session.isMuted)
                        coroutineScope.launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.NotificationsOff,
                    contentDescription = if (session.isMuted) stringResource(R.string.conversation_unmute) else stringResource(R.string.conversation_mute),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 72.dp)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable {
                        onDelete()
                        coroutineScope.launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .height(72.dp)
                .combinedClickable(
                    onClick = {
                        if (offsetX.value < 0f) {
                            coroutineScope.launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = {
                        onLongPress?.invoke()
                    }
                )
                .background(
                    if (isDragging) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.background
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = if (offsetX.value < swipeThresholdPx) maxSwipePx else 0f
                            coroutineScope.launch { offsetX.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) }
                        },
                        onDragCancel = {
                            coroutineScope.launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                        }
                    ) { _, dragAmount ->
                        val newOffset = (offsetX.value + dragAmount).coerceIn(maxSwipePx, 0f)
                        coroutineScope.launch { offsetX.snapTo(newOffset) }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = session.title.take(5).ifEmpty { session.title },
                            fontSize = 15.sp,
                            fontWeight = if (badgeLevel != BadgeLevel.NONE) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (agentStatus != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            when (agentStatus) {
                                AgentStatus.THINKING -> PulsingStatusDot(isThinking = true, label = stringResource(R.string.conversation_thinking))
                                AgentStatus.EXECUTING_TOOL -> PulsingStatusDot(isThinking = false, label = stringResource(R.string.conversation_executing))
                                AgentStatus.IDLE -> StaticStatusDot(color = Color(0xFF4CAF50), label = stringResource(R.string.conversation_idle_status))
                                AgentStatus.ERROR -> StaticStatusDot(color = Color(0xFFF44336), label = stringResource(R.string.conversation_error))
                                AgentStatus.STOPPED -> StaticStatusDot(color = Color(0xFF9E9E9E), label = stringResource(R.string.conversation_stopped))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.isMuted) {
                            Icon(
                                Icons.Default.NotificationsOff,
                                contentDescription = stringResource(R.string.conversation_muted),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = formatInstantTime(session.lastUpdatedAt, LocalContext.current),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = session.lastMessage ?: stringResource(R.string.chat_no_messages),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp)
                    )
                    if (badgeLevel == BadgeLevel.COUNT && effectiveUnreadCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (effectiveUnreadCount > 99) "99+" else effectiveUnreadCount.toString(),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onError,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (badgeLevel == BadgeLevel.DOT) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    }
                }
            }

            if (session.isPinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = stringResource(R.string.chat_pinned),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun WechatDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun GroupCard(
    group: GroupInfo,
    agents: Map<String, com.lin.hippyagent.core.agent.AgentProfile> = emptyMap(),
    badgeLevel: BadgeLevel = BadgeLevel.NONE,
    lastMessage: String? = null,
    lastUpdatedAt: java.time.Instant? = null,
    unreadCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupAvatarGrid(
            agentIds = group.agentIds,
            agents = agents,
            modifier = Modifier.size(48.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.groupName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (lastUpdatedAt != null) {
                    Text(
                        text = formatInstantTime(lastUpdatedAt, LocalContext.current),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (!lastMessage.isNullOrBlank()) {
                Text(
                    text = lastMessage,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (unreadCount > 0) {
            if (badgeLevel == BadgeLevel.COUNT) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onError,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (badgeLevel == BadgeLevel.DOT) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun GroupAvatarGrid(
    agentIds: List<String>,
    agents: Map<String, com.lin.hippyagent.core.agent.AgentProfile>,
    modifier: Modifier = Modifier
) {
    val displayAgents = agentIds.take(9).mapNotNull { id -> agents[id] }
    val count = displayAgents.size

    if (count == 0) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Groups,
                contentDescription = stringResource(R.string.conversation_group),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = modifier.size(24.dp)
            )
        }
        return
    }

    val gridSize = when {
        count == 1 -> 1
        count == 2 -> 2
        count <= 4 -> 2
        else -> 3
    }
    val cellCount = if (gridSize == 1) 1 else gridSize

    Box(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val rows = if (gridSize == 1) listOf(displayAgents)
            else displayAgents.chunked(gridSize)

            rows.forEach { rowAgents ->
                Row(
                    horizontalArrangement = Arrangement.Center
                ) {
                    rowAgents.forEach { agent ->
                        val cellSize = when (gridSize) {
                            1 -> modifier
                            2 -> Modifier.size(24.dp)
                            else -> Modifier.size(16.dp)
                        }
                        Box(
                            modifier = cellSize,
                            contentAlignment = Alignment.Center
                        ) {
                            val avatarUrl = agent.avatarUrl?.takeIf { it.isNotEmpty() }
                            if (avatarUrl != null && (avatarUrl.startsWith("/") || avatarUrl.startsWith("content://"))) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(avatarUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = agent.name,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(2.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = getAvatarIcon(agent.agentId),
                                    contentDescription = agent.name,
                                    modifier = Modifier.size(
                                        when (gridSize) {
                                            1 -> 24.dp
                                            2 -> 14.dp
                                            else -> 10.dp
                                        }
                                    ),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (rowAgents.size < gridSize) {
                        repeat(gridSize - rowAgents.size) {
                            Box(modifier = when (gridSize) {
                                2 -> Modifier.size(24.dp)
                                else -> Modifier.size(16.dp)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingStatusDot(
    isThinking: Boolean,
    label: String,
    modifier: Modifier = Modifier
) {
    com.lin.hippyagent.ui.components.PulsingStatusDot(isThinking, label, modifier)
}

@Composable
private fun StaticStatusDot(
    color: Color,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatInstantTime(timestamp: Instant, context: Context): String {
    val zoned = timestamp.atZone(ZoneId.systemDefault())
    val localDate = zoned.toLocalDate()
    val now = java.time.LocalDate.now()
    val weekAgo = now.minusDays(7)

    return when {
        localDate == now -> DateTimeFormatter.ofPattern("HH:mm").format(zoned)
        localDate == now.minusDays(1) -> context.getString(R.string.conversation_date_yesterday)
        localDate.year == now.year && localDate in weekAgo..now -> DateTimeFormatter.ofPattern("EEEE").format(localDate)
        localDate.year == now.year -> DateTimeFormatter.ofPattern(context.getString(R.string.conversation_date_month_day)).format(localDate)
        else -> DateTimeFormatter.ofPattern(context.getString(R.string.conversation_date_year_month_day)).format(localDate)
    }
}
