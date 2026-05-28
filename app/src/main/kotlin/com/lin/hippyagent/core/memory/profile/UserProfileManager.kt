package com.lin.hippyagent.core.memory.profile

import com.lin.hippyagent.core.memory.commonmemory.BrainMemoryType
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import timber.log.Timber
import java.io.File
import java.time.Instant

class UserProfileManager(workspaceDir: File) {

    data class UserProfile(
        val name: String? = null,
        val language: String? = null,
        val responseStyle: String? = null,
        val taskHabits: List<String> = emptyList(),
        val recurringNeeds: List<String> = emptyList(),
        val preferredTopics: List<String> = emptyList(),
        val focusRecent7d: List<String> = emptyList(),
        val gallerySummary: String? = null,
        val taskMemorySummary: String? = null,
        val updatedAt: Instant = Instant.now()
    )

    private val profileFile = File(workspaceDir, "USER-PROFILE.md")

    suspend fun getProfile(): UserProfile {
        if (!profileFile.exists()) return UserProfile()
        return try {
            val content = profileFile.readText()
            parseProfile(content)
        } catch (e: Exception) {
            Timber.w(e, "UserProfileManager: failed to read profile")
            UserProfile()
        }
    }

    suspend fun updateProfile(updater: (UserProfile) -> UserProfile): UserProfile {
        val current = getProfile()
        val updated = updater(current)
        writeProfile(updated)
        return updated
    }

    suspend fun evolveFromMemories(memories: List<CommonMemoryEntry>, taskLogs: List<String>) {
        val current = getProfile()
        val habits = memories.filter { it.type == BrainMemoryType.PREFERENCE }
            .map { it.summary }.take(10)
        val topics = memories.map { it.summary }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .take(5).map { it.key }

        val updated = current.copy(
            taskHabits = habits,
            preferredTopics = topics,
            focusRecent7d = taskLogs.take(5),
            updatedAt = Instant.now()
        )
        writeProfile(updated)
        Timber.i("UserProfileManager: evolved profile from ${memories.size} memories")
    }

    private fun writeProfile(profile: UserProfile) {
        val content = buildString {
            appendLine("# 用户画像")
            appendLine()
            appendLine("## 基本信息")
            profile.name?.let { appendLine("- 姓名: $it") }
            profile.language?.let { appendLine("- 语言: $it") }
            profile.responseStyle?.let { appendLine("- 回复风格: $it") }
            appendLine()
            appendLine("## 任务习惯")
            profile.taskHabits.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 常见需求")
            profile.recurringNeeds.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 偏好话题")
            profile.preferredTopics.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 近7天焦点")
            profile.focusRecent7d.forEach { appendLine("- $it") }
            appendLine()
            appendLine("_更新时间: ${profile.updatedAt}_")
        }
        profileFile.writeText(content)
    }

    private fun parseProfile(content: String): UserProfile {
        val lines = content.lines()
        var name: String? = null
        var language: String? = null
        var responseStyle: String? = null
        var gallerySummary: String? = null
        var taskMemorySummary: String? = null
        var currentSection = ""

        val taskHabits = mutableListOf<String>()
        val recurringNeeds = mutableListOf<String>()
        val preferredTopics = mutableListOf<String>()
        val focusRecent7d = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("## 基本信息") -> currentSection = "basic"
                trimmed.startsWith("## 任务习惯") -> currentSection = "habits"
                trimmed.startsWith("## 常见需求") -> currentSection = "needs"
                trimmed.startsWith("## 偏好话题") -> currentSection = "topics"
                trimmed.startsWith("## 近7天焦点") -> currentSection = "focus"
                trimmed.startsWith("## 相册摘要") -> currentSection = "gallery"
                trimmed.startsWith("## 任务记忆摘要") -> currentSection = "taskMemory"
                trimmed.startsWith("- ") -> {
                    val value = trimmed.removePrefix("- ").trim()
                    when (currentSection) {
                        "basic" -> when {
                            value.contains("姓名:") -> name = value.substringAfter("姓名:").trim()
                            value.contains("语言:") -> language = value.substringAfter("语言:").trim()
                            value.contains("回复风格:") -> responseStyle = value.substringAfter("回复风格:").trim()
                        }
                        "habits" -> taskHabits.add(value)
                        "needs" -> recurringNeeds.add(value)
                        "topics" -> preferredTopics.add(value)
                        "focus" -> focusRecent7d.add(value)
                    }
                }
            }
        }

        return UserProfile(
            name = name,
            language = language,
            responseStyle = responseStyle,
            taskHabits = taskHabits,
            recurringNeeds = recurringNeeds,
            preferredTopics = preferredTopics,
            focusRecent7d = focusRecent7d,
            gallerySummary = gallerySummary,
            taskMemorySummary = taskMemorySummary
        )
    }
}
