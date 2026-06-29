package com.lin.hippyagent.core.security

/**
 * 全局唯一安全正则库。
 * 规则：所有安全相关 Regex 必须在此定义，禁止在其他文件 companion object 或函数内重复构造。
 */
object PatternLibrary {

    // ===== Shell 逃逸（原 ToolGuardian.SHELL_EVASION_PATTERNS，32 个）=====
    val SHELL_EVASION_PATTERNS: List<Regex> = listOf(
        Regex("""\\x[0-9a-fA-F]{2}"""),
        Regex("""\\u[0-9a-fA-F]{4}"""),
        Regex("""\$\{.*\}"""),
        Regex("""\$\([^)]+\)"""),
        Regex("""`[^`]+`"""),
        Regex("""(?i)base64\s+--decode"""),
        Regex("""(?i)xxd\s+-r"""),
        Regex("""(?i)printf\s+\\x"""),
        Regex("""(?i)echo\s+-e\s+\\x"""),
        Regex("""(?i)eval\s+["']"""),
        Regex("""(?i)exec\s+["']"""),
        Regex("""(?i)python[23]?\s+-c"""),
        Regex("""(?i)perl\s+-e"""),
        Regex("""(?i)ruby\s+-e"""),
        Regex("""(?i)env\s+-[iS]"""),
        Regex("""(?i)/dev/tcp/"""),
        Regex("""(?i)nc\s+-[elp]"""),
        Regex("""(?i)curl\s+.*\|\s*sh"""),
        Regex("""(?i)wget\s+.*\|\s*sh"""),
        Regex("""(?i)chmod\s+\+x"""),
        Regex("""(?i)chown\s+root"""),
        Regex("""(?i)nohup\s+"""),
        Regex("""(?i)setsid\s+"""),
        Regex(""";\s*rm\s+-rf"""),
        Regex("""\|\s*rm\s+-rf"""),
        Regex("""&&\s*rm\s+-rf"""),
        Regex("""(?i)su\s+-c"""),
        Regex("""(?i)sudo\s+"""),
        Regex("""(?i)mount\s+-o\s+remount"""),
        Regex("""(?i)iptables\s+"""),
        Regex("""(?i)insmod\s+"""),
        Regex("""(?i)rmmod\s+""")
    )

    // ===== Shell 混淆指示器（原 ToolGuardian.SHELL_OBFUSCATION_INDICATORS，8 个）=====
    val SHELL_OBFUSCATION_INDICATORS: List<Regex> = listOf(
        Regex("""\$\{IFS\}"""),
        Regex("""\$\{PATH\:"""),
        Regex("""''"""),
        Regex("""(?i)\bcat\b\s+.*\bcat\b"""),
        Regex("""(?i)head\s+-c\s+\d+\s+"""),
        Regex("""(?i)tail\s+-c\s+\d+\s+"""),
        Regex("""(?i)rev\s+"""),
        Regex("""(?i)tr\s+""")
    )

    // ===== 危险命令模式（原 ToolGuardian.DANGEROUS_PATTERNS，14 个）=====
    val DANGEROUS_COMMAND_PATTERNS: List<Regex> = listOf(
        Regex("""rm\s+-rf\s+/"""),
        Regex("""dd\s+if="""),
        Regex("""mkfs"""),
        Regex("""chmod\s+777\s+/"""),
        Regex("""chown\s+root"""),
        Regex("""sudo\s+"""),
        Regex("""su\s+-"""),
        Regex("""curl.*\|.*sh"""),
        Regex("""wget.*\|.*sh"""),
        Regex("""DROP\s+TABLE""", RegexOption.IGNORE_CASE),
        Regex("""DELETE\s+FROM""", RegexOption.IGNORE_CASE),
        Regex("""INSERT\s+INTO""", RegexOption.IGNORE_CASE),
        Regex("""\$\(.*\)"""),
        Regex("""`.*`""")
    )

