package com.lin.hippyagent.core.agent.middleware

import timber.log.Timber

class MemoryMiddleware : AgentMiddleware {

    override val priority: Int = PRIORITY
    override val name: String = NAME

    override fun afterAgent(context: MiddlewareContext) {
        val lastAssistantMsg = context.messages.lastOrNull { it.role == "assistant" }
        if (lastAssistantMsg == null || lastAssistantMsg.content.isNullOrBlank()) return

        val cleaned = stripUploadMentions(lastAssistantMsg.content)
        if (cleaned.isNotBlank()) {
            context.extra["memory_candidate"] = cleaned.take(500)
        }
    }

    private fun stripUploadMentions(text: String): String {
        return UPLOAD_SENTENCE_RE.replace(text, "").replace(Regex("  +"), " ").trim()
    }

    companion object {
        const val PRIORITY = 50
        const val NAME = "memory"

        private val UPLOAD_SENTENCE_RE = Regex(
            """[^.!?]*\b(?:upload(?:ed|ing)?(?:\s+\w+){0,3}\s+(?:file|files?|document|documents?|attachment|attachments?)|file\s+upload|/mnt/user-data/uploads/|<uploaded_files>|上传(?:了|过)?(?:\s*\w+){0,3}\s*(?:文件|附件|文档))[^.!?]*[.!?]?\s*""",
            RegexOption.IGNORE_CASE
        )
    }
}
