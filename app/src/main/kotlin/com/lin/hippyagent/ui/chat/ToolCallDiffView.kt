package com.lin.hippyagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

// ========== Diff 视图相关 ==========

/**
 * Diff 行数据类
 */
@Immutable
internal data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val lineNumber: Int = 0
)

internal enum class DiffLineType {
    HEADER,   // --- file 或 +++ file
    HUNK,     // @@ ... @@
    REMOVED,  // - 行
    ADDED,    // + 行
    CONTEXT   // 上下文行
}

/**
 * 解析简易 diff 格式（--- / @@ / - / + 前缀）
 */
internal fun parseDiffOutput(text: String): Pair<String, List<DiffLine>> {
    val lines = text.lines()
    if (lines.isEmpty()) return "" to emptyList()

    var filePath = ""
    val diffLines = mutableListOf<DiffLine>()

    for (line in lines) {
        when {
            line.startsWith("--- ") -> {
                filePath = line.removePrefix("--- ").trim()
                diffLines.add(DiffLine(DiffLineType.HEADER, line))
            }
            line.startsWith("+++ ") -> {
                diffLines.add(DiffLine(DiffLineType.HEADER, line))
            }
            line.startsWith("@@ ") -> {
                diffLines.add(DiffLine(DiffLineType.HUNK, line))
            }
            line.startsWith("- ") -> {
                diffLines.add(DiffLine(DiffLineType.REMOVED, line.removePrefix("- ")))
            }
            line.startsWith("+ ") -> {
                diffLines.add(DiffLine(DiffLineType.ADDED, line.removePrefix("+ ")))
            }
            line.startsWith("  ") -> {
                diffLines.add(DiffLine(DiffLineType.CONTEXT, line.removePrefix("  ")))
            }
        }
    }

    return filePath to diffLines
}

/**
 * 判断文本是否为 diff 格式
 */
internal fun isDiffOutput(text: String): Boolean {
    return text.contains("--- ") && text.contains("@@ ") &&
           (text.contains("- ") || text.contains("+ "))
}

/**
 * Diff 视图渲染 — 带行号和颜色标注的变更展示
 */
@Composable
internal fun DiffView(
    diffText: String,
    modifier: Modifier = Modifier
) {
    val (filePath, diffLines) = remember(diffText) { parseDiffOutput(diffText) }
    val displayPath = shortenWorkspacePaths(filePath)
    if (diffLines.isEmpty()) {
        Text(text = diffText, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    Column(modifier = modifier) {

        Spacer(modifier = Modifier.height(4.dp))

        // Diff 行渲染 — 只显示变化部分（不含 CONTEXT 行）
        diffLines.filter { it.type != DiffLineType.CONTEXT }.forEach { line ->
            when (line.type) {
                DiffLineType.HUNK -> {
                    Text(
                        text = line.content,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                DiffLineType.REMOVED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFCDD2).copy(alpha = 0.4f)) // 红色背景
                            .padding(start = 10.dp, end = 4.dp, top = 1.dp, bottom = 1.dp)
                    ) {
                        Text(
                            text = "-",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.width(10.dp)
                        )
                        Text(
                            text = line.content,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            textDecoration = TextDecoration.LineThrough // 文字划中线
                        )
                    }
                }
                DiffLineType.ADDED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFC8E6C9).copy(alpha = 0.4f)) // 绿色背景
                            .padding(start = 10.dp, end = 4.dp, top = 1.dp, bottom = 1.dp)
                    ) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50), // Green 500
                            modifier = Modifier.width(10.dp)
                        )
                        Text(
                            text = line.content,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF388E3C).copy(alpha = 0.85f) // Green 700
                        )
                    }
                }
                DiffLineType.HEADER -> { /* 已在标题处理 */ }
                DiffLineType.CONTEXT -> { /* 不显示上下文行 */ }
            }
        }
    }
}
