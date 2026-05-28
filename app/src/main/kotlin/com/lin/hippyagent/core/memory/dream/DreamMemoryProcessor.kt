package com.lin.hippyagent.core.memory.dream

import com.lin.hippyagent.core.memory.DreamPhase
import com.lin.hippyagent.core.memory.MemoryStore
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DreamMemoryProcessor(
    private val workingDir: File,
    private val memoryStore: MemoryStore,
    private val modelClient: ModelClient? = null
) {
    suspend fun dreamMemory(phase: DreamPhase = DreamPhase.DEEP): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.i("Dream memory processing started (phase=$phase)")

            val memoryFile = File(workingDir, "MEMORY.md")
            val memoryDir = File(workingDir, "memory")

            if (!memoryFile.exists() && !memoryDir.exists()) {
                return@runCatching "No memory files to process"
            }

            val backupFile = File(workingDir, "MEMORY.md.bak")
            if (memoryFile.exists()) {
                memoryFile.copyTo(backupFile, overwrite = true)
            }

            val memoryContent = StringBuilder()
            if (memoryFile.exists()) {
                memoryContent.append(memoryFile.readText())
                memoryContent.append("\n\n")
            }

            if (memoryDir.exists()) {
                val dailyFiles = memoryDir.listFiles()
                    ?.filter { it.name.endsWith(".md") }
                    ?.sortedByDescending { it.name }
                    ?: emptyList()

                for (file in dailyFiles.take(7)) {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                        memoryContent.append("## ${file.nameWithoutExtension}\n\n")
                        memoryContent.append(content)
                        memoryContent.append("\n\n")
                    }
                }
            }

            var result = memoryContent.toString().trim()

            if (modelClient != null && result.length > 500) {
                result = optimizeWithLLM(modelClient, result)
            }

            if (result.isNotBlank()) {
                memoryFile.writeText(result)
                Timber.i("Dream memory processing completed: ${result.length} chars")
            }

            "Dream completed: processed ${result.length} characters"
        }.onFailure {
            Timber.e(it, "Dream memory processing failed")
        }
    }

    private suspend fun optimizeWithLLM(client: ModelClient, content: String): String {
        return try {
            val messages = listOf(
                ModelMessage(
                    role = "system",
                    content = "You are a memory optimizer. Consolidate and deduplicate the following memory entries. Remove redundant information, merge similar topics, and keep only the most important and recent information. Return the optimized memory content."
                ),
                ModelMessage(
                    role = "user",
                    content = "Optimize this memory:\n\n$content"
                )
            )

            val response = client.chatCompletion(
                ModelCallRequest(
                    model = "default",
                    messages = messages,
                    temperature = 0.3f,
                    maxTokens = 2048
                )
            )

            response.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() } ?: content
        } catch (e: Exception) {
            Timber.w(e, "LLM optimization failed, using original content")
            content
        }
    }

    suspend fun triggerDream(): Result<String> = dreamMemory()
}

