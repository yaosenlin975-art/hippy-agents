package com.lin.hippyagent.core.tools.builtin

import android.content.Context
import com.lin.hippyagent.core.tools.FileLockManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import java.io.File

/** 文件路径重定向：文件不存在时重定向到智能体工作区（仅相对路径） */
private fun resolveFilePath(originalPath: String, ctx: ToolContext): String {
    val file = File(originalPath)
    if (file.exists()) return originalPath
    val workspace = ctx.workspace ?: return originalPath
    if (file.isAbsolute) return originalPath
    val inWorkspace = File(workspace, originalPath.removePrefix("./"))
    return inWorkspace.absolutePath
}

class ReadFileTool(private val fileLockManager: FileLockManager? = null) : Tool() {
    override val definition = ToolDefinition(
        name = "read_file",
        description = "读取文件内容，可通过 start_line/end_line 控制阅读范围（行号从 1 开始）",
        parameters = mapOf(
            "file_path" to ToolParameter(
                name = "file_path",
                type = "string",
                description = "文件绝对路径，如 /storage/emulated/0/Download/file.txt",
                required = true
            ),
            "start_line" to ToolParameter(
                name = "start_line",
                type = "integer",
                description = "起始行号（可选，从 1 开始）",
                required = false
            ),
            "end_line" to ToolParameter(
                name = "end_line",
                type = "integer",
                description = "终止行号（可选，包含该行）",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val filePath = getRequiredArgument(arguments, "file_path")
        val callId = arguments["callId"] as? String ?: ""
        val startLine = (arguments["start_line"] as? Number)?.toInt()
        val endLine = (arguments["end_line"] as? Number)?.toInt()

        val readFn: (String, String) -> ToolResult = { path, id ->
            doRead(path, id, startLine, endLine)
        }

        if (fileLockManager != null) {
            return fileLockManager.withReadLock(filePath) {
                readFn(filePath, callId)
            } ?: readFn(filePath, callId)
        }
        return readFn(filePath, callId)
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val originalPath = getRequiredArgument(args, "file_path")
        val resolved = resolveFilePath(originalPath, ctx)
        val newArgs = args.toMutableMap().also { it["file_path"] = resolved }
        return execute(newArgs)
    }


    private fun doRead(path: String, callId: String, startLine: Int? = null, endLine: Int? = null): ToolResult {
        return try {
            val file = File(path)
            if (!file.exists()) {
                ToolResult(callId, false, error = "File not found: $path")
            } else if (!file.canRead()) {
                ToolResult(callId, false, error = "Cannot read file: $path")
            } else {
                val allText = file.readText()
                if (startLine == null && endLine == null) {
                    ToolResult(callId, true, allText)
                } else {
                    val lines = allText.lines()
                    val start = (startLine ?: 1).coerceAtLeast(1)
                    val end = (endLine ?: lines.size).coerceAtMost(lines.size)
                    val selected = lines.subList(start - 1, end)
                    val result = buildString {
                        selected.forEachIndexed { idx, line ->
                            appendLine("${start + idx}:$line")
                        }
                    }
                    ToolResult(callId, true, result)
                }
            }
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to read file: ${e.message}")
        }
    }
}

class WriteFileTool(private val context: Context, private val fileLockManager: FileLockManager? = null) : Tool() {
    override val definition = ToolDefinition(
        name = "write_file",
        description = "写入文件内容到指定路径，会覆盖文件原有内容。如需修改文件部分内容请使用 edit_file。file_path 必须为绝对路径（支持 /tmp 前缀映射到应用缓存目录、/sdcard 前缀映射到内部存储）。示例: write_file(file_path='/tmp/test.txt', content='Hello World')",
        parameters = mapOf(
            "file_path" to ToolParameter(
                name = "file_path",
                type = "string",
                description = "文件绝对路径，如 /storage/emulated/0/Download/file.txt。支持 /tmp 前缀自动映射到应用缓存目录",
                required = true
            ),
            "content" to ToolParameter(
                name = "content",
                type = "string",
                description = "要写入的文件内容",
                required = true
            )
        )
    )

    private fun resolvePath(path: String): String {
        return if (path.startsWith("/tmp")) {
            context.cacheDir.absolutePath + path.removePrefix("/tmp")
        } else if (path.startsWith("/sdcard")) {
            "/storage/emulated/0" + path.removePrefix("/sdcard")
        } else {
            path
        }
    }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val rawPath = getRequiredArgument(arguments, "file_path")
        val content = getRequiredArgument(arguments, "content")
        val callId = arguments["callId"] as? String ?: ""
        val path = resolvePath(rawPath)

        if (fileLockManager != null) {
            return fileLockManager.withWriteLock(path) {
                doWrite(path, content, callId)
            } ?: ToolResult(callId, false, error = "File lock timeout for $path")
        }
        return doWrite(path, content, callId)
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val originalPath = getRequiredArgument(args, "file_path")
        val resolved = resolveFilePath(originalPath, ctx)
        val newArgs = args.toMutableMap().also { it["file_path"] = resolved }
        return execute(newArgs)
    }


    private fun doWrite(path: String, content: String, callId: String): ToolResult {
        return try {
            val file = File(path)
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs()
            }

            val output = if (file.exists()) {
                val oldContent = file.readText()
                val newLineCount = content.lines().size
                EditFileTool.buildDiffOutput(path, 1, oldContent.lines().size, newLineCount, oldContent, content)
            } else {
                val newLineCount = content.lines().size
                buildString {
                    appendLine("--- $path")
                    appendLine("@@ -0,0 +1,$newLineCount @@")
                    for (line in content.lines()) {
                        appendLine("+ $line")
                    }
                }.trimEnd()
            }

            val tempFile = File("$path.tmp")
            tempFile.writeText(content)
            tempFile.renameTo(file)
            ToolResult(callId, true, output = output)
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to write file: ${e.message}")
        }
    }
}

