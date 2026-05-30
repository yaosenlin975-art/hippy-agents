package com.lin.hippyagent.core.skill.store.provider

fun shellEscape(s: String): String {
    return "'${s.replace("'", "'\\''")}'"
}
