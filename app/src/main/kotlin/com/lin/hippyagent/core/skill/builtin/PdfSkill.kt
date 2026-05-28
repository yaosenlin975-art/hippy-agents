package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException as JavaFileNotFoundException

/**
 * PDF 阅读技能 - 支持 PDF 文本提取
 */
class PdfSkill(private val context: Context) {
    companion object {
        private val pdfTextRegex = Regex("""BT\s*(.*?)\s*ET""", RegexOption.DOT_MATCHES_ALL)
        private val parenthesizedRegex = Regex("""\((.*?)\)""")
        private val pageTypeRegex = Regex("""/Type\s*/Page[^s]""")
    }

    /**
     * 读取 PDF 文件内容
     */
    fun readPdf(filePath: String): Result<String> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return Result.failure(JavaFileNotFoundException("PDF file not found: $filePath"))
            }

            if (!file.extension.equals("pdf", ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("Not a PDF file: $filePath"))
            }

            // 简单的 PDF 文本提取（实际项目中应使用 PDFBox 或类似库）
            val content = extractTextFromPdf(file)
            Result.success(content)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read PDF: $filePath")
            Result.failure(e)
        }
    }

    /**
     * 获取 PDF 元数据
     */
    fun getPdfMetadata(filePath: String): Result<Map<String, Any>> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return Result.failure(JavaFileNotFoundException("PDF file not found: $filePath"))
            }

            val metadata = mapOf(
                "name" to file.name,
                "path" to file.absolutePath,
                "size" to file.length(),
                "pages" to estimatePageCount(file),
                "lastModified" to file.lastModified()
            )

            Result.success(metadata)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get PDF metadata: $filePath")
            Result.failure(e)
        }
    }

    private fun extractTextFromPdf(file: File): String {
        // 简单的 PDF 文本提取实现
        // 实际项目中应使用 Android PDF Renderer 或第三方库
        return try {
            val bytes = file.readBytes()
            val text = StringBuilder()

            // 查找 PDF 中的文本流
            val content = String(bytes, Charsets.ISO_8859_1)
            val matches = pdfTextRegex.findAll(content)

            for (match in matches) {
                val textBlock = match.groupValues[1]
                val strings = parenthesizedRegex.findAll(textBlock)
                for (s in strings) {
                    text.append(s.groupValues[1])
                }
            }

            if (text.isEmpty()) {
                "[PDF file: ${file.name}, size: ${file.length()} bytes - text extraction not available]"
            } else {
                text.toString()
            }
        } catch (e: Exception) {
            "[PDF file: ${file.name}, size: ${file.length()} bytes - text extraction failed]"
        }
    }

    private fun estimatePageCount(file: File): Int {
        return try {
            val content = String(file.readBytes(), Charsets.ISO_8859_1)
            pageTypeRegex.findAll(content).count().coerceAtLeast(1)
        } catch (e: Exception) {
            1
        }
    }
}