class EditFileTool(private val fileLockManager: FileLockManager? = null) : Tool() {
    override val definition = ToolDefinition(
        name = "edit_file",
        description = "精确查找并替换文件中的指定文本。必须先用read_file读取文件内容，然后将需要替换的原文精确复制到old_text参数中。old_text必须是文件中的原文，不能省略、不能凭记忆编写、不能为空。如果要重写整个文件，请使用write_file。file_path 必须为绝对路径。示例: edit_file(file_path='/tmp/test.txt', old_text='Hello', new_text='Hi')",
        parameters = mapOf(
            "file_path" to ToolParameter(
                name = "file_path",
                type = "string",
                description = "文件绝对路径，如 /storage/emulated/0/Download/file.txt",
                required = true
            ),
            "old_text" to ToolParameter(
                name = "old_text",
                type = "string",
                description = "要被替换的旧文本，必须是从read_file结果中精确复制的原文，不能省略或为空",
                required = true
            ),
            "new_text" to ToolParameter(
                name = "new_text",
                type = "string",
                description = "替换后的新文本",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val mapped = arguments.toMutableMap()
        if (!mapped.containsKey("old_text") && mapped.containsKey("old_string")) mapped["old_text"] = mapped["old_string"]!!
        if (!mapped.containsKey("new_text") && mapped.containsKey("new_string")) mapped["new_text"] = mapped["new_string"]!!
        val filePath = getRequiredArgument(mapped, "file_path")
        val oldText = getRequiredArgument(mapped, "old_text")
        val newText = getRequiredArgument(mapped, "new_text")
        val callId = arguments["callId"] as? String ?: ""

        if (fileLockManager != null) {
            return fileLockManager.withWriteLock(filePath) {
                doEdit(filePath, oldText, newText, callId)
            } ?: ToolResult(callId, false, error = "File lock timeout for $filePath")
        }
        return doEdit(filePath, oldText, newText, callId)
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val originalPath = getRequiredArgument(args, "file_path")
        val resolved = resolveFilePath(originalPath, ctx)
        val newArgs = args.toMutableMap().also { it["file_path"] = resolved }
        return execute(newArgs)
    }


    private fun doEdit(filePath: String, oldText: String, newText: String, callId: String): ToolResult {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return ToolResult(callId, false, error = "File not found: $filePath")
            }
            val content = file.readText()
            val normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n")
            val normalizedOldText = oldText.replace("\r\n", "\n").replace("\r", "\n")
            if (normalizedOldText !in normalizedContent) {
                val preview = content.take(500)
                return ToolResult(callId, false, error = "Text not found in file. Please read the file first with read_file, then copy the exact text to old_text. File preview: $preview")
            }
            val oldTextStart = normalizedContent.indexOf(normalizedOldText)
            val lineBefore = normalizedContent.substring(0, oldTextStart).count { it == '\n' }
            val oldLineCount = normalizedOldText.count { it == '\n' } + 1
            val newLineCount = newText.count { it == '\n' } + 1

            val newContent = if (oldText in content) {
                content.replaceFirst(oldText, newText)
            } else {
                normalizedContent.replaceFirst(normalizedOldText, newText)
            }
            val tempFile = File("$filePath.tmp")
            tempFile.writeText(newContent)
            tempFile.renameTo(file)

            val diffOutput = buildDiffOutput(filePath, lineBefore + 1, oldLineCount, newLineCount, normalizedOldText, newText)
            ToolResult(callId, true, output = diffOutput)
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to edit file: ${e.message}")
        }
    }

