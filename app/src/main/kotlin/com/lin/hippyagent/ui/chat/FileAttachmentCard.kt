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
internal fun FileAttachmentCard(
    filePath: String,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fileName = filePath.substringAfterLast("/", filePath.substringAfterLast("\\"))
    val ext = filePath.substringAfterLast(".", "").lowercase()
    val fileSize = remember(filePath) {
        val file = File(filePath)
        if (file.exists()) {
            val size = file.length()
            when {
                size >= 1_073_741_824 -> "%.1f GB".format(size / 1_073_741_824.0)
                size >= 1_048_576 -> "%.1f MB".format(size / 1_048_576.0)
                size >= 1024 -> "%.1f KB".format(size / 1024.0)
                else -> "$size B"
            }
        } else null
    }

    androidx.compose.material3.Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isUser)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                imageVector = when (ext) {
                    "doc", "docx", "txt", "md" -> Icons.Outlined.Article
                    else -> Icons.Outlined.AttachFile
                },
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ext.isNotEmpty()) {
                        Text(
                            text = ext.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (fileSize != null) {
                        if (ext.isNotEmpty()) Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = fileSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = {
                    try {
                        val file = File(filePath)
                        if (!file.exists()) {
                            Toast.makeText(context, context.getString(R.string.error_file_not_found), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.chat_cannot_open, e.message), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Article,
                    contentDescription = stringResource(R.string.chat_view),
                    modifier = Modifier.size(18.dp),
                    tint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = {
                    try {
                        val file = File(filePath)
                        if (!file.exists()) {
                            Toast.makeText(context, context.getString(R.string.error_file_not_found), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                        val dest = File(downloadsDir, fileName)
                        file.copyTo(dest, overwrite = true)
                        Toast.makeText(context, context.getString(R.string.chat_saved_to_downloads, fileName), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.chat_save_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Save,
                    contentDescription = stringResource(R.string.chat_save),
                    modifier = Modifier.size(18.dp),
                    tint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = {
                    try {
                        val file = File(filePath)
                        if (!file.exists()) {
                            Toast.makeText(context, context.getString(R.string.error_file_not_found), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext) ?: "*/*"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.chat_share_file)))
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.chat_share_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.chat_share),
                    modifier = Modifier.size(18.dp),
                    tint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

internal fun getFileName(context: Context, uri: Uri): String {
    var fileName = "unknown"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            fileName = cursor.getString(nameIndex)
        }
    }
    return fileName
}

