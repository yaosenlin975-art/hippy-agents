package com.lin.hippyagent.data.repository

import android.content.Context
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.agent.config.ActiveHoursConfig
import com.lin.hippyagent.core.agent.config.CoreFile
import com.lin.hippyagent.core.agent.config.CoreFileType
import com.lin.hippyagent.core.agent.config.HeartbeatConfig
import com.lin.hippyagent.core.agent.config.MCPClientConfig
import com.lin.hippyagent.core.agent.config.MCPConfig
import com.lin.hippyagent.core.agent.config.RunningConfig
import com.lin.hippyagent.core.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.io.File
import java.time.Instant
import androidx.documentfile.provider.DocumentFile

class AgentRepository(
    private val context: Context,
    private val storageManager: StorageManager? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
) {
    companion object {
        const val DEFAULT_AGENT_ID = "default-agent"
        private val NAME_REGEX = Regex("""- \*\*名字[：:]\*\*\s*(.+)""")
        private val NAME_REPLACE_REGEX = Regex("""(- \*\*名字[：:]\*\*\s*)(.+)""")
    }

    private val profilesStateFlow = MutableStateFlow<Map<String, AgentProfile>>(emptyMap())
    @Volatile
    private var profilesLoaded = false

    /**
     * 使用 App 专属目录存储工作区数据，确保隐私和安全
     */
    private fun getWorkingDir(): File {
        return context.filesDir
    }

    fun getAgentWorkspaceDir(agentId: String): File {
        return File(getWorkingDir(), "workspaces/$agentId")
    }

    fun getAgentProfilePath(agentId: String): File {
        return File(getAgentWorkspaceDir(agentId), "agent.json")
    }

    /**
     * 加载 Agent Profiles。
     * 首次调用从磁盘加载并缓存到 profilesStateFlow，后续调用直接返回缓存。
     */
    fun loadAgentProfiles(): Flow<Map<String, AgentProfile>> {
        if (profilesLoaded) {
            return profilesStateFlow.asStateFlow()
        }
        return flow {
            val workingDir = getWorkingDir()
            val profilesDir = File(workingDir, "agents")

            if (!profilesDir.exists()) {
                profilesDir.mkdirs()
                profilesLoaded = true
                emit(emptyMap())
                return@flow
            }

            val profiles = mutableMapOf<String, AgentProfile>()
            profilesDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    try {
                        val profile = json.decodeFromString<AgentProfile>(file.readText())
                        profiles[profile.agentId] = profile
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to load agent profile: ${file.name}")
                    }
                }
            }

            profilesStateFlow.value = profiles
            profilesLoaded = true
            emit(profiles)
        }.flowOn(Dispatchers.IO)
    }

    suspend fun saveAgentProfile(profile: AgentProfile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val profilesDir = File(getWorkingDir(), "agents")
            profilesDir.mkdirs()

            val profileFile = File(profilesDir, "${profile.agentId}.json")
            val tempFile = File(profilesDir, "${profile.agentId}.json.tmp")

            tempFile.writeText(json.encodeToString(profile))
            tempFile.renameTo(profileFile)

            val currentProfiles = profilesStateFlow.value.toMutableMap()
            currentProfiles[profile.agentId] = profile
            profilesStateFlow.value = currentProfiles

            // 首次创建时生成默认核心文件
            val workspaceDir = getAgentWorkspaceDir(profile.agentId)
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs()
                createDefaultCoreFiles(profile.agentId)
            }

            // SAF 启用时，将新创建的智能体数据同步到外部存储
            syncToSafIfNeeded(profile.agentId)
        }.onFailure {
            Timber.e(it, "Failed to save agent profile: ${profile.agentId}")
        }
    }

    private suspend fun createDefaultCoreFiles(agentId: String) {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        val bootstrapCompletedMarker = File(workspaceDir, ".bootstrap_completed")
        val templateFiles = listOf("RULES.md", "AGENTS.md", "SOUL.md", "PROFILE.md", "MEMORY.md", "BOOTSTRAP.md")
        templateFiles.forEach { filename ->
            // 引导已完成则跳过创建 BOOTSTRAP.md，避免覆盖安装后重新触发引导
            if (filename == "BOOTSTRAP.md" && bootstrapCompletedMarker.exists()) {
                Timber.d("Skipping BOOTSTRAP.md creation: bootstrap already completed for $agentId")
                return@forEach
            }
            val file = File(workspaceDir, filename)
            if (!file.exists()) {
                val content = try {
                    context.assets.open("templates/$filename").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    Timber.w(e, "Template not found: $filename, using default")
                    "# $filename\n\n"
                }
                file.writeText(content)
            }
        }
    }

    /**
     * SAF 启用时，将智能体数据同步到外部存储
     * 确保外部挂载后新建的智能体资料也存放在外部
     */
    private fun syncToSafIfNeeded(agentId: String) {
        val sm = storageManager ?: return
        if (!sm.isSafEnabled()) return
        try {
            val safRoot = sm.getWorkingDirSaf() ?: return

            // 同步 agents/{agentId}.json
            val profileFile = File(getWorkingDir(), "agents/$agentId.json")
            if (profileFile.exists()) {
                val agentsSafDir = safRoot.findDir("agents") ?: safRoot.createDirectory("agents")
                if (agentsSafDir != null) {
                    val existing = agentsSafDir.findFile("$agentId.json")
                    if (existing == null) {
                        val target = agentsSafDir.createFile("*/*", "$agentId.json")
                        if (target != null) {
                            context.contentResolver.openOutputStream(target.uri)?.use { os ->
                                profileFile.inputStream().use { iss -> iss.copyTo(os) }
                            }
                        }
                    }
                }
            }

            // 同步 workspaces/{agentId}/ 目录
            val workspaceDir = getAgentWorkspaceDir(agentId)
            if (workspaceDir.exists()) {
                val workspacesSafDir = safRoot.findDir("workspaces") ?: safRoot.createDirectory("workspaces")
                if (workspacesSafDir != null) {
                    val agentWorkspaceSaf = workspacesSafDir.findDir(agentId) ?: workspacesSafDir.createDirectory(agentId)
                    if (agentWorkspaceSaf != null) {
                        syncDirToSaf(workspaceDir, agentWorkspaceSaf)
                    }
                }
            }
            Timber.i("Synced agent $agentId data to SAF")
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync agent $agentId to SAF")
        }
    }

    /**
     * 递归同步 File 目录到 SAF DocumentFile（仅同步 SAF 中不存在的文件）
     */
    private fun syncDirToSaf(sourceDir: File, targetDir: DocumentFile) {
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val existing = targetDir.findFile(file.name)
                if (existing == null) {
                    val target = targetDir.createFile("*/*", file.name)
                    if (target != null) {
                        try {
                            context.contentResolver.openOutputStream(target.uri)?.use { os ->
                                file.inputStream().use { iss -> iss.copyTo(os) }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to sync file to SAF: ${file.name}")
                        }
                    }
                }
            } else if (file.isDirectory) {
                val subDir = targetDir.findDir(file.name) ?: targetDir.createDirectory(file.name)
                if (subDir != null) {
                    syncDirToSaf(file, subDir)
                }
            }
        }
    }

    private fun DocumentFile.findDir(name: String): DocumentFile? {
        val found = findFile(name)
        return if (found != null && found.isDirectory) found else null
    }

    suspend fun deleteAgentProfile(agentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val profileFile = File(getWorkingDir(), "agents/$agentId.json")
            if (profileFile.exists()) {
                profileFile.delete()
            }

            val currentProfiles = profilesStateFlow.value.toMutableMap()
            currentProfiles.remove(agentId)
            profilesStateFlow.value = currentProfiles
        }.onFailure {
            Timber.e(it, "Failed to delete agent profile: $agentId")
        }
    }

    fun getRunningConfig(agentId: String): Flow<RunningConfig> = flow {
        val profile = profilesStateFlow.value[agentId]
        if (profile != null) {
            emit(profile.running)
        } else {
            emit(RunningConfig())
        }
    }.flowOn(Dispatchers.IO)

    suspend fun saveRunningConfig(agentId: String, config: RunningConfig): Result<Unit> {
        val profile = profilesStateFlow.value[agentId] ?: return Result.failure(IllegalStateException("Agent not found: $agentId"))

        val updatedProfile = profile.copy(running = config)
        return saveAgentProfile(updatedProfile)
    }

    fun getHeartbeatConfig(agentId: String): Flow<HeartbeatConfig> = flow {
        val profile = profilesStateFlow.value[agentId]
        if (profile != null) {
            emit(profile.heartbeat)
        } else {
            emit(HeartbeatConfig())
        }
    }.flowOn(Dispatchers.IO)

    suspend fun saveHeartbeatConfig(agentId: String, config: HeartbeatConfig): Result<Unit> {
        val profile = profilesStateFlow.value[agentId] ?: return Result.failure(IllegalStateException("Agent not found: $agentId"))

        val updatedProfile = profile.copy(heartbeat = config)
        return saveAgentProfile(updatedProfile)
    }

    fun getMCPConfig(agentId: String): Flow<MCPConfig> = flow {
        val profile = profilesStateFlow.value[agentId]
        if (profile != null) {
            emit(profile.mcp)
        } else {
            emit(MCPConfig())
        }
    }.flowOn(Dispatchers.IO)

    suspend fun saveMCPConfig(agentId: String, config: MCPConfig): Result<Unit> {
        val profile = profilesStateFlow.value[agentId] ?: return Result.failure(IllegalStateException("Agent not found: $agentId"))

        val updatedProfile = profile.copy(mcp = config)
        return saveAgentProfile(updatedProfile)
    }

    suspend fun saveSkills(agentId: String, skills: List<String>): Result<Unit> {
        val profile = profilesStateFlow.value[agentId] ?: return Result.failure(IllegalStateException("Agent not found: $agentId"))

        // 1. 写入 skill.json（权威源）
        val workspaceDir = getAgentWorkspaceDir(agentId)
        val skillJsonFile = java.io.File(workspaceDir, "skill.json")
        try {
            val existing = if (skillJsonFile.exists()) {
                org.json.JSONObject(skillJsonFile.readText())
            } else {
                org.json.JSONObject()
            }
            existing.put("schema_version", "workspace-skill-manifest.v1")
            existing.put("version", System.currentTimeMillis())

            val skillsObj = existing.optJSONObject("skills") ?: org.json.JSONObject()
            // 先清除所有技能的 enabled 状态
            val keys = skillsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = skillsObj.optJSONObject(key)
                if (entry != null) {
                    entry.put("enabled", skills.contains(key))
                    skillsObj.put(key, entry)
                }
            }
            // 添加新技能
            for (skillId in skills) {
                if (!skillsObj.has(skillId)) {
                    val newEntry = org.json.JSONObject().apply {
                        put("enabled", true)
                        put("channels", org.json.JSONArray().put("all"))
                        put("source", "pool")
                        put("metadata", org.json.JSONObject())
                        put("updated_at", java.time.Instant.now().toString())
                        put("config", org.json.JSONObject())
                    }
                    skillsObj.put(skillId, newEntry)
                }
            }
            existing.put("skills", skillsObj)
            skillJsonFile.writeText(existing.toString(2))
            Timber.i("Saved skills to skill.json for agent $agentId: $skills")
        } catch (e: Exception) {
            Timber.w(e, "Failed to write skill.json for agent $agentId")
        }

        // 2. 同步更新 agents/xxx.json 中的 skills 字段（兼容性保留）
        val updatedProfile = profile.copy(skills = skills)
        return saveAgentProfile(updatedProfile)
    }

    /**
     * 从 skill.json 读取智能体启用的技能列表（权威源）
     * 如果 skill.json 不存在或读取失败，回退到 AgentProfile.skills
     */
    fun getEnabledSkillsFromJson(agentId: String): List<String> {
        val workspaceDir = getAgentWorkspaceDir(agentId)
        val skillJsonFile = java.io.File(workspaceDir, "skill.json")
        if (!skillJsonFile.exists()) {
            // 回退到 AgentProfile.skills
            return profilesStateFlow.value[agentId]?.skills ?: emptyList()
        }
        return try {
            val json = org.json.JSONObject(skillJsonFile.readText())
            val skillsObj = json.optJSONObject("skills") ?: return emptyList()
            val enabledSkills = mutableListOf<String>()
            val keys = skillsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = skillsObj.optJSONObject(key)
                if (entry != null && entry.optBoolean("enabled", true)) {
                    enabledSkills.add(key)
                }
            }
            enabledSkills
        } catch (e: Exception) {
            Timber.w(e, "Failed to read skill.json for agent $agentId, falling back to profile")
            profilesStateFlow.value[agentId]?.skills ?: emptyList()
        }
    }

    fun getAgentById(agentId: String): AgentProfile? = profilesStateFlow.value[agentId]

    fun getProfiles(): Flow<Map<String, AgentProfile>> = profilesStateFlow.asStateFlow()

    /**
     * 从磁盘重新加载所有智能体 Profile，更新内存缓存。
     * 在智能体通过文件工具（write_file/edit_file）修改 PROFILE.md 或 agent.json 后调用，
     * 同时扫描 PROFILE.md 与 agent.json 的名字差异并同步。
     * 确保 profilesStateFlow 与磁盘一致。
     *
     * 注意：智能体直接通过 edit_file/write_file 修改 PROFILE.md 时，
     * agent.json 不会被同步。因此 refreshProfiles 同时扫描每个智能体的 PROFILE.md，
     * 如果发现名字不一致则自动同步到 agent.json。
     */
    suspend fun refreshProfiles(): Map<String, AgentProfile> = withContext(Dispatchers.IO) {
        val profilesDir = File(getWorkingDir(), "agents")
        val profiles = mutableMapOf<String, AgentProfile>()

        // 1. 从 agent.json 加载现有 Profile
        if (profilesDir.exists()) {
            profilesDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    try {
                        val text = file.readText()
                        val profile = json.decodeFromString<AgentProfile>(text)
                        profiles[profile.agentId] = profile
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to load agent profile: ${file.name}")
                    }
                }
            }
        }

        // 2. 检查每个智能体的 PROFILE.md，发现名字不一致则同步
        val nameRegex = NAME_REGEX
        for ((agentId, profile) in profiles.toMap()) {
            val profileMd = File(getAgentWorkspaceDir(agentId), "PROFILE.md")
            if (profileMd.exists()) {
                try {
                    val content = profileMd.readText()
                    val match = nameRegex.find(content)
                    if (match != null) {
                        val profileName = match.groupValues[1].trim()
                        if (profileName.isNotBlank() && profile.name != profileName) {
                            Timber.d("refreshProfiles: PROFILE.md name='$profileName' differs from agent.json '${profile.name}', syncing")
                            val updated = profile.copy(name = profileName)
                            saveAgentProfile(updated)
                            profiles[agentId] = updated
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read PROFILE.md for $agentId")
                }
            }
        }

        if (profiles.isNotEmpty()) {
            profilesStateFlow.value = profiles
        }
        profiles
    }

    fun getCoreFiles(agentId: String): Flow<List<CoreFile>> = flow {
        val profile = profilesStateFlow.value[agentId]
        val workspaceDir = getAgentWorkspaceDir(agentId)

        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
            createDefaultCoreFiles(agentId)
        }

        val enabledFiles = profile?.coreFiles ?: listOf(
            "RULES.md", "AGENTS.md", "SOUL.md", "PROFILE.md"
        )

        // 使用 CoreFileType 枚举定义所有默认核心文件，确保全部显示
        val defaultFiles = CoreFileType.entries.map { it.filename }
        val coreFiles = mutableListOf<CoreFile>()
        val seenFilenames = mutableSetOf<String>()

        for (filename in defaultFiles) {
            val file = File(workspaceDir, filename)
            val fileExists = file.exists()
            coreFiles.add(CoreFile(
                filename = filename,
                size = if (fileExists) file.length() else 0,
                lastModified = if (fileExists) java.time.Instant.ofEpochMilli(file.lastModified()) else java.time.Instant.now(),
                enabled = filename in enabledFiles,
                exists = fileExists
            ))
            seenFilenames.add(filename)
        }

        // 扫描工作区根目录的其他 .md 文件（非默认文件）
        workspaceDir.listFiles()?.filter { it.isFile && it.extension == "md" && it.name !in seenFilenames }?.forEach { file ->
            coreFiles.add(CoreFile(
                filename = file.name,
                size = file.length(),
                lastModified = java.time.Instant.ofEpochMilli(file.lastModified()),
                enabled = file.name in enabledFiles,
                exists = true
            ))
            seenFilenames.add(file.name)
        }

        // 扫描 memory/ 子目录的 .md 文件
        val memoryDir = File(workspaceDir, "memory")
        if (memoryDir.exists() && memoryDir.isDirectory) {
            memoryDir.listFiles()?.filter { it.isFile && it.extension == "md" }?.forEach { file ->
                val filename = "memory/${file.name}"
                if (filename !in seenFilenames) {
                    coreFiles.add(CoreFile(
                        filename = filename,
                        size = file.length(),
                        lastModified = java.time.Instant.ofEpochMilli(file.lastModified()),
                        enabled = filename in enabledFiles,
                        exists = true
                    ))
                    seenFilenames.add(filename)
                }
            }
        }

        emit(coreFiles)
    }.flowOn(Dispatchers.IO)

    suspend fun saveCoreFiles(agentId: String, files: List<CoreFile>): Result<Unit> {
        val profile = profilesStateFlow.value[agentId] ?: return Result.failure(IllegalStateException("Agent not found: $agentId"))

        val enabledFiles = files
            .filter { it.enabled }
            .map { it.filename }

        val updatedProfile = profile.copy(coreFiles = enabledFiles)
        return saveAgentProfile(updatedProfile)
    }

    suspend fun reorderCoreFiles(agentId: String, newOrder: List<String>): Result<Unit> {
        val profile = profilesStateFlow.value[agentId] ?: return Result.failure(IllegalStateException("Agent not found: $agentId"))

        val updatedProfile = profile.copy(coreFiles = newOrder)
        return saveAgentProfile(updatedProfile)
    }

    fun toggleCoreFile(agentId: String, filename: String, enabled: Boolean): Flow<List<CoreFile>> = flow {
        val profile = profilesStateFlow.value[agentId]
        if (profile != null) {
            val currentEnabled = profile.coreFiles.toMutableList()
            if (enabled) {
                if (filename !in currentEnabled) {
                    currentEnabled.add(filename)
                }
            } else {
                currentEnabled.remove(filename)
            }

            val updatedProfile = profile.copy(coreFiles = currentEnabled)
            saveAgentProfile(updatedProfile)
        }

        getCoreFiles(agentId).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    suspend fun createCoreFile(agentId: String, filename: String, content: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val workspaceDir = getAgentWorkspaceDir(agentId)
            workspaceDir.mkdirs()

            val file = File(workspaceDir, filename)
            if (!file.exists()) {
                file.writeText(content)
            }
        }.onFailure {
            Timber.e(it, "Failed to create core file: $filename")
        }
    }

    suspend fun readCoreFile(agentId: String, filename: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val workspaceDir = getAgentWorkspaceDir(agentId)
            val file = File(workspaceDir, filename)
            if (file.exists()) {
                file.readText()
            } else {
                ""
            }
        }.onFailure {
            Timber.e(it, "Failed to read core file: $filename")
        }
    }

    suspend fun writeCoreFile(agentId: String, filename: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val workspaceDir = getAgentWorkspaceDir(agentId)
            workspaceDir.mkdirs()

            val file = File(workspaceDir, filename)
            file.parentFile?.mkdirs()
            val tempFile = File(workspaceDir, "$filename.tmp")

            tempFile.writeText(content)
            tempFile.renameTo(file)

            if (filename == "PROFILE.md") {
                syncNameFromProfileMd(agentId, content)
            }
            Unit
        }.onFailure {
            Timber.e(it, "Failed to write core file: $filename")
        }
    }

    private suspend fun syncNameFromProfileMd(agentId: String, content: String) {
        val nameRegex = NAME_REGEX
        val match = nameRegex.find(content)
        if (match != null) {
            val newName = match.groupValues[1].trim()
            if (newName.isNotBlank()) {
                val profiles = profilesStateFlow.value
                val current = profiles[agentId]
                if (current != null && current.name != newName) {
                    saveAgentProfile(current.copy(name = newName))
                }
            }
        }
    }

    suspend fun createDefaultAgentIfNeeded(): Result<Unit> {
        if (profilesStateFlow.value.isEmpty()) {
            loadAgentProfiles().first()
        }
        if (profilesStateFlow.value.isEmpty()) {
            val defaultKit = listOf(
                "read_file", "write_file", "edit_file", "append_file", "delete_file",
                "glob_search", "grep_search", "list_directory",
                "memory_search", "web_fetch", "web_search"
            )
            val defaultProfile = AgentProfile(
                agentId = DEFAULT_AGENT_ID,
                name = "Hippy",
                modelName = "",
                defaultToolKit = defaultKit + listOf("get_current_time", "ask", "tool_search"),
                modelProvider = "",
                isDefault = true,
                enabled = true,
                avatarUrl = ensureBuiltinAvatar(),
                running = RunningConfig(maxIters = 10, maxInputLength = 65536),
                heartbeat = HeartbeatConfig(enabled = false),
                mcp = MCPConfig(),
                coreFiles = listOf("RULES.md", "AGENTS.md", "SOUL.md", "PROFILE.md"),
                skills = listOf("news", "himalaya", "channel_message", "pdf", "docx", "xlsx", "pptx", "guidance", "qa_source_index", "image_generate")
            )
            saveAgentProfile(defaultProfile)
            syncNameToProfileMd(defaultProfile.agentId, defaultProfile.name)
            return Result.success(Unit)
        }
        val defaultAgent = profilesStateFlow.value.values.find { it.agentId == DEFAULT_AGENT_ID }
        if (defaultAgent != null && defaultAgent.avatarUrl.isNullOrBlank()) {
            val avatarPath = ensureBuiltinAvatar()
            saveAgentProfile(defaultAgent.copy(avatarUrl = avatarPath))
        }
        return Result.success(Unit)
    }

    private fun ensureBuiltinAvatar(): String {
        val avatarsDir = File(context.filesDir, "avatars")
        avatarsDir.mkdirs()
        val outputFile = File(avatarsDir, "hippy.png")
        if (!outputFile.exists()) {
            context.resources.openRawResource(R.drawable.ic_hippy_avatar).use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outputFile.absolutePath
    }

    /** 将名字同步到 PROFILE.md */
    private suspend fun syncNameToProfileMd(agentId: String, name: String) {
        if (name.isBlank()) return
        val profileMd = File(getAgentWorkspaceDir(agentId), "PROFILE.md")
        if (!profileMd.exists()) return
        try {
            val content = profileMd.readText()
            val nameRegex = NAME_REPLACE_REGEX
            val updated = if (nameRegex.containsMatchIn(content)) {
                nameRegex.replace(content) { match ->
                    "${match.groupValues[1]}$name"
                }
            } else content
            if (updated != content) {
                profileMd.writeText(updated)
            }
        } catch (_: Exception) { }
    }
}