    companion object {
        /**
         * 构建可解析的 diff 输出格式：
         * --- file_path
         * @@ start,count +start,count @@
         * -删除的行
         * +新增的行
         *  上下文行
         */
        fun buildDiffOutput(
            filePath: String,
            startLine: Int,
            oldLineCount: Int,
            newLineCount: Int,
            oldText: String,
            newText: String
        ): String {
            val sb = StringBuilder()
            sb.appendLine("--- $filePath")
            sb.appendLine("@@ -$startLine,$oldLineCount +$startLine,$newLineCount @@")

            // 上下文：取 old_text 前后各1行
            val oldLines = oldText.lines()
            val newLines = newText.lines()

            for (line in oldLines) {
                sb.appendLine("- $line")
            }
            for (line in newLines) {
                sb.appendLine("+ $line")
            }
            return sb.toString().trimEnd()
        }
    }
}

class AppendFileTool(private val context: Context, private val fileLockManager: FileLockManager? = null) : Tool() {
    override val definition = ToolDefinition(
        name = "append_file",
        description = "在文件末尾追加内容。file_path 必须为绝对路径（支持 /tmp 前缀映射到应用缓存目录、/sdcard 前缀映射到内部存储）。示例: append_file(file_path='/tmp/log.txt', content='new line')",
        parameters = mapOf(
            "file_path" to ToolParameter(
                name = "file_path",
                type = "string",
                description = "文件绝对路径，如 /storage/emulated/0/Download/file.txt。支持 /tmp 前缀自动映射到应用缓存目录",
                required = true
            ),
            "content" to ToolParameter(
                name = "content",
                type = "string",
                description = "要追加的内容",
                required = true
            )
        )
    )

    private fun resolvePath(path: String): String {
        return if (path.startsWith("/tmp")) {
            context.cacheDir.absolutePath + path.removePrefix("/tmp")
        } else if (path.startsWith("/sdcard")) {
            "/storage/emulated/0" + path.removePrefix("/sdcard")
        } else {
            path
        }
    }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val rawFilePath = getRequiredArgument(arguments, "file_path")
        val content = getRequiredArgument(arguments, "content")
        val callId = arguments["callId"] as? String ?: ""
        val filePath = resolvePath(rawFilePath)

        if (fileLockManager != null) {
            return fileLockManager.withWriteLock(filePath) {
                doAppend(filePath, content, callId)
            } ?: ToolResult(callId, false, error = "File lock timeout for $filePath")
        }
        return doAppend(filePath, content, callId)
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val originalPath = getRequiredArgument(args, "file_path")
        val resolved = resolveFilePath(originalPath, ctx)
        val newArgs = args.toMutableMap().also { it["file_path"] = resolved }
        return execute(newArgs)
    }


    private fun doAppend(filePath: String, content: String, callId: String): ToolResult {
        return try {
            val file = File(filePath)
            file.appendText(content)
            ToolResult(callId, true, output = content.trimEnd())
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to append file: ${e.message}")
        }
    }
}

