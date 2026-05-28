package com.lin.hippyagent.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.storage.PreviousDataLocation
import timber.log.Timber
import java.io.File

@Composable
fun PreviousDataDialog(
    locations: List<PreviousDataLocation>,
    onUseData: (PreviousDataLocation, DataMergeMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedLocation by remember { mutableStateOf(locations.firstOrNull()) }
    var selectedMode by remember { mutableStateOf(DataMergeMode.MERGE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("发现历史数据")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "检测到设备上存在之前的 HippyAgent 数据，请选择如何处理：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                locations.forEach { location ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedLocation = location },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedLocation == location)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                location.dataTypeDescription,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "${location.readableSize} · ${location.fileCount} 个文件",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                location.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("处理方式：", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                DataMergeMode.entries.forEach { mode ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedMode = mode },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedMode == mode)
                                MaterialTheme.colorScheme.tertiaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(mode.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(mode.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(mode.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (selectedLocation != null) onUseData(selectedLocation!!, selectedMode)
            }) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("从头开始")
            }
        }
    )
}

enum class DataMergeMode(val label: String, val description: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    KEEP_CURRENT("只保留现有资料", "丢弃历史数据，保留当前工作区内容", Icons.Default.Edit),
    MERGE("合并数据", "将历史数据合并到当前工作区，以较新文件为准", Icons.Default.SwapHoriz),
    KEEP_HISTORY("只保留历史数据", "用历史数据覆盖当前工作区内容", Icons.Default.Folder)
}

internal fun mergeDataDir(source: File, target: File) {
    if (!source.exists() || !source.isDirectory) return
    target.mkdirs()
    source.listFiles()?.forEach { srcFile ->
        val destFile = File(target, srcFile.name)
        if (srcFile.isDirectory) {
            mergeDataDir(srcFile, destFile)
        } else {
            if (!destFile.exists() || srcFile.lastModified() > destFile.lastModified()) {
                try {
                    srcFile.copyTo(destFile, overwrite = true)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to merge ${srcFile.path}")
                }
            }
        }
    }
}
