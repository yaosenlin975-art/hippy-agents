package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException as JavaFileNotFoundException
import java.util.zip.ZipInputStream

/**
 * PowerPoint 技能 - 支持 .pptx 文本提取
 * pptx 是 ZIP 包，内含 ppt/slides/slide*.xml
 */
class PptxSkill(private val context: Context) {
    companion object {
        private val slideSeparatorRegex = Regex("""---\s*Slide \d+\s*---""")
        private val slideFileRegex = Regex("""ppt/slides/slide(\d+)\.xml""")
        private val textTagRegex = Regex("""<a:t>([^<]*)</a:t>""")
    }

    fun readPptx(filePath: String): Result<String> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return Result.failure(JavaFileNotFoundException("File not found: $filePath"))
            if (!file.extension.equals("pptx", ignoreCase = true))
                return Result.failure(IllegalArgumentException("Not a .pptx file: $filePath"))

            val text = extractTextFromPptx(file)
            Result.success(text)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read pptx: $filePath")
            Result.failure(e)
        }
    }

    fun getMetadata(filePath: String): Result<Map<String, Any>> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return Result.failure(JavaFileNotFoundException("File not found: $filePath"))
            val text = extractTextFromPptx(file)
            val slideCount = text.split(Regex("""---\s*Slide \d+\s*---""")).size - 1
            Result.success(mapOf(
                "name" to file.name,
                "path" to file.absolutePath,
                "size" to file.length(),
                "slides" to slideCount.coerceAtLeast(1),
                "lastModified" to file.lastModified()
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractTextFromPptx(file: File): String {
        val slides = mutableMapOf<Int, StringBuilder>()

        try {
            file.inputStream().buffered().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val slideMatch = slideFileRegex.find(entry.name)
                        if (slideMatch != null) {
                            val slideNum = slideMatch.groupValues[1].toIntOrNull() ?: 0
                            val xml = zis.readBytes().toString(Charsets.UTF_8)
                            val sb = slides.getOrPut(slideNum) { StringBuilder() }

                            // 提取 <a:t>...</a:t> 中的文本
                            for (match in textTagRegex.findAll(xml)) {
                                val t = match.groupValues[1]
                                if (t.isNotBlank()) {
                                    sb.append(t).append(" ")
                                }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            return "[pptx parse error: ${e.message}]"
        }

        if (slides.isEmpty()) return "[empty presentation]"

        return slides.toSortedMap().entries.joinToString("\n\n") { (num, text) ->
            "--- Slide $num ---\n${text.toString().trim()}"
        }
    }
}

