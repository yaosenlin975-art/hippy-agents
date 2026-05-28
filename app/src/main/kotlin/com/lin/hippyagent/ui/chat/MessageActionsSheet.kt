package com.lin.hippyagent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    isAgent: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onSpeak: (() -> Unit)? = null,
    onQuote: (() -> Unit)? = null,
    onMultiSelect: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            ActionRow(
                icon = Icons.Default.ContentCopy,
                label = "复制",
                onClick = {
                    onCopy()
                    onDismiss()
                }
            )
            if (onQuote != null) {
                ActionRow(
                    icon = Icons.Default.FormatQuote,
                    label = "引用",
                    onClick = {
                        onQuote()
                        onDismiss()
                    }
                )
            }
            if (!isAgent) {
                ActionRow(
                    icon = Icons.Default.Edit,
                    label = "编辑",
                    onClick = {
                        onEdit()
                        onDismiss()
                    }
                )
            }
            if (isAgent) {
                ActionRow(
                    icon = Icons.Default.Refresh,
                    label = "重新生成",
                    onClick = {
                        onRegenerate()
                        onDismiss()
                    }
                )
                // TTS 播放按钮
                if (onSpeak != null) {
                    ActionRow(
                        icon = Icons.Default.VolumeUp,
                        label = "语音播报",
                        onClick = {
                            onSpeak()
                            onDismiss()
                        }
                    )
                }
            }
            if (onMultiSelect != null) {
                ActionRow(
                    icon = Icons.Default.Checklist,
                    label = "多选",
                    onClick = {
                        onMultiSelect()
                        onDismiss()
                    }
                )
            }
            ActionRow(
                icon = Icons.Default.Delete,
                label = "删除",
                onClick = {
                    onDelete()
                    onDismiss()
                },
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = tint)
    }
}

