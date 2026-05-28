package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException as JavaFileNotFoundException
import java.util.zip.ZipInputStream

/**
 * Excel 表格技能 - 支持 .xlsx 文本提取
 * xlsx 是 ZIP 包，内含 xl/sharedStrings.xml 和 xl/worksheets/sheet*.xml
 */
class XlsxSkill(private val context: Context) {
    companion object {
        private val cellTextRegex = Regex("""<t[^>]*>([^<]*)</t>""")
        private val sheetFileRegex = Regex("""xl/worksheets/sheet\d+\.xml""")
        private val rowRegex = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
        private val cellRegex = Regex("""<c[^>]*t="([^"]*)"[^>]*>(?:<v>([^<]*)</v>)?</c>""")
        private val sharedCellRegex = Regex("""<c[^>]*>(?:<is><t>([^<]*)</t></is>)?</c>""")
    }

    fun readXlsx(filePath: String): Result<String> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return Result.failure(JavaFileNotFoundException("File not found: $filePath"))
            if (!file.extension.equals("xlsx", ignoreCase = true))
                return Result.failure(IllegalArgumentException("Not a .xlsx file: $filePath"))

            val text = extractTextFromXlsx(file)
            Result.success(text)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read xlsx: $filePath")
            Result.failure(e)
        }
    }

    fun getMetadata(filePath: String): Result<Map<String, Any>> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return Result.failure(JavaFileNotFoundException("File not found: $filePath"))
            val text = extractTextFromXlsx(file)
            val lines = text.lines().size
            Result.success(mapOf(
                "name" to file.name,
                "path" to file.absolutePath,
                "size" to file.length(),
                "rows" to lines,
                "lastModified" to file.lastModified()
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractTextFromXlsx(file: File): String {
        val sharedStrings = mutableListOf<String>()
        val sheetData = StringBuilder()

        try {
            file.inputStream().buffered().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "xl/sharedStrings.xml" -> {
                                val xml = zis.readBytes().toString(Charsets.UTF_8)
                                for (match in cellTextRegex.findAll(xml)) {
                                    sharedStrings.add(match.groupValues[1])
                                }
                            }
                            entry.name.matches(sheetFileRegex) -> {
                                val xml = zis.readBytes().toString(Charsets.UTF_8)
                                parseSheetXml(xml, sharedStrings, sheetData)
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            return "[xlsx parse error: ${e.message}]"
        }

        return if (sheetData.isEmpty()) "[empty spreadsheet]" else sheetData.toString().trimEnd()
    }

    private fun parseSheetXml(xml: String, sharedStrings: List<String>, sb: StringBuilder) {
        // 解析每行 <row>...</row>
        for (rowMatch in rowRegex.findAll(xml)) {
            val rowXml = rowMatch.groupValues[1]
            val cells = mutableListOf<String>()

            for (cellMatch in cellRegex.findAll(rowXml)) {
                val type = cellMatch.groupValues[1]
                val value = cellMatch.groupValues[2]
                val display = when (type) {
                    "s" -> value.toIntOrNull()?.let { sharedStrings.getOrElse(it) { "?" } } ?: "?"
                    "b" -> if (value == "1") "TRUE" else "FALSE"
                    else -> value
                }
                cells.add(display)
            }

            // 也尝试内联字符串
            if (cells.isEmpty()) {
                for (m in sharedCellRegex.findAll(rowXml)) {
                    cells.add(m.groupValues[1])
                }
            }

            if (cells.isNotEmpty()) {
                sb.appendLine(cells.joinToString("\t"))
            }
        }
    }
}

