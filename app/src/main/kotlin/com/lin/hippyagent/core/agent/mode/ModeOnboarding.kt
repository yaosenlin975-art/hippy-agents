package com.lin.hippyagent.core.agent.mode

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import com.lin.hippyagent.core.skill.WorkspaceSkillConfigManager

class ModeOnboarding(
    private val skillConfig: WorkspaceSkillConfigManager,
    private val prefs: SharedPreferences
) {
    fun showIfNeeded(context: Context) {
        if (prefs.getBoolean(KEY_ONBOARDED, false)) return
        val config = skillConfig.loadConfig()
        val isEmpty = config.skills.isEmpty() &&
            config.tools.isEmpty() &&
            config.enabledSkills.isEmpty() &&
            config.disabledSkills.isEmpty() &&
            config.skillOverrides.isEmpty()
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        if (isEmpty) {
            Toast.makeText(
                context,
                "在智能体设置中可以配置 Auto/Chat/Work 模式的 Skills 和 Tools 范围",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val KEY_ONBOARDED = "mode_onboarding_shown"
    }
}
