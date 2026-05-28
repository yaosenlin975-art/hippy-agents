package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import com.lin.hippyagent.core.linux.LinuxManager
import timber.log.Timber
import java.io.File

/**
 * Executes skill scripts through Linux environment.
 * Skills are installed as .py scripts in hippydata/skills/{skillId}/scripts/
 * This executor runs them via LinuxManager (PRoot container).
 */
class SkillScriptExecutor(
    private val context: Context,
    private val linuxManager: LinuxManager
) {
    private val skillsDir: File get() = File(context.filesDir, "skills")

    /**
     * Execute a skill's script with given arguments.
     * @param skillId The skill identifier (e.g., "docx", "pdf", "news")
     * @param scriptName The script filename (e.g., "docx_handler.py")
     * @param args Arguments to pass to the script
     * @return Result with script output
     */
    suspend fun executeScript(
        skillId: String,
        scriptName: String,
        args: List<String> = emptyList()
    ): Result<String> {
        val scriptFile = skillsDir.resolve(skillId).resolve("scripts").resolve(scriptName)
        if (!scriptFile.exists()) {
            return Result.failure(IllegalStateException("Script not found: ${scriptFile.absolutePath}"))
        }

        if (!linuxManager.isReady.value) {
            return Result.failure(
                IllegalStateException(
                    "Linux environment not ready. " +
                    "Please wait for initialization to complete."
                )
            )
        }

        val command = buildString {
            append("python3 ${scriptFile.absolutePath}")
            if (args.isNotEmpty()) {
                append(" ")
                append(args.joinToString(" ") { "\"${it.replace("\"", "\\\"")}\"" })
            }
        }

        Timber.d("Executing skill script: $command")
        val (exitCode, output) = linuxManager.exec(command)
        return if (exitCode == 0) {
            Result.success(output)
        } else {
            Result.failure(RuntimeException("Script execution failed: $output"))
        }
    }

    /**
     * Execute a skill's script and return parsed output.
     * Script should output JSON or structured text.
     */
    suspend fun executeAndParse(
        skillId: String,
        scriptName: String,
        args: List<String> = emptyList()
    ): Result<Map<String, Any>> {
        val output = executeScript(skillId, scriptName, args)
        return output.fold(
            onSuccess = { text ->
                Result.success(parseScriptOutput(text))
            },
            onFailure = { e ->
                Result.failure(e)
            }
        )
    }

    /**
     * List available scripts for a skill.
     */
    fun listScripts(skillId: String): List<String> {
        val scriptsDir = skillsDir.resolve(skillId).resolve("scripts")
        if (!scriptsDir.exists()) return emptyList()
        return scriptsDir.listFiles()?.map { it.name } ?: emptyList()
    }

    /**
     * Check if a skill has executable scripts.
     */
    fun hasScripts(skillId: String): Boolean {
        return listScripts(skillId).isNotEmpty()
    }

    private fun parseScriptOutput(output: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val lines = output.lines().filter { it.isNotBlank() }

        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0 && colonIndex < line.length - 1) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                result[key] = value
            }
        }

        if (result.isEmpty()) {
            result["output"] = output
        }

        return result
    }
}


