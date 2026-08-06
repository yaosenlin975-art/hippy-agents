package com.lin.hippyagent.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.jeziellago.compose.markdowntext.MarkdownText
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.PermissionType
import com.lin.hippyagent.core.security.RiskLevel
import com.lin.hippyagent.core.skill.SkillManager
import java.io.File
import com.lin.hippyagent.ui.chat.PlanProgressChip
import com.lin.hippyagent.ui.chat.PlanPanel
import com.lin.hippyagent.ui.settings.general.readChatFontSize
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.chat.components.QueueBottomSheet

@Composable
internal fun PermissionTurnCard(
    turn: ChatTurn.PermissionTurn,
    onApproveOnce: () -> Unit,
    onApproveAlways: () -> Unit,
    onDenyOnce: () -> Unit,
    onDenyAlways: () -> Unit,
    modifier: Modifier = Modifier
) {
    val severityColor = when (turn.permissionType) {
        PermissionType.SHELL_COMMAND -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        PermissionType.CUSTOM_TOOL -> MaterialTheme.colorScheme.tertiaryContainer
        PermissionType.ACCESSIBILITY -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        PermissionType.ANDROID_RUNTIME -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainerColor = when (turn.permissionType) {
        PermissionType.SHELL_COMMAND -> MaterialTheme.colorScheme.onErrorContainer
        PermissionType.CUSTOM_TOOL -> MaterialTheme.colorScheme.onTertiaryContainer
        PermissionType.ACCESSIBILITY -> MaterialTheme.colorScheme.onErrorContainer
        PermissionType.ANDROID_RUNTIME -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = severityColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = onContainerColor
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = turn.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainerColor
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = turn.description,
                style = MaterialTheme.typography.labelMedium,
                color = onContainerColor.copy(alpha = 0.85f)
            )
            if (!turn.pendingCommand.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = turn.pendingCommand,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            if (!turn.isResolved) {
                if (turn.findings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (finding in turn.findings.take(3)) {
                            Text(
                                text = "• [${finding.severity.value}] ${finding.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onContainerColor.copy(alpha = 0.7f)
                            )
                        }
                        if (turn.findings.size > 3) {
                            Text(
                                text = stringResource(R.string.chat_more_items, turn.findings.size - 3),
                                style = MaterialTheme.typography.labelSmall,
                                color = onContainerColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = onDenyOnce,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) { Text(stringResource(R.string.chat_deny), style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(
                        onClick = onDenyAlways,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.chat_deny_always), style = MaterialTheme.typography.labelSmall) }
                    Button(
                        onClick = onApproveOnce,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) { Text(stringResource(R.string.chat_approve_once), style = MaterialTheme.typography.labelSmall) }
                    Button(
                        onClick = onApproveAlways,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text(stringResource(R.string.chat_approve_always), style = MaterialTheme.typography.labelSmall) }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = onContainerColor.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_processed),
                        style = MaterialTheme.typography.labelSmall,
                        color = onContainerColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

