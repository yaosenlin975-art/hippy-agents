package com.lin.hippyagent.ui.store

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.lin.hippyagent.R
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
     val installedIds: Set<String> = emptySet(),
     val installedNormalizedIds: Set<String> = emptySet(),
    val error: String? = null,
    val showInstallDialog: StoreSkillItem? = null,
    val nodeStatus: NodeStatus = NodeStatus.Unknown,
    val selectedSkill: StoreSkillItem? = null,
    val installTarget: InstallTarget = InstallTarget.Workspace,
    val providerErrors: List<com.lin.hippyagent.core.skill.store.provider.MarketSearchError> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val installMessage: String? = null,
    val installingIds: Set<String> = emptySet(),
    val queuedIds: Set<String> = emptySet()
)

sealed class NodeStatus {
    data object Unknown : NodeStatus()
    data object Checking : NodeStatus()
    data object Installing : NodeStatus()
    data object Ready : NodeStatus()
    data object Failed : NodeStatus()
}

enum class InstallTarget(@StringRes val displayNameRes: Int) {
    Workspace(R.string.install_target_workspace),
    Pool(R.string.install_target_pool)
}

enum class SortType(@StringRes val displayNameRes: Int) {
    HOT(R.string.store_sort_hot),
    NEW(R.string.store_sort_new),
    RATING(R.string.store_sort_rating),
    INSTALLS(R.string.store_sort_installs)
}
