package com.lin.hippyagent.core.linux.tools

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber
import java.io.File
import java.io.FileWriter

/**
 * Execute Python 工具：执行 Python 脚本
 *
 * 改进：
 * - 内联脚本写入临时 .py 文件再执行，避免 shell 特殊字符转义问题
 * - 支持多行脚本和复杂代码
 * - 超时从 60s 提升到 120s（复杂脚本需要更长时间）
 */
class ExecutePythonTool(
    private val linuxManager: LinuxManager,
    private val defaultTimeoutMs: Long = 120000
) : Tool() {

    override val definition: ToolDefinition = ToolDefinition(
        name = "execute_python",
        description = "Execute a Python script in the Linux environment. Supports both file paths and inline scripts.",
        parameters = mapOf(
            "script" to ToolParameter(
                name = "script",
                type = "string",
                description = "Python script content or file path (.py). For inline scripts, write full Python code.",
                required = true
            ),
            "args" to ToolParameter(
                name = "args",
                type = "array",
                description = "Command line arguments for the script",
                required = false,
                defaultValue = emptyList<String>()
            ),
            "timeout" to ToolParameter(
                name = "timeout",
                type = "integer",
                description = "Timeout in milliseconds (default: ${defaultTimeoutMs}ms)",
                required = false,
                defaultValue = defaultTimeoutMs
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val script = getRequiredArgument(arguments, "script")
        val args = arguments["args"] as? List<*> ?: emptyList<Any>()
        val argsStr = args.filterIsInstance<String>().joinToString(" ")
        val timeout = getOptionalArgument(arguments, "timeout", defaultTimeoutMs.toString())?.toLongOrNull() ?: defaultTimeoutMs

        // 检查 Linux 环境是否就绪
        if (!linuxManager.isReady.value) {
            return ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                error = "Linux environment not ready. Please wait for initialization to complete.\n" +
                        "Tip: Make sure PRoot/Linux is initialized before using this tool."
            )
        }

        return try {
            // 判断是文件路径还是内联脚本
            // 文件路径判断：以 / 开头，或短路径且以 .py 结尾且不含换行
            val isFilePath = script.startsWith("/") ||
                    (script.endsWith(".py") && !script.contains('\n') && script.length < 256)

            val command = if (isFilePath) {
                "python3 $script $argsStr"
            } else {
                // 内联脚本模式：写入临时文件再执行（避免 shell 转义地狱）
                // createTempScript 返回 PRoot 容器内的路径
                val containerScriptPath = createTempScript(script)
                "python3 $containerScriptPath $argsStr"
            }

            Timber.d("Executing Python script: ${command.take(100)}...")
            val (exitCode, output) = linuxManager.exec(command, timeout)

            if (exitCode == 0) {
                ToolResult(
                    callId = com.lin.hippyagent.core.pool.FastId.next(),
                    success = true,
                    output = output,
                    forLLM = output
                )
            } else {
                ToolResult(
                    callId = com.lin.hippyagent.core.pool.FastId.next(),
                    success = false,
                    output = output,
                    error = "Python execution failed with exit code $exitCode:\n$output"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute Python script")
            ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                error = "Python execution error: ${e.message}"
            )
        }
    }

    /**
     * 将内联脚本写入容器 /tmp 目录下的临时 .py 文件。
     * 这比 python3 -c "..." 更可靠：不受 shell 特殊字符干扰。
     *
     * @return PRoot 容器内的路径（如 /tmp/tmp_script_xxx.py）
     */
    private fun createTempScript(scriptContent: String): String {
        val timestamp = System.currentTimeMillis()
        val scriptFileName = "tmp_script_$timestamp.py"

        val rootfsPath = linuxManager.getRootfsPath()
        val tmpDir = File(rootfsPath, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()

        val scriptFile = File(tmpDir, scriptFileName)
        FileWriter(scriptFile).use { writer ->
            writer.write(scriptContent)
        }
        scriptFile.setReadable(true, false)

        // 返回 PRoot 容器内的路径，而不是 Android 文件系统路径
        val containerPath = "/tmp/$scriptFileName"
        Timber.d("Temporary Python script written to: ${scriptFile.absolutePath} (container: $containerPath)")
        return containerPath
    }
}

