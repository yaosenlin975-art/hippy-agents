package com.lin.hippyagent.core.storage

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Agent 工作区管理
 */
class WorkspaceManager(
    private val context: Context,
    private val storageManager: StorageManager
) {
    /**
     * 获取 Agent 工作区目录
     */
    fun getAgentWorkspaceDir(agentId: String): File {
        val baseDir = storageManager.getWorkingDir()
        val workspaceDir = File(baseDir, "workspaces/$agentId")
        workspaceDir.mkdirs()
        return workspaceDir
    }

    fun getSessionWorkspaceDir(agentId: String, sessionId: String): File {
        val agentDir = getAgentWorkspaceDir(agentId)
        val sessionDir = File(agentDir, "sessions/$sessionId")
        sessionDir.mkdirs()
        return sessionDir
    }

    fun cleanupSessionWorkspace(agentId: String, sessionId: String): Boolean {
        val sessionDir = getSessionWorkspaceDir(agentId, sessionId)
        return if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        } else {
            false
        }
    }

    /**
     * 创建 Agent 工作区
     */
    fun createAgentWorkspace(agentId: String): File {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        workspaceDir.mkdirs()

        File(workspaceDir, "src").mkdirs()
        File(workspaceDir, "docs").mkdirs()
        File(workspaceDir, "tests").mkdirs()
        File(workspaceDir, "skills").mkdirs()

        val skillJsonFile = File(workspaceDir, "skill.json")
        if (!skillJsonFile.exists()) {
            skillJsonFile.writeText(
                """
                {
                  "schema_version": "workspace-skill-manifest.v1",
                  "version": ${System.currentTimeMillis()},
                  "skills": {}
                }
                """.trimIndent()
            )
        }

        Timber.i("Created workspace for agent: $agentId at ${workspaceDir.absolutePath}")
        return workspaceDir
    }

    /**
     * 删除 Agent 工作区
     */
    fun deleteAgentWorkspace(agentId: String): Boolean {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        return if (workspaceDir.exists()) {
            workspaceDir.deleteRecursively()
        } else {
            false
        }
    }

    /**
     * 检查 Agent 工作区是否存在
     */
    fun agentWorkspaceExists(agentId: String): Boolean {
        return getAgentWorkspaceDir(agentId).exists()
    }

    /**
     * 获取 Agent 工作区大小（字节）
     */
    fun getAgentWorkspaceSize(agentId: String): Long {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        return if (workspaceDir.exists()) {
            workspaceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
    }

    /**
     * 列出所有 Agent 工作区
     */
    fun listAgentWorkspaces(): List<String> {
        val baseDir = storageManager.getWorkingDir()
        val workspacesDir = File(baseDir, "workspaces")
        return if (workspacesDir.exists()) {
            workspacesDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * 获取工作区中的文件列表
     */
    fun listFiles(agentId: String, relativePath: String = ""): List<File> {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        val targetDir = if (relativePath.isEmpty()) {
            workspaceDir
        } else {
            File(workspaceDir, relativePath)
        }

        return if (targetDir.exists()) {
            targetDir.listFiles()?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * 读取工作区文件
     */
    fun readFile(agentId: String, relativePath: String): String? {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        val file = File(workspaceDir, relativePath)
        return if (file.exists() && file.isFile) {
            file.readText()
        } else {
            null
        }
    }

    /**
     * 写入工作区文件
     */
    fun writeFile(agentId: String, relativePath: String, content: String): Boolean {
        return try {
            val workspaceDir = getAgentWorkspaceDir(agentId)
            val file = File(workspaceDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to write file: $relativePath for agent: $agentId")
            false
        }
    }

    /**
     * 删除工作区文件
     */
    fun deleteFile(agentId: String, relativePath: String): Boolean {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        val file = File(workspaceDir, relativePath)
        return if (file.exists() && file.isFile) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * 检查工作区文件是否存在
     */
    fun fileExists(agentId: String, relativePath: String): Boolean {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        val file = File(workspaceDir, relativePath)
        return file.exists() && file.isFile
    }

    fun syncAgentSkillJson(agentId: String, skillId: String, skillName: String, skillDescription: String, source: String = "pool") {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        val skillsDir = File(workspaceDir, "skills")
        skillsDir.mkdirs()

        val skillJsonFile = File(workspaceDir, "skill.json")
        val existing = if (skillJsonFile.exists()) runCatching {
            org.json.JSONObject(skillJsonFile.readText())
        }.getOrNull() ?: org.json.JSONObject() else org.json.JSONObject()

        existing.put("schema_version", "workspace-skill-manifest.v1")
        existing.put("version", System.currentTimeMillis())

        val skills = existing.optJSONObject("skills") ?: org.json.JSONObject()
        if (!skills.has(skillId)) {
            val skillEntry = org.json.JSONObject().apply {
                put("enabled", true)
                put("channels", org.json.JSONArray().put("all"))
                put("source", source)
                put("metadata", org.json.JSONObject().apply {
                    put("name", skillName)
                    put("description", skillDescription)
                    put("source", source)
                })
                put("updated_at", java.time.Instant.now().toString())
                put("config", org.json.JSONObject())
            }
            skills.put(skillId, skillEntry)
            existing.put("skills", skills)
            skillJsonFile.writeText(existing.toString(2))
            Timber.i("Synced skill '$skillId' to agent '$agentId' skill.json")
        }

        val globalSkillsDir = File(storageManager.getWorkingDir(), "skills")
        val agentSkillDir = File(skillsDir, skillId)
        if (!agentSkillDir.exists()) {
            val sourceSkillDir = File(globalSkillsDir, skillId)
            if (sourceSkillDir.exists()) {
                sourceSkillDir.copyRecursively(agentSkillDir)
                Timber.i("Copied skill '$skillId' to agent '$agentId' skills/")
            }
        }
    }
}

