package com.lin.hippyagent.core.skill

import kotlinx.serialization.Serializable

@Serializable
data class SkillManifest(
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val source: String = "builtin",
    val tools: List<SkillToolDef> = emptyList(),
    val triggers: SkillTriggers = SkillTriggers(),
    val requires: SkillRequirements = SkillRequirements(),
    val dependencies: List<SkillDependency> = emptyList(),
    val permissions: List<SkillPermission> = emptyList(),
    val channels: List<String> = listOf("all"),
    val protected: Boolean = false
)

@Serializable
data class SkillTriggers(
    val keywords: List<String> = emptyList(),
    val fileExtensions: List<String> = emptyList(),
    val scenarios: List<String> = emptyList(),
    val shouldUse: List<String> = emptyList(),
    val shouldNotUse: List<String> = emptyList()
)

@Serializable
data class SkillToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, SkillParamDef> = emptyMap(),
    val script: String? = null,
    val ownership: String = "SHARED",
    val requiredPermissions: List<String> = emptyList()
)

@Serializable
data class SkillParamDef(
    val name: String = "",
    val type: String = "string",
    val description: String = "",
    val required: Boolean = true,
    val defaultValue: String? = null
)

@Serializable
data class SkillRequirements(
    val bins: List<String> = emptyList(),
    val envs: List<String> = emptyList(),
    val minApiLevel: Int = 21
)

@Serializable
data class SkillDependency(
    val skillId: String,
    val minVersion: String? = null,
    val optional: Boolean = false
)

@Serializable
data class SkillPermission(
    val type: String,
    val scope: String = "",
    val riskLevel: String = "MEDIUM"
)

@Serializable
data class SkillIndex(
    val version: Long = System.currentTimeMillis(),
    val skills: Map<String, SkillIndexEntry> = emptyMap()
)

@Serializable
data class SkillIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val source: String = "builtin",
    val toolNames: List<String> = emptyList(),
    val triggers: SkillTriggers = SkillTriggers(),
    val requiresBins: List<String> = emptyList(),
    val protected: Boolean = false,
    val installedAt: Long = 0,
    val updatedAt: Long = 0,
    val broken: Boolean = false,
    val manifestMtime: Long = 0,
    val manifestJson: String = ""
)