    // ===== 敏感输入（原 ToolGuardian.SENSITIVE_INPUT_PATTERNS，3 个）=====
    val SENSITIVE_INPUT_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)(密码|password|passwd|pin|验证码|captcha)"""),
        Regex("""(?i)(支付|付款|转账|transfer|payment)"""),
        Regex("""(?i)(删除|清空|卸载|delete|remove|uninstall|clear)""")
    )

    // ===== PII 检测（新增）=====
    val PHONE_CN: Regex = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    val IDCARD_CN: Regex = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
    val BANKCARD: Regex = Regex("""(?<!\d)\d{16,19}(?!\d)""")
    val EMAIL: Regex = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")

    // ===== Prompt Injection 检测（新增，英文 10 + 中文 10）=====
    val INJECTION_PATTERNS_EN: List<Pair<String, Regex>> = listOf(
        "INJ_EN_001" to Regex("""(?i)ignore\s+(all\s+)?(previous|prior|above)\s+(instructions?|prompts?|rules?)"""),
        "INJ_EN_002" to Regex("""(?i)disregard\s+(all\s+)?(previous|prior|above)"""),
        "INJ_EN_003" to Regex("""(?i)you\s+are\s+(now|actually)\s+(a|an)\s+"""),
        "INJ_EN_004" to Regex("""(?i)forget\s+(everything|all\s+(previous|prior))"""),
        "INJ_EN_005" to Regex("""(?i)system\s*prompt\s*:?"""),
        "INJ_EN_006" to Regex("""(?i)reveal\s+(your|the)\s+(system\s*prompt|instructions?|rules?)"""),
        "INJ_EN_007" to Regex("""(?i)new\s+instructions?\s*:"""),
        "INJ_EN_008" to Regex("""(?i)act\s+as\s+(if|though)\s+"""),
        "INJ_EN_009" to Regex("""(?i)jailbreak"""),
        "INJ_EN_010" to Regex("""(?i)do\s+not\s+follow\s+(any\s+)?rules?""")
    )

    val INJECTION_PATTERNS_ZH: List<Pair<String, Regex>> = listOf(
        "INJ_ZH_001" to Regex("""忽略(上述|以上|前面|之前)(所有)?(指令|提示|规则|约束)"""),
        "INJ_ZH_002" to Regex("""无视(上述|以上|前面|之前)(所有)?(指令|提示|规则)"""),
        "INJ_ZH_003" to Regex("""你(现在|其实|实际上)是(一个|名)?"""),
        "INJ_ZH_004" to Regex("""忘记(所有|之前|前面)(的)?(内容|指令|对话)"""),
        "INJ_ZH_005" to Regex("""(系统|初始)(提示|指令|prompt)\s*[：:]"""),
        "INJ_ZH_006" to Regex("""(显示|透露|告诉)(你的|系统)(系统提示|初始指令|规则|prompt)"""),
        "INJ_ZH_007" to Regex("""新(的)?指令\s*[：:]"""),
        "INJ_ZH_008" to Regex("""(假装|假设)你是"""),
        "INJ_ZH_009" to Regex("""越狱"""),
        "INJ_ZH_010" to Regex("""不要遵守(任何)?(规则|约束|限制)""")
    )

    val ALL_INJECTION_PATTERNS: List<Pair<String, Regex>> =
        INJECTION_PATTERNS_EN + INJECTION_PATTERNS_ZH

    // ===== 越狱检测（新增，10 个，聚焦角色替换和权限提升）=====
    val JAILBREAK_PATTERNS: List<Pair<String, Regex>> = listOf(
        "JB_001" to Regex("""(?i)(DAN|do\s+anything\s+now)"""),
        "JB_002" to Regex("""(?i)developer\s*mode"""),
        "JB_003" to Regex("""(?i)(god\s*mode|admin\s*mode|root\s*mode)"""),
        "JB_004" to Regex("""(?i)no\s+(restrictions?|limits?|rules?)"""),
        "JB_005" to Regex("""(?i)unrestricted\s+(mode|access)"""),
        "JB_006" to Regex("""(?i)pretend\s+(you\s+have\s+no|there\s+are\s+no)\s+(rules?|restrictions?)"""),
        "JB_007" to Regex("""开发者模式"""),
        "JB_008" to Regex("""(上帝|管理员|root|超级用户)模式"""),
        "JB_009" to Regex("""无(限制|约束|规则)模式"""),
        "JB_010" to Regex("""假装(你|你)没有(任何)?(规则|限制|约束)""")
    )

    // ===== 敏感金额模式 =====
    val AMOUNT_PATTERN: Regex = Regex("""\d+[.,]\d{2}""")
}
