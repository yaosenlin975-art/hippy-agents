package com.lin.hippyagent.core.skill

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-agent [WorkspaceSkillConfigManager] 缓存。
 * 取代原 Koin 单例,保证每个智能体读 / 写自己的 `workspaces/$agentId/skill_config.json`。
 *
 * Koin 中以 `single` 注册,所有 UI / 运行时组件通过 [forAgent] 获取实例。
 */
class WorkspaceSkillConfigManagerRegistry(
    private val baseDir: File
) {
    private val cache = ConcurrentHashMap<String, WorkspaceSkillConfigManager>()

    fun forAgent(agentId: String): WorkspaceSkillConfigManager {
        require(SAFE_AGENT_ID.matches(agentId)) {
            "invalid agentId: $agentId (must match ${SAFE_AGENT_ID.pattern})"
        }
        return cache.getOrPut(agentId) {
            val workspaceDir = File(baseDir, "workspaces/$agentId").apply { mkdirs() }
            WorkspaceSkillConfigManager(
                workspaceDir = workspaceDir,
                agentId = agentId
            )
        }
    }

    /**
     * 扫描所有 agent 的 `workspaces/<agentId>/skill_config.json`,看是否有任何 agent 被用户改过。
     * 仅用于首次启动的 onboarding 提示(对所有 agent 取并集),非热路径,IO 一次可接受。
     *
     * 注意:不能仅靠 `configFile.length() > 0` 判断,v1→v2 迁移后空 entry 也可能 length>0。
     * 必须解析 JSON 检查 `skills` / `tools` map 是否真的有用户配置。
     */
    fun hasAnyNonEmptyConfig(): Boolean {
        val workspacesDir = File(baseDir, "workspaces")
        if (!workspacesDir.isDirectory) return false
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        return workspacesDir.listFiles()?.any { agentDir ->
            if (!agentDir.isDirectory) return@any false
            // 防御性:目录名是 agentId 同样需满足白名单,避免遍历到非 agent 目录
            if (!SAFE_AGENT_ID.matches(agentDir.name)) return@any false
            val configFile = File(agentDir, "skill_config.json")
            if (!configFile.exists() || configFile.length() == 0L) return@any false
            runCatching {
                val raw = configFile.readText()
                val cfg = json.decodeFromString(WorkspaceSkillConfig.serializer(), raw)
                cfg.skills.isNotEmpty() || cfg.tools.isNotEmpty()
            }.getOrElse { e ->
                timber.log.Timber.w(e, "WorkspaceSkillConfigManagerRegistry: failed to parse $configFile")
                false
            }
        } ?: false
    }

    private companion object {
        // 白名单 agentId: 字母数字下划线连字符,1-64 字符,防止路径穿越(".." / "/")
        private val SAFE_AGENT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
