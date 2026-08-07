package com.lin.hippyagent.ui.shortcut

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.lin.hippyagent.ui.MainActivity
import com.lin.hippyagent.ui.entry.AgentEntryRouter
import timber.log.Timber
import java.util.LinkedHashMap

/**
 * 动态 Shortcuts 推送：Skill 激活时调 [pushRecentSkill] 把最近用过的 Skill 推到长按图标 Shortcut 列表。
 *
 * - 上限 4 个（系统 dynamic 总数 5 个，留 1 个余量）
 * - id 格式 `skill_${skillId}`，避免与静态 Shortcut 冲突
 * - 用 setRank 调整顺序（最近用的 rank=0）
 * - 缓存最近 4 个 skill 元数据用于 rank 重新计算
 *
 * 内存合规：[recentSkillsCache] 用 LinkedHashMap LRU 上限 8，超限淘汰。
 */
object AgentShortcuts {

    private const val MAX_DYNAMIC_SHORTCUTS = 4
    private const val CACHE_LIMIT = 8

    /** LRU 缓存最近 skillId→skillName，用于 rank 重排 */
    private val recentSkillsCache = object : LinkedHashMap<String, String>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > CACHE_LIMIT
    }

    /**
     * 推送（或更新）一个最近使用的 Skill 到动态 Shortcuts。
     * 调用时机：[com.lin.hippyagent.core.skill.SkillLifecycleManager.activateSkill] 成功后。
     *
     * 线程安全：activateSkill 可能从 AgentFactory/LoadSkillTool/ModeAwareSkillActivator 跨线程调用，
     * [recentSkillsCache] 是 LinkedHashMap 非线程安全，用 synchronized 包裹缓存读写。
     */
    fun pushRecentSkill(context: Context, skillId: String, skillName: String) {
        runCatching {
            val shortcuts = synchronized(recentSkillsCache) {
                recentSkillsCache[skillId] = skillName
                recentSkillsCache.entries
                    .take(MAX_DYNAMIC_SHORTCUTS)
                    .mapIndexed { index, (id, name) ->
                        ShortcutInfoCompat.Builder(context, "skill_$id")
                            .setShortLabel(name)
                            .setLongLabel(name)
                            .setRank(index)
                            .setIntent(Intent(context, MainActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra(AgentEntryRouter.EXTRA_AGENT_ACTION, AgentEntryRouter.ACTION_OPEN_CHAT)
                                putExtra(AgentEntryRouter.EXTRA_PROMPT, context.getString(com.lin.hippyagent.R.string.shortcut_skill_prompt, name))
                            })
                            .build()
                    }
            }
            // 用 setDynamicShortcuts 一次替换（原子操作），避免 removeAll + add 期间长按图标列表为空
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            Timber.i("AgentShortcuts: pushed ${shortcuts.size} dynamic shortcuts")
        }.onFailure { Timber.e(it, "AgentShortcuts.pushRecentSkill failed") }
    }

    /** 清空所有动态 Shortcuts（Settings 重置时调用） */
    fun clearAll(context: Context) {
        runCatching {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            synchronized(recentSkillsCache) { recentSkillsCache.clear() }
        }
    }
}
