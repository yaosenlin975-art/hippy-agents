package com.lin.hippyagent.core.agent.group

import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File

/**
 * 文件任务交接 — Leader 写 spec → Worker 执行写 result → Leader 拉取 result
 *
 * 任务存储在共享工作区目录: workspace/team-tasks/{taskId}/
 *
 * 流程:
 *   Leader: writeSpec(taskId, spec) → 通知 Worker
 *   Worker: readSpec(taskId) → 执行 → writeResult(taskId, result) → 通知 Leader
 *   Leader: readResult(taskId) → 完成
 */
class TeamTaskHandoff(
    private val workspaceDir: File
) {
    private val tasksDir: File get() = File(workspaceDir, "team-tasks")

    /** 写入任务规格文件 */
    fun writeSpec(taskId: String, spec: String): Result<String> = runCatching {
        val taskDir = getOrCreateTaskDir(taskId)
        val specFile = File(taskDir, "spec.md")
        specFile.writeText(spec)
        Timber.d("Spec written for task $taskId: ${spec.length} chars")
        specFile.absolutePath
    }

    /** 读取任务规格文件 */
    fun readSpec(taskId: String): Result<String?> = runCatching {
        val specFile = File(File(tasksDir, taskId), "spec.md")
        if (!specFile.exists()) {
            Timber.w("Spec not found for task $taskId")
            return@runCatching null
        }
        specFile.readText()
    }

    /** 写入任务结果文件 */
    fun writeResult(taskId: String, result: String): Result<String> = runCatching {
        val taskDir = getOrCreateTaskDir(taskId)
        val resultFile = File(taskDir, "result.md")
        resultFile.writeText(result)
        Timber.d("Result written for task $taskId: ${result.length} chars")
        resultFile.absolutePath
    }

    /** 读取任务结果文件 */
    fun readResult(taskId: String): Result<String?> = runCatching {
        val resultFile = File(File(tasksDir, taskId), "result.md")
        if (!resultFile.exists()) {
            Timber.w("Result not found for task $taskId")
            return@runCatching null
        }
        resultFile.readText()
    }

    /** 获取任务状态：pending / in-progress / completed */
    fun getTaskStatus(taskId: String): String {
        val taskDir = File(tasksDir, taskId)
        if (!taskDir.exists()) return "pending"
        val hasSpec = File(taskDir, "spec.md").exists()
        val hasResult = File(taskDir, "result.md").exists()
        return when {
            hasSpec && hasResult -> "completed"
            hasSpec -> "in-progress"
            else -> "pending"
        }
    }

    /** 列出所有团队任务 ID */
    fun listTaskIds(): List<String> {
        val dir = tasksDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?.toList() ?: emptyList()
    }

    /** 清理已完成任务的文件（仅保留目录） */
    fun cleanTaskFiles(taskId: String): Result<Unit> = runCatching {
        val taskDir = File(tasksDir, taskId)
        if (!taskDir.exists()) return@runCatching
        File(taskDir, "spec.md").delete()
        File(taskDir, "result.md").delete()
        Timber.d("Task files cleaned for $taskId")
    }

    private fun getOrCreateTaskDir(taskId: String): File {
        val dir = File(tasksDir, taskId)
        dir.mkdirs()
        return dir
    }
}
