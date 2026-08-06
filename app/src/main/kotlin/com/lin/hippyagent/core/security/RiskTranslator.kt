package com.lin.hippyagent.core.security

/**
 * 把 shell/exec 命令翻译为自然语言描述，并给出静态风险估计。
 *
 * 用于统一审批组件（方案 A：风险分级）：
 * - [translate] 把命令转成「它想访问 ~/Downloads 下的所有文件」式的人话
 * - [estimateRisk] 按命令特征估计风险档位（LOW / MEDIUM / HIGH / CRITICAL）
 * - [estimateToolRisk] 按工具名估计风险档位（TaskEntity 审批无 riskLevel 字段时使用）
 *
 * 纯 Kotlin，无 Android 依赖，可在 JVM 单元测试中直接验证。
 */
object RiskTranslator {

    // ═══════════════════════ 自然语言翻译 ═══════════════════════

    private data class Rule(val pattern: Regex, val describe: (MatchResult) -> String)

    // 顺序敏感：先匹配更具体的规则
    private val rules = listOf(
        Rule(Regex("""(?i)^rm\s+-rf\s+(.+)$""")) { m ->
            "将永久删除「${m.groupValues[1].trim()}」下的所有内容（不可恢复）"
        },
        Rule(Regex("""(?i)^rm\s+-r\s+(.+)$""")) { m ->
            "将递归删除「${m.groupValues[1].trim()}」及其子内容"
        },
        Rule(Regex("""(?i)^rm\s+(.+)$""")) { m ->
            "将删除文件或目录「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^sudo\s+(.+)$""")) { m ->
            "将以超级用户权限执行：${m.groupValues[1].trim()}"
        },
        Rule(Regex("""(?i)^git\s+push\s*(.+)?$""")) { m ->
            "将本地提交推送到远程仓库" + (m.groupValues[1].takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
        },
        Rule(Regex("""(?i)^git\s+pull\s*(.+)?$""")) { m ->
            "将从远程仓库拉取最新代码" + (m.groupValues[1].takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
        },
        Rule(Regex("""(?i)^git\s+clone\s+(.+)$""")) { m ->
            "将克隆远程仓库「${m.groupValues[1].trim()}」到本地"
        },
        Rule(Regex("""(?i)^git\s+commit\s*(.+)?$""")) { m ->
            "将创建一次本地代码提交" + (m.groupValues[1].takeIf { it.isNotBlank() }?.let { "（${it.trim()}）" } ?: "")
        },
        Rule(Regex("""(?i)^git\s+reset\s+--hard\s*(.+)?$""")) { m ->
            "将强制回退代码并丢弃未提交的修改" + (m.groupValues[1].takeIf { it.isNotBlank() }?.let { "（${it.trim()}）" } ?: "")
        },
        Rule(Regex("""(?i)^git\s+rm\s+(.+)$""")) { m ->
            "将删除仓库中的文件「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^git\s+(\S+)(.*)$""")) { m ->
            "将执行 git 操作（${m.groupValues[1]}${m.groupValues[2].trim().takeIf { it.isNotBlank() }?.let { " ${it}" } ?: ""}）"
        },
        Rule(Regex("""(?i)^npm\s+publish\s*(.+)?$""")) { m ->
            "将软件包发布到公共 npm 仓库" + (m.groupValues[1].takeIf { it.isNotBlank() }?.let { "（${it.trim()}）" } ?: "")
        },
        Rule(Regex("""(?i)^npm\s+install\s+(.+)$""")) { m ->
            "将安装依赖包「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^npm\s+run\s+(.+)$""")) { m ->
            "将运行 npm 脚本「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^npm\s+uninstall\s+(.+)$""")) { m ->
            "将卸载 npm 包「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^pip\s+install\s+(.+)$""")) { m ->
            "将安装 Python 包「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^pip3\s+install\s+(.+)$""")) { m ->
            "将安装 Python 包「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^apt\s+(install|remove|purge)\s+(.+)$""")) { m ->
            "将${when (m.groupValues[1]) { "install" -> "安装"; "remove" -> "移除"; else -> "清除" }}系统软件包「${m.groupValues[2].trim()}」"
        },
        Rule(Regex("""(?i)^chmod\s+(\S+)\s+(.+)$""")) { m ->
            "将修改「${m.groupValues[2].trim()}」的权限为 ${m.groupValues[1].trim()}"
        },
        Rule(Regex("""(?i)^chown\s+(\S+)\s+(.+)$""")) { m ->
            "将修改「${m.groupValues[2].trim()}」的所有者为 ${m.groupValues[1].trim()}"
        },
        Rule(Regex("""(?i)^cp\s+-r\s+(.+?)\s+(\S+)$""")) { m ->
            "将目录「${m.groupValues[1].trim()}」递归复制到「${m.groupValues[2].trim()}」"
        },
        Rule(Regex("""(?i)^cp\s+(.+?)\s+(\S+)$""")) { m ->
            "将「${m.groupValues[1].trim()}」复制到「${m.groupValues[2].trim()}」"
        },
        Rule(Regex("""(?i)^mv\s+(.+?)\s+(\S+)$""")) { m ->
            "将「${m.groupValues[1].trim()}」移动到「${m.groupValues[2].trim()}」"
        },
        Rule(Regex("""(?i)^mkdir\s+-p\s+(.+)$""")) { m ->
            "将创建目录「${m.groupValues[1].trim()}」（含上级目录）"
        },
        Rule(Regex("""(?i)^mkdir\s+(.+)$""")) { m ->
            "将创建目录「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^rmdir\s+(.+)$""")) { m ->
            "将删除空目录「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^cat\s+(.+)$""")) { m ->
            "将读取文件「${m.groupValues[1].trim()}」的内容"
        },
        Rule(Regex("""(?i)^head\s+(.+)$""")) { m ->
            "将查看文件「${m.groupValues[1].trim()}」的开头部分"
        },
        Rule(Regex("""(?i)^tail\s+-f\s+(.+)$""")) { m ->
            "将实时跟踪文件「${m.groupValues[1].trim()}」的新增内容"
        },
        Rule(Regex("""(?i)^tail\s+(.+)$""")) { m ->
            "将查看文件「${m.groupValues[1].trim()}」的末尾部分"
        },
        Rule(Regex("""(?i)^ls\s+(.+)$""")) { m ->
            "将列出目录「${m.groupValues[1].trim()}」的内容"
        },
        Rule(Regex("""(?i)^ls\s*$""")) { m ->
            "将列出当前目录的内容"
        },
        Rule(Regex("""(?i)^find\s+(.+)$""")) { m ->
            "将在「${m.groupValues[1].trim()}」中查找文件"
        },
        Rule(Regex("""(?i)^grep\s+(.+)$""")) { m ->
            "将搜索匹配「${m.groupValues[1].take(60)}」的内容"
        },
        Rule(Regex("""(?i)^curl\s+(.+)$""")) { m ->
            "将向「${m.groupValues[1].trim().take(80)}」发起网络请求"
        },
        Rule(Regex("""(?i)^wget\s+(.+)$""")) { m ->
            "将从「${m.groupValues[1].trim().take(80)}」下载文件"
        },
        Rule(Regex("""(?i)^kill\s+-9\s+(.+)$""")) { m ->
            "将强制终止进程 ${m.groupValues[1].trim()}"
        },
        Rule(Regex("""(?i)^kill\s+(.+)$""")) { m ->
            "将终止进程 ${m.groupValues[1].trim()}"
        },
        Rule(Regex("""(?i)^pkill\s+(.+)$""")) { m ->
            "将终止所有匹配「${m.groupValues[1].trim()}」的进程"
        },
        Rule(Regex("""(?i)^shutdown\s*(.+)?$""")) { m ->
            "将关闭系统" + (m.groupValues[1].takeIf { it.isNotBlank() }?.let { "（${it.trim()}）" } ?: "")
        },
        Rule(Regex("""(?i)^reboot\s*(.+)?$""")) { m ->
            "将重启系统" + (m.groupValues[1].takeIf { it.isNotBlank() }?.let { "（${it.trim()}）" } ?: "")
        },
        Rule(Regex("""(?i)^dd\s+(.+)$""")) { m ->
            "将以块级方式直接读写设备/文件（${m.groupValues[1].trim().take(80)}，可能造成数据损坏）"
        },
        Rule(Regex("""(?i)^mkfs(\.\w+)?\s+(.+)$""")) { m ->
            "将格式化「${m.groupValues[2].trim()}」（会清空其中的数据）"
        },
        Rule(Regex("""(?i)^echo\s+([^|>]+?)\s*>\s*>\s*(\S+)$""")) { m ->
            "将向文件「${m.groupValues[2].trim()}」追加内容「${m.groupValues[1].trim().take(40)}」"
        },
        Rule(Regex("""(?i)^echo\s+([^|>]+?)\s*>\s*(\S+)$""")) { m ->
            "将把内容「${m.groupValues[1].trim().take(40)}」写入文件「${m.groupValues[2].trim()}」"
        },
        Rule(Regex("""(?i)^echo\s+(.+)$""")) { m ->
            "将输出文本「${m.groupValues[1].trim().take(40)}」"
        },
        Rule(Regex("""(?i)^node\s+(.+)$""")) { m ->
            "将运行 Node.js 脚本「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^python3?\s+(.+)$""")) { m ->
            "将运行 Python 脚本「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^bash\s+(.+)$""")) { m ->
            "将运行 Shell 脚本「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^sh\s+(.+)$""")) { m ->
            "将运行 Shell 脚本「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^adb\s+install\s+(.+)$""")) { m ->
            "将通过 adb 安装应用「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^adb\s+uninstall\s+(.+)$""")) { m ->
            "将通过 adb 卸载应用「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^adb\s+shell\s+(.+)$""")) { m ->
            "将通过 adb 在设备上执行命令「${m.groupValues[1].trim().take(60)}」"
        },
        Rule(Regex("""(?i)^pm\s+install\s+(.+)$""")) { m ->
            "将在设备上安装应用「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^pm\s+uninstall\s+(.+)$""")) { m ->
            "将卸载设备应用「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^pm\s+clear\s+(.+)$""")) { m ->
            "将清除应用「${m.groupValues[1].trim()}」的全部数据"
        },
        Rule(Regex("""(?i)^am\s+start\s+(.+)$""")) { m ->
            "将启动应用/组件「${m.groupValues[1].trim().take(60)}」"
        },
        Rule(Regex("""(?i)^touch\s+(.+)$""")) { m ->
            "将创建或更新时间戳文件「${m.groupValues[1].trim()}」"
        },
        Rule(Regex("""(?i)^(df|du|ps|pwd|whoami|uname|date)\s*(.+)?$""")) { m ->
            "将查询系统信息（${m.groupValues[1]}）"
        },
        Rule(Regex("""(?i)^cd\s+(.+)$""")) { m ->
            "将工作目录切换到「${m.groupValues[1].trim()}」"
        }
    )

    /**
     * 把 shell 命令翻译为自然语言描述。
     * 未匹配任何规则时回退为「将执行命令：<command>」。
     */
    fun translate(command: String): String {
        val trimmed = command.trim()
        for (rule in rules) {
            val match = rule.pattern.find(trimmed) ?: continue
            return rule.describe(match)
        }
        return "将执行命令：${trimmed.take(120)}"
    }

    // ═══════════════════════ 风险估计 ═══════════════════════

    private val highRiskPatterns = listOf(
        Regex("""(?i)\bsudo\b"""),
        Regex("""(?i)\brm\s+-rf\b"""),
        Regex("""(?i)\brm\s+-r\b"""),
        Regex("""(?i)\bmkfs\b"""),
        Regex("""(?i)\bdd\s+if=""", RegexOption.IGNORE_CASE),
        Regex("""(?i)\bchmod\s+777\s+/"""),
        Regex("""(?i)\bchown\b"""),
        Regex("""(?i)\bshutdown\b"""),
        Regex("""(?i)\breboot\b"""),
        Regex("""(?i)\bkill\s+-9\s+1\b"""),
        Regex("""(?i)\bpm\s+clear\b"""),
        Regex("""(?i)\bpm\s+uninstall\b"""),
        Regex("""(?i)\badb\s+uninstall\b"""),
        Regex("""(?i)\bgit\s+reset\s+--hard\b"""),
        Regex("""(?i)\bgit\s+push\s+(-f|--force)\b"""),
        Regex("""(?i)\bmv\s+/\S+\s+/dev/null\b"""),
        Regex("""(?i)\brm\s+-rf\s+(/|~|/\*|/data|/storage)"""),
        Regex("""\b:\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;""")
    )

    private val lowRiskPatterns = listOf(
        Regex("""(?i)^\s*(ls|cat|pwd|cd|echo|head|tail|wc|find|grep|rg|ps|df|du|uname|whoami|date|touch)\b"""),
        Regex("""(?i)^\s*git\s+(status|diff|log|branch|show|stash list)\b"""),
        Regex("""(?i)^\s*(node|python3?|npm run|npm test|pip list)\b""")
    )

    /**
     * 按命令特征估计风险档位。
     * 黑名单/破坏性 → HIGH；只读/无害 → LOW；其余需审批的写操作 → MEDIUM。
     */
    fun estimateRisk(command: String): RiskLevel {
        val trimmed = command.trim()
        if (highRiskPatterns.any { it.containsMatchIn(trimmed) }) return RiskLevel.HIGH
        if (lowRiskPatterns.any { it.containsMatchIn(trimmed) }) return RiskLevel.LOW
        return RiskLevel.MEDIUM
    }

    private val lowRiskTools = setOf(
        "read_file", "list_directory", "search", "screen_observe", "screen_read",
        "get_screen", "list_files", "read_notes", "get_weather", "get_time"
    )

    private val highRiskTools = setOf(
        "delete_file", "execute_shell", "execute_bash", "shell_exec", "exec_command",
        "run_shell", "execute_python", "sudo_exec", "uninstall_app", "pm_clear",
        "shutdown_device", "format_disk", "send_message", "post_content",
        "ssh_execute", "remote_exec", "install_app", "pm_uninstall"
    )

    /**
     * 按工具名估计风险档位（TaskEntity 审批没有 riskLevel 字段时使用）。
     */
    fun estimateToolRisk(toolName: String): RiskLevel {
        val name = toolName.trim().lowercase()
        if (highRiskTools.any { name == it || name.startsWith("$it:") }) return RiskLevel.HIGH
        if (lowRiskTools.any { name == it || name.startsWith("$it:") }) return RiskLevel.LOW
        return RiskLevel.MEDIUM
    }
}
