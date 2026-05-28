package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException as JavaFileNotFoundException
import java.util.zip.ZipInputStream

/**
 * Word 文档技能 - 支持 .docx 文本提取和基本操作
 * docx 是 ZIP 包，内含 word/document.xml
 */
class DocxSkill(private val context: Context) {
    companion object {
        private val wordTextRegex = Regex("""<w:t[^>]*>([^<]*)</w:t>""")
    }

    fun readDocx(filePath: String): Result<String> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return Result.failure(JavaFileNotFoundException("File not found: $filePath"))
            if (!file.extension.equals("docx", ignoreCase = true))
                return Result.failure(IllegalArgumentException("Not a .docx file: $filePath"))

            val text = extractTextFromDocx(file)
            Result.success(text)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read docx: $filePath")
            Result.failure(e)
        }
    }

    fun getMetadata(filePath: String): Result<Map<String, Any>> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return Result.failure(JavaFileNotFoundException("File not found: $filePath"))
            val text = extractTextFromDocx(file)
            val paragraphCount = text.split("\n\n").size
            Result.success(mapOf(
                "name" to file.name,
                "path" to file.absolutePath,
                "size" to file.length(),
                "paragraphs" to paragraphCount,
                "lastModified" to file.lastModified()
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractTextFromDocx(file: File): String {
        val sb = StringBuilder()
        try {
            file.inputStream().buffered().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            val xml = zis.readBytes().toString(Charsets.UTF_8)
                            // 从 XML 中提取文本：匹配 <w:t>...</w:t> 和 <w:t xml:space="preserve">...</w:t>
                            for (match in wordTextRegex.findAll(xml)) {
                                val t = match.groupValues[1]
                                if (t.isNotBlank()) {
                                    sb.append(t)
                                }
                            }
                            break
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            return "[docx parse error: ${e.message}]"
        }

        // 将连续文本按段落分割（word/document.xml 中段落由 <w:p> 分隔）
        return if (sb.isEmpty()) "[empty document]" else formatParagraphs(sb.toString())
    }

    private fun formatParagraphs(raw: String): String {
        // 简单分段：每 80 字符左右换行
        return raw.chunked(80).joinToString("\n")
    }
}

