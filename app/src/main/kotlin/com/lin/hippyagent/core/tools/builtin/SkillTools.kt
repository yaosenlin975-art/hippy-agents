package com.lin.hippyagent.core.tools.builtin

import android.content.Context
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import com.lin.hippyagent.core.skill.builtin.PdfSkill
import com.lin.hippyagent.core.skill.builtin.DocxSkill
import com.lin.hippyagent.core.skill.builtin.XlsxSkill
import com.lin.hippyagent.core.skill.builtin.PptxSkill
import com.lin.hippyagent.core.skill.builtin.FileReaderSkill
import com.lin.hippyagent.core.skill.builtin.GuidanceSkill
import com.lin.hippyagent.core.skill.builtin.QASourceIndexSkill
import com.lin.hippyagent.core.skill.builtin.MultiAgentCollaborationSkill
import com.lin.hippyagent.core.tools.web.WebFetchTool
import com.lin.hippyagent.core.skill.builtin.HimalayaSkill
import com.lin.hippyagent.core.linux.LinuxManager
import timber.log.Timber

// ── PDF Skill Tool ──

class ReadPdfTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "read_pdf",
        description = "Read and extract text from PDF files. Supports simple uncompressed text PDFs only. Does NOT support scanned/images PDFs (no OCR), compressed streams, encrypted documents, or complex layouts. Inform the user if extraction fails.",
        parameters = mapOf(
            "file_path" to ToolParameter("file_path", "string", "PDF file path", true)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val path = getRequiredArgument(arguments, "file_path")
        return PdfSkill(context).readPdf(path).fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

class HimalayaTool(private val linuxManager: LinuxManager?) : Tool() {
    private val skill = HimalayaSkill(linuxManager)
    override val definition = ToolDefinition(
        name = "himalaya_email",
        description = "Email management via himalaya CLI. Supports listing, reading, searching and sending emails through IMAP/SMTP.",
        parameters = mapOf(
            "action" to ToolParameter("action", "string", "Action to perform: list_emails, read_email, search_emails, send_email", required = true),
            "email_id" to ToolParameter("email_id", "string", "Email ID (for read_email action)", required = false),
            "query" to ToolParameter("query", "string", "Search query (for search_emails action)", required = false),
            "to" to ToolParameter("to", "string", "Recipient email address (for send_email action)", required = false),
            "subject" to ToolParameter("subject", "string", "Email subject (for send_email action)", required = false),
            "body" to ToolParameter("body", "string", "Email body content (for send_email action)", required = false),
            "folder" to ToolParameter("folder", "string", "Email folder, default INBOX", required = false),
            "account" to ToolParameter("account", "string", "Email account name (if multiple configured)", required = false),
            "limit" to ToolParameter("limit", "integer", "Max emails to list (for list_emails action), default 20", required = false)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val action = getRequiredArgument(arguments, "action")
        val folder = (arguments["folder"] as? String) ?: "INBOX"
        val account = (arguments["account"] as? String) ?: ""
        val result = when (action) {
            "list_emails" -> {
                val limit = (arguments["limit"] as? Number)?.toInt() ?: 20
                skill.listEmails(account, folder, limit)
            }
            "read_email" -> {
                val id = getRequiredArgument(arguments, "email_id")
                skill.readEmail(id, account, folder)
            }
            "search_emails" -> {
                val query = getRequiredArgument(arguments, "query")
                skill.searchEmails(query, account, folder)
            }
            "send_email" -> {
                val to = getRequiredArgument(arguments, "to")
                val subject = getRequiredArgument(arguments, "subject")
                val body = getRequiredArgument(arguments, "body")
                skill.sendEmail(to, subject, body, account)
            }
            else -> return ToolResult(callId, false, error = "Unknown action: $action")
        }
        return result.fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

// ── DOCX Skill Tool ──

class ReadDocxTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "read_docx",
        description = "Read and extract plain text from Word (.docx) files. Extracts text from <w:t> tags in word/document.xml. Supports .docx (Office Open XML) format only. Does NOT support legacy .doc, images, charts, or embedded objects.",
        parameters = mapOf(
            "file_path" to ToolParameter("file_path", "string", "DOCX file path", true)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val path = getRequiredArgument(arguments, "file_path")
        return DocxSkill(context).readDocx(path).fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

// ── XLSX Skill Tool ──

class ReadXlsxTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "read_xlsx",
        description = "Read and extract table data from Excel (.xlsx) files. Supports string, numeric, and boolean cell types with shared string table resolution. Does NOT support formula results, charts, macros, or VBA. Supports .xlsx format only.",
        parameters = mapOf(
            "file_path" to ToolParameter("file_path", "string", "XLSX file path", true)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val path = getRequiredArgument(arguments, "file_path")
        return XlsxSkill(context).readXlsx(path).fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

// ── PPTX Skill Tool ──

class ReadPptxTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "read_pptx",
        description = "Read and extract text from PowerPoint (.pptx) slide presentations. Extracts <a:t> tag text from ppt/slides/ in numeric order. Supports .pptx format only. Does NOT support legacy .ppt, images, animations, or embedded objects.",
        parameters = mapOf(
            "file_path" to ToolParameter("file_path", "string", "PPTX file path", true)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val path = getRequiredArgument(arguments, "file_path")
        return PptxSkill(context).readPptx(path).fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

// ── File Reader Skill Tool ──

class SkillReadFileTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "skill_read_file",
        description = "File reader, supports multiple file formats (text/code/config)",
        parameters = mapOf(
            "file_path" to ToolParameter("file_path", "string", "File path", true)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val path = getRequiredArgument(arguments, "file_path")
        return FileReaderSkill(context).readFile(path).fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

// ── Guidance Skill Tool ──

class GuidanceTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "guidance",
        description = "Guidance assistant, search local docs for installation/configuration guides",
        parameters = mapOf(
            "query" to ToolParameter("query", "string", "Search keywords", true)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val query = getRequiredArgument(arguments, "query")
        return GuidanceSkill(context).searchDocs(query).fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

// ── QA Source Index Skill Tool ──

class QASourceIndexTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "qa_source_index",
        description = "Knowledge base index, search source code and doc paths by keyword",
        parameters = mapOf(
            "keyword" to ToolParameter("keyword", "string", "Search keywords", true)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val keyword = getRequiredArgument(arguments, "keyword")
        val skill = QASourceIndexSkill(context)
        return skill.searchSource(keyword).fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

// ── Browser CDP Skill Tool ──

class WebFetchSkillTool(private val context: Context) : Tool() {
    private val delegate = WebFetchTool(context)
    override val definition = delegate.definition
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        return delegate.execute(arguments)
    }
}

// ── Multi-Agent Collaboration Skill Tool ──

class MultiAgentCollaborationTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "multi_agent_collaboration",
        description = "Multi-agent collaboration, send messages or submit tasks to other agents",
        parameters = mapOf(
            "action" to ToolParameter("action", "string", "Action (chat/submit_task/check_task/list_agents)", true),
            "target_agent" to ToolParameter("target_agent", "string", "Target Agent ID", false),
            "message" to ToolParameter("message", "string", "Message content", false),
            "task_id" to ToolParameter("task_id", "string", "Task ID (required for check_task)", false)
        )
    )
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val action = getRequiredArgument(arguments, "action")
        val skill = MultiAgentCollaborationSkill(context)
        val result = when (action) {
            "chat" -> {
                val target = getRequiredArgument(arguments, "target_agent")
                val msg = getRequiredArgument(arguments, "message")
                skill.chatWithAgent(target, msg)
            }
            "submit_task" -> {
                val target = getRequiredArgument(arguments, "target_agent")
                val msg = getRequiredArgument(arguments, "message")
                skill.submitTask(target, msg)
            }
            "check_task" -> {
                val taskId = getRequiredArgument(arguments, "task_id")
                skill.checkTask(taskId)
            }
            "list_agents" -> skill.listAgents()
            else -> return ToolResult(callId, false, error = "Unknown action: $action")
        }
        return result.fold(
            onSuccess = { ToolResult(callId, true, output = it) },
            onFailure = { ToolResult(callId, false, error = it.message) }
        )
    }
}

