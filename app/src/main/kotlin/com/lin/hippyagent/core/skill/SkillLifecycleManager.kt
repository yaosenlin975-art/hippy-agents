package com.lin.hippyagent.core.skill

import android.content.Context
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.core.tools.builtin.GuidanceTool
import com.lin.hippyagent.core.tools.builtin.ImageGenerateTool
import com.lin.hippyagent.core.tools.builtin.QASourceIndexTool
import com.lin.hippyagent.core.tools.builtin.ReadDocxTool
import com.lin.hippyagent.core.tools.builtin.ReadPdfTool
import com.lin.hippyagent.core.tools.builtin.ReadPptxTool
import com.lin.hippyagent.core.tools.builtin.ReadXlsxTool
import com.lin.hippyagent.core.tools.builtin.HimalayaTool
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SkillLifecycleManager(
    private val context: Context,
    private val skillManager: SkillManager,
    private val toolRegistry: ToolRegistry,
    private val linuxManager: com.lin.hippyagent.core.linux.LinuxManager? = null,
    private val modelProviderStore: ModelProviderStore? = null
) {
    private val activeSkillTools = ConcurrentHashMap<String, MutableList<String>>()

    fun activateSkill(skillId: String) {
        if (activeSkillTools.containsKey(skillId)) return

        val manifest = skillManager.getManifest(skillId) ?: return
        val tools = createToolsForSkill(skillId, manifest)
        val registeredNames = mutableListOf<String>()

        for (tool in tools) {
            toolRegistry.register(tool, hidden = true)
            registeredNames.add(tool.definition.name)
        }

        toolRegistry.revealTools(registeredNames)
        activeSkillTools[skillId] = registeredNames
        Timber.d("Skill activated: $skillId, registered tools: $registeredNames")
    }

    fun deactivateSkill(skillId: String) {
        val toolNames = activeSkillTools.remove(skillId) ?: return
        for (name in toolNames) {
            toolRegistry.unregister(name)
        }
        Timber.d("Skill deactivated: $skillId, unregistered tools: $toolNames")
    }

    fun activateSkills(skillIds: List<String>) {
        for (id in skillIds) {
            activateSkill(id)
        }
    }

    fun deactivateAll() {
        for ((skillId, toolNames) in activeSkillTools) {
            for (name in toolNames) {
                toolRegistry.unregister(name)
            }
            Timber.d("Skill deactivated: $skillId")
        }
        activeSkillTools.clear()
    }

    fun getActiveSkillIds(): Set<String> = activeSkillTools.keys.toSet()

    fun syncSkillsToAgentWorkspace(agentId: String, skillIds: List<String>, agentWorkspaceDir: File) {
        val targetSkillsDir = File(agentWorkspaceDir, "skills")
        targetSkillsDir.mkdirs()

        for (skillId in skillIds) {
            val sourceDir = skillManager.getSkillDir(skillId)
            if (sourceDir == null || !sourceDir.exists()) {
                Timber.w("syncSkills: skill dir not found for $skillId")
                continue
            }
            val destDir = File(targetSkillsDir, skillId)
            if (destDir.exists()) {
                continue
            }
            try {
                sourceDir.copyRecursively(destDir, overwrite = false)
                Timber.d("Skill '$skillId' copied to agent '$agentId' workspace")
            } catch (e: Exception) {
                Timber.e(e, "Failed to copy skill '$skillId' to agent workspace")
            }
        }
    }

    private fun createToolsForSkill(skillId: String, manifest: SkillManifest): List<Tool> {
        return when (skillId) {
            "pdf" -> listOf(ReadPdfTool(context))
            "docx" -> listOf(ReadDocxTool(context))
            "xlsx" -> listOf(ReadXlsxTool(context))
            "pptx" -> listOf(ReadPptxTool(context))
            "guidance" -> listOf(GuidanceTool(context))
            "qa_source_index" -> listOf(QASourceIndexTool(context))
            "image_generate" -> {
                if (modelProviderStore != null) {
                    listOf(ImageGenerateTool(context, modelProviderStore))
                } else {
                    emptyList()
                }
            }
            "himalaya" -> listOf(HimalayaTool(linuxManager))
            // 纯 prompt 技能（news, channel_message）无工具，仅通过 SKILL.md 注入提示词
            else -> emptyList()
        }
    }
}
