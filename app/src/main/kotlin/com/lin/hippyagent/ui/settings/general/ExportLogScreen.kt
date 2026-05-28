package com.lin.hippyagent.ui.settings.general

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.core.log.LogExporter
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class LogcatViewModel : ViewModel() {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private var logcatProcess: Process? = null
    private val maxLines = 2000

    fun startStreaming() {
        if (_isStreaming.value) return
        _isStreaming.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logcatProcess = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "time", "--pid=${android.os.Process.myPid()}")
                )
                val reader = logcatProcess!!.inputStream.bufferedReader()
                var line = reader.readLine()
                while (line != null) {
                    _lines.update { current ->
                        val next = current + line
                        if (next.size > maxLines) next.drop(next.size - maxLines) else next
                    }
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Timber.e(e, "Logcat streaming failed")
            } finally {
                _isStreaming.value = false
            }
        }
    }

    fun stopStreaming() {
        logcatProcess?.destroy()
        logcatProcess = null
        _isStreaming.value = false
    }

    fun clear() {
        _lines.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        logcatProcess?.destroy()
    }
}

@Composable
fun ExportLogScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val logcatVm: LogcatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val lines by logcatVm.lines.collectAsStateWithLifecycle()
    val isStreaming by logcatVm.isStreaming.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var autoScroll by remember { mutableStateOf(true) }
    var selectedLevel by remember { mutableStateOf<Char?>(null) }
    var onlyHippy by remember { mutableStateOf(false) }

    val filteredLines by remember {
        derivedStateOf {
            lines.filter { line ->
                val level = extractLogLevel(line)
                val levelMatch = selectedLevel == null || level == selectedLevel
                val hippyMatch = !onlyHippy || line.contains("com.lin.hippyagent")
                levelMatch && hippyMatch
            }
        }
    }

    LaunchedEffect(Unit) {
        logcatVm.startStreaming()
    }

    LaunchedEffect(filteredLines.size) {
        if (autoScroll && filteredLines.isNotEmpty()) {
            listState.animateScrollToItem(filteredLines.size - 1)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            logcatVm.stopStreaming()
        }
    }

    Scaffold(topBar = { HippyTopBar(title = "日志", showBackButton = true, onBackClick = onBackClick) }) { padding ->
        Column(modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { exporting = true; scope.launch { status = doExport(ctx); exporting = false } },
                        enabled = !exporting,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (exporting) "导出中..." else "导出", color = MaterialTheme.colorScheme.onPrimary)
                    }
                    if (isStreaming) {
                        OutlinedButton(onClick = { logcatVm.stopStreaming() }) {
                            Text("停止")
                        }
                    } else {
                        OutlinedButton(onClick = { logcatVm.startStreaming() }) {
                            Text("实时日志")
                        }
                    }
                    OutlinedButton(onClick = { logcatVm.clear() }) {
                        Text("清空")
                    }
                }

                status?.let { s ->
                    Spacer(Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(s, Modifier.padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedLevel == null,
                        onClick = { selectedLevel = null },
                        label = { Text("全部", fontSize = 11.sp) }
                    )
                    listOf('V' to "Verbose", 'D' to "Debug", 'I' to "Info", 'W' to "Warn", 'E' to "Error", 'F' to "Fatal").forEach { (level, label) ->
                        FilterChip(
                            selected = selectedLevel == level,
                            onClick = { selectedLevel = if (selectedLevel == level) null else level },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onlyHippy, onCheckedChange = { onlyHippy = it })
                    Text("只显示Hippy", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (filteredLines.isEmpty()) {
                    Text(
                        if (lines.isEmpty()) {
                            if (isStreaming) "等待日志..." else "点击「实时日志」开始查看"
                        } else "无匹配日志",
                        color = Color(0xFF888888),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    SelectionContainer {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(filteredLines, key = { index, _ -> index }) { _, line ->
                                val level = extractLogLevel(line)
                                val textColor = when (level) {
                                    'E', 'F' -> Color(0xFFFF6B6B)
                                    'W' -> Color(0xFFFFCC00)
                                    'I' -> Color(0xFF4EC9B0)
                                    'D' -> Color(0xFFCCCCCC)
                                    else -> Color(0xFF9CDCFE)
                                }
                                Text(
                                    parseLogcatLine(line),
                                    color = textColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                }

                if (filteredLines.isNotEmpty()) {
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedButton(
                            onClick = { autoScroll = !autoScroll },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (autoScroll) Color(0xFF333333) else Color.Transparent,
                                contentColor = Color.White
                            )
                        ) {
                            Text(if (autoScroll) "自动滚动" else "手动滚动", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private val LOG_LEVEL_REGEX = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+([VDIWEF])\s+""")

private fun extractLogLevel(line: String): Char? =
    LOG_LEVEL_REGEX.find(line)?.groupValues?.get(1)?.firstOrNull()

private val LOGCAT_LINE_REGEX = Regex("""^\d{2}-\d{2} (\d{2}:\d{2}:\d{2})\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+(\S+?):?\s*(.*)""")

private fun parseLogcatLine(line: String): String {
    val match = LOGCAT_LINE_REGEX.matchEntire(line)
    return if (match != null) "${match.groupValues[1]} ${match.groupValues[2]}: ${match.groupValues[3]}"
    else line
}

private suspend fun doExport(ctx: android.content.Context) = withContext(Dispatchers.IO) {
    try {
        val exporter = LogExporter(ctx)
        val result = exporter.exportToFile()
        result.fold(
            onSuccess = { file ->
                withContext(Dispatchers.Main) {
                    try {
                        val intent = exporter.shareLogFile(file)
                        ctx.startActivity(Intent.createChooser(intent, "分享日志"))
                        "日志已导出: ${file.name}"
                    } catch (e: Exception) { Timber.e(e, "Share failed"); "日志已保存: ${file.absolutePath}" }
                }
            },
            onFailure = { e -> "导出失败: ${e.message}" }
        )
    } catch (e: Exception) { "导出失败: ${e.message}" }
}
