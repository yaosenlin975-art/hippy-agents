package com.lin.hippyagent.core.skill

import kotlinx.serialization.Serializable

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val isEnabled: Boolean,
    val installedAt: Long,
    val scripts: List<String> = emptyList(),
    val assets: List<String> = emptyList(),
    val isBuiltin: Boolean = false,
    /** 中文显示名，为空时回退到 [name] */
    val displayName: String = ""
) {
    /** 获取显示名 */
    fun displayNameOrName() = displayName.ifEmpty { name }
}

/** 系统内置技能中文名映射 */
object BuiltinSkillNames {
    val map = mapOf(
        "caveman" to "原始人模式",
        "code-review-and-quality" to "代码审查",
        "code-simplification" to "代码简化",
        "context-engineering" to "上下文工程",
        "design-an-interface" to "接口设计",
        "diagnose" to "诊断调试",
        "documentation-and-adrs" to "文档记录",
        "expert-brainstorm" to "专家头脑风暴",
        "explore" to "代码探索",
        "finishing-a-development-branch" to "开发分支收尾",
        "frontend-ui-engineering" to "前端UI工程",
        "git-workflow-and-versioning" to "Git工作流",
        "handoff" to "交接文档",
        "huashu-design" to "花叔设计",
        "improve-codebase-architecture" to "架构优化",
        "incremental-implementation" to "增量实现",
        "it-project-designer" to "IT项目设计",
        "memory-management" to "记忆管理",
        "research" to "研究分析",
        "security-review" to "安全审查",
        "testing-and-quality" to "测试质量",
        "writing-plans" to "编写计划",
        "change-management" to "变更管理"
    )

    fun getDisplayName(id: String): String = map[id] ?: id
}

@Serializable
data class SkillConfig(
    val skillId: String,
    val enabled: Boolean = true,
    val settings: Map<String, String> = emptyMap(),
    val secrets: Map<String, String> = emptyMap()
)

