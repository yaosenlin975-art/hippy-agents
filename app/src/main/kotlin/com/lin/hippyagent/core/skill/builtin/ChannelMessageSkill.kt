package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Channel Message - 频道消息发送
 * 主动向会话或频道发送单向消息
 */
class ChannelMessageSkill(private val context: Context) {

    private val channelsDir: File get() = File(context.filesDir, "channels")

    fun sendMessage(channelName: String, message: String): Result<String> {
        return try {
            channelsDir.mkdirs()
            val channelFile = File(channelsDir, "$channelName.json")
            if (!channelFile.exists()) {
                // 自动创建频道
                channelFile.writeText("""{"name":"$channelName","messages":[]}""")
            }

            // 读取现有消息
            val json = channelFile.readText()
            val messages = extractMessages(json)

            // 追加新消息
            val newMessage = """{"content":"${message.replace("\"", "\\\"").replace("\n", "\\n")}","timestamp":${System.currentTimeMillis()},"sender":"agent"}"""
            val updatedMessages = messages + newMessage

            // 写回文件
            val updatedJson = """{"name":"$channelName","messages":[${updatedMessages.joinToString(",")}]}"""
            channelFile.writeText(updatedJson)

            Timber.d("Message sent to channel $channelName")
            Result.success("消息已发送到频道 '$channelName'")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send channel message")
            Result.failure(e)
        }
    }

    fun listChannels(): List<String> {
        return try {
            if (!channelsDir.exists()) return emptyList()
            channelsDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to list channels")
            emptyList()
        }
    }

    fun readMessages(channelName: String, limit: Int = 20): Result<List<String>> {
        return try {
            val channelFile = File(channelsDir, "$channelName.json")
            if (!channelFile.exists()) {
                return Result.success(emptyList())
            }
            val json = channelFile.readText()
            val messages = extractMessages(json).takeLast(limit)
            Result.success(messages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractMessages(json: String): List<String> {
        val messagesStart = json.indexOf("\"messages\":[")
        if (messagesStart == -1) return emptyList()
        val arrayStart = messagesStart + "\"messages\":[".length
        val arrayEnd = json.indexOf("]", arrayStart)
        if (arrayEnd == -1) return emptyList()
        val arrayContent = json.substring(arrayStart, arrayEnd).trim()
        if (arrayContent.isEmpty()) return emptyList()
        return arrayContent.split("},{").map { if (it.startsWith("[")) it else "{$it}" }
    }
}


