package com.lin.hippyagent.ui.store

import androidx.compose.runtime.Immutable
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.StoreSkillItem

@Immutable
data class SkillStoreUiState(
    val isLoading: Boolean = false,
    val skills: List<StoreSkillItem> = emptyList(),
    val hotSkills: List<StoreSkillItem> = emptyList(),
    val searchQuery: String = "",
    val activeSource: SkillSource? = null,
    val sortType: SortType = SortType.HOT,
    val installingIds: Set<String> = emptySet(),
    val installedIds: Set<String> = emptySet(),
    val error: String? = null,
    val showInstallDialog: StoreSkillItem? = null,
    /** Node.js 环境状态提示，null 表示正常 */
    val nodeStatus: String? = null,
    /** 当前查看详情的技能 */
    val selectedSkill: StoreSkillItem? = null
)

enum class SortType(val displayName: String) {
    HOT("最热"),
    NEW("最新"),
    RATING("评分最高"),
    INSTALLS("最多安装")
}