class DeleteFileTool(private val fileLockManager: FileLockManager? = null) : Tool() {
    override val definition = ToolDefinition(
        name = "delete_file",
        description = "删除指定文件（不支持删除目录）",
        parameters = mapOf(
            "file_path" to ToolParameter(
                name = "file_path",
                type = "string",
                description = "要删除的文件绝对路径，如 /storage/emulated/0/Download/file.txt",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val filePath = getRequiredArgument(arguments, "file_path")
        val callId = arguments["callId"] as? String ?: ""

        if (fileLockManager != null) {
            return fileLockManager.withWriteLock(filePath) {
                doDelete(filePath, callId)
            } ?: ToolResult(callId, false, error = "File lock timeout for $filePath")
        }
        return doDelete(filePath, callId)
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val originalPath = getRequiredArgument(args, "file_path")
        val resolved = resolveFilePath(originalPath, ctx)
        val newArgs = args.toMutableMap().also { it["file_path"] = resolved }
        return execute(newArgs)
    }

    private fun doDelete(path: String, callId: String): ToolResult {
        return try {
            val file = File(path)
            if (!file.exists()) {
                return ToolResult(callId, false, error = "File not found: $path")
            }
            if (file.isDirectory) {
                return ToolResult(callId, false, error = "Cannot delete directory with delete_file. Use execute_bash with rm -r instead.")
            }
            if (!file.canWrite()) {
                return ToolResult(callId, false, error = "Cannot write/delete file: $path")
            }
            val deleted = file.delete()
            if (deleted) {
                ToolResult(callId, true, output = "Deleted $path")
            } else {
                ToolResult(callId, false, error = "Failed to delete $path (unknown reason)")
            }
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to delete file: ${e.message}")
        }
    }
}

class GlobSearchTool : Tool() {
    override val definition = ToolDefinition(
        name = "glob_search",
        description = "按文件名模式搜索文件（支持 ** 递归匹配）",
        parameters = mapOf(
            "pattern" to ToolParameter(
                name = "pattern",
                type = "string",
                description = "glob 模式，如 *.py, **/*.json, src/**/*.kt",
                required = true
            ),
            "search_path" to ToolParameter(
                name = "search_path",
                type = "string",
                description = "搜索根目录的绝对路径，如 /storage/emulated/0/Download。默认当前目录",
                required = false,
                defaultValue = "."
            )
        )
    )

    private val skipDirs = setOf(
        ".git", ".svn", ".hg", "node_modules", "__pycache__",
        ".tox", ".nox", ".mypy_cache", ".pytest_cache", ".ruff_cache",
        ".venv", "venv", ".eggs", "dist", "build", ".next", ".nuxt"
    )
    private val maxMatches = 200

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val pattern = getRequiredArgument(arguments, "pattern")
        val searchPath = getOptionalArgument(arguments, "search_path", ".")!!
        val callId = arguments["callId"] as? String ?: ""

        return try {
            val dir = File(searchPath)
            if (!dir.exists() || !dir.isDirectory) {
                return ToolResult(callId, false, error = "无效目录: $searchPath")
            }

            val results = mutableListOf<String>()
            var truncated = false

            // 使用 java.nio.file.FileSystems 做 glob 匹配
            val rootPath = dir.toPath()
            val isRecursive = pattern.startsWith("**/")
            val globPattern = if (isRecursive) {
                "glob:**/${pattern.removePrefix("**/")}"
            } else {
                "glob:$pattern"
            }

            val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher(globPattern)
            val fileNameRegex = if (isRecursive) {
                pattern.removePrefix("**/").replace(".", "\\.").replace("*", ".*").replace("?", ".").toRegex()
            } else {
                pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".").toRegex()
            }

            fun walk(path: java.nio.file.Path) {
                if (results.size >= maxMatches) { truncated = true; return }
                try {
                    val stream = java.nio.file.Files.list(path)
                    stream.use { s ->
                        s.sorted(java.util.Comparator.comparing { it.fileName.toString() }).forEach { entry ->
                            if (results.size >= maxMatches) { truncated = true; return@forEach }
                            val fileName = entry.fileName.toString()
                            if (java.nio.file.Files.isDirectory(entry) && fileName in skipDirs) return@forEach
                            if (java.nio.file.Files.isDirectory(entry) && isRecursive) {
                                walk(entry)
                            } else if (!java.nio.file.Files.isDirectory(entry)) {
                                val relPath = rootPath.relativize(entry).toString().replace("\\", "/")
                                val matched = if (isRecursive) {
                                    matcher.matches(entry) || fileName.matches(fileNameRegex)
                                } else {
                                    matcher.matches(entry.fileName) || fileName.matches(fileNameRegex)
                                }
                                if (matched) {
                                    results.add(relPath)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            walk(rootPath)
            results.sort()

            if (results.isEmpty()) {
                ToolResult(callId, true, "未找到匹配文件: $pattern")
            } else {
                val text = results.joinToString("\n") + if (truncated) "\n\n(结果已截断，最多显示 $maxMatches 条)" else ""
                ToolResult(callId, true, text)
            }
        } catch (e: Exception) {
            ToolResult(callId, false, error = "搜索失败: ${e.message}")
        }
    }

}

class GrepSearchTool : Tool() {
    override val definition = ToolDefinition(
        name = "grep_search",
        description = "递归搜索文件内容（支持正则、上下文行数、文件名过滤）",
        parameters = mapOf(
            "pattern" to ToolParameter(
                name = "pattern",
                type = "string",
                description = "搜索模式（默认字面量，is_regex=true 时为正则）",
                required = true
            ),
            "path" to ToolParameter(
                name = "path",
                type = "string",
                description = "搜索的文件或目录路径",
                required = false,
                defaultValue = "."
            ),
            "is_regex" to ToolParameter(
                name = "is_regex",
                type = "boolean",
                description = "是否将 pattern 视为正则表达式",
                required = false,
                defaultValue = "false"
            ),
            "case_sensitive" to ToolParameter(
                name = "case_sensitive",
                type = "boolean",
                description = "是否区分大小写",
                required = false,
                defaultValue = "true"
            ),
            "context_lines" to ToolParameter(
                name = "context_lines",
                type = "integer",
                description = "匹配行前后显示的上下文行数（类似 grep -C），最多 5",
                required = false,
                defaultValue = "0"
            ),
            "include_pattern" to ToolParameter(
                name = "include_pattern",
                type = "string",
                description = "仅搜索文件名匹配此 glob 的文件（如 *.py）",
                required = false
            )
        )
    )

    private val skipDirs = setOf(
        ".git", ".svn", ".hg", "node_modules", "__pycache__",
        ".tox", ".nox", ".mypy_cache", ".pytest_cache", ".ruff_cache",
        ".venv", "venv", ".eggs", "dist", "build", ".next", ".nuxt"
    )
    private val binaryExtensions = setOf(
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".svg",
        ".mp3", ".mp4", ".avi", ".mov", ".mkv", ".flac", ".wav",
        ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar", ".pdf",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".exe", ".dll", ".so", ".dylib", ".bin", ".dat",
        ".woff", ".woff2", ".ttf", ".eot", ".otf",
        ".pyc", ".pyo", ".class", ".o", ".a"
    )
    private val maxMatches = 200
    private val maxOutputChars = 50_000
    private val maxContextLines = 5
    private val maxFilesScanned = 10_000

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val pattern = getRequiredArgument(arguments, "pattern")
        val searchPath = getOptionalArgument(arguments, "path", ".")!!
        val isRegex = getOptionalArgument(arguments, "is_regex", "false")!!.toBoolean()
        val caseSensitive = getOptionalArgument(arguments, "case_sensitive", "true")!!.toBoolean()
        val contextLines = getOptionalArgument(arguments, "context_lines", "0")!!.toInt().coerceIn(0, maxContextLines)
        val includePattern = getOptionalArgument(arguments, "include_pattern")
        val callId = arguments["callId"] as? String ?: ""

        return try {
            val searchFile = File(searchPath)
            if (!searchFile.exists()) {
                return ToolResult(callId, false, error = "路径不存在: $searchPath")
            }

            val regex = if (isRegex) {
                val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                pattern.toRegex(opts)
            } else {
                val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex.escape(pattern).toRegex(opts)
            }

            val includeRegex = includePattern?.let {
                val globRegex = it.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                globRegex.toRegex()
            }

            val matches = mutableListOf<String>()
            var totalChars = 0
            var filesScanned = 0
            var truncated = false

            fun isTextFile(file: File): Boolean {
                if (file.extension.lowercase() in binaryExtensions) return false
                if (file.length() > 2 * 1024 * 1024) return false
                return true
            }

            fun grepFile(file: File) {
                if (truncated || filesScanned >= maxFilesScanned) return
                filesScanned++
                if (!isTextFile(file)) return
                if (includeRegex != null && !includeRegex.matches(file.name)) return

                val displayPath = if (searchFile.isFile) file.name else {
                    try { file.relativeTo(searchFile).path.replace("\\", "/") } catch (_: Exception) { file.name }
                }

                try {
                    val lines = file.readLines()
                    for ((idx, line) in lines.withIndex()) {
                        if (truncated) return
                        if (!regex.containsMatchIn(line)) continue
                        val lineNo = idx + 1

                        // 输出上下文行
                        val start = (idx - contextLines).coerceAtLeast(0)
                        val end = (idx + contextLines + 1).coerceAtMost(lines.size)
                        for (i in start until end) {
                            if (matches.size >= maxMatches) { truncated = true; return }
                            val ln = i + 1
                            val prefix = if (ln == lineNo) ">" else " "
                            val entry = "$displayPath:$ln:$prefix ${lines[i]}"
                            totalChars += entry.length + 1
                            if (totalChars > maxOutputChars) { truncated = true; return }
                            matches.add(entry)
                        }
                        if (contextLines > 0) {
                            if (matches.size >= maxMatches) { truncated = true; return }
                            totalChars += 4
                            if (totalChars > maxOutputChars) { truncated = true; return }
                            matches.add("---")
                        }
                    }
                } catch (_: Exception) {}
            }

            if (searchFile.isFile) {
                grepFile(searchFile)
            } else {
                searchFile.walkTopDown()
                    .onEnter { it.name !in skipDirs }
                    .filter { it.isFile }
                    .forEach { grepFile(it) }
            }

            when {
                matches.isEmpty() -> ToolResult(callId, true, "未找到匹配: $pattern")
                truncated -> ToolResult(callId, true, matches.joinToString("\n") + "\n\n(结果已截断，最多 $maxMatches 条匹配，${maxOutputChars / 1000}KB 输出)")
                else -> ToolResult(callId, true, matches.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult(callId, false, error = "搜索失败: ${e.message}")
        }
    }

}

class ListDirectoryTool : Tool() {
    override val definition = ToolDefinition(
        name = "list_directory",
        description = "列出目录内容",
        parameters = mapOf(
            "path" to ToolParameter(
                name = "path",
                type = "string",
                description = "目录路径",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val path = getRequiredArgument(arguments, "path")
        val callId = arguments["callId"] as? String ?: ""

        return try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                return ToolResult(callId, false, error = "Invalid directory: $path")
            }
            val entries = dir.listFiles()?.map {
                val type = if (it.isDirectory) "📁" else "📄"
                val size = if (it.isFile) " (${it.length()} bytes)" else ""
                "$type ${it.name}$size"
            } ?: emptyList()
            ToolResult(callId, true, entries.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to list directory: ${e.message}")
        }
    }

}

class GetFileSizeTool : Tool() {
    override val definition = ToolDefinition(
        name = "get_file_size",
        description = "获取文件大小（字节数）",
        parameters = mapOf(
            "file_path" to ToolParameter(
                name = "file_path",
                type = "string",
                description = "文件绝对路径",
                required = true
            )
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val path = getRequiredArgument(arguments, "file_path")
        val callId = arguments["callId"] as? String ?: ""
        return try {
            val file = File(path)
            if (!file.exists()) return ToolResult(callId, false, error = "File not found: $path")
            if (!file.isFile) return ToolResult(callId, false, error = "Not a file: $path")
            ToolResult(callId, true, file.length().toString())
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed: ${e.message}")
        }
    }

}

class GetTextLinesCountTool : Tool() {
    override val definition = ToolDefinition(
        name = "get_text_lines_count",
        description = "获取文本文件的行数（仅对文本类型文件生效）",
        parameters = mapOf(
            "file_path" to ToolParameter(
                name = "file_path",
                type = "string",
                description = "文件绝对路径",
                required = true
            )
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val path = getRequiredArgument(arguments, "file_path")
        val callId = arguments["callId"] as? String ?: ""
        return try {
            val file = File(path)
            if (!file.exists()) return ToolResult(callId, false, error = "File not found: $path")
            if (!file.isFile) return ToolResult(callId, false, error = "Not a file: $path")
            val text = file.readText()
            val lines = text.lines().size
            ToolResult(callId, true, lines.toString())
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed: ${e.message}")
        }
    }

}

