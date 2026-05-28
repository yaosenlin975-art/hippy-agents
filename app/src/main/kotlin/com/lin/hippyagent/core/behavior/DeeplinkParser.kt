package com.lin.hippyagent.core.behavior

object DeeplinkParser {

    data class CapturedIntent(
        val action: String?,
        val dataUri: String?,
        val component: String?,
        val extras: Map<String, String>
    )

    fun parseFromDumpsys(dumpsysOutput: String, packageName: String): CapturedIntent? {
        val section = extractActivitySection(dumpsysOutput, packageName) ?: return null
        val action = extractValue(section, "Action") ?: "android.intent.action.MAIN"
        val data = extractValue(section, "Data")
        val component = extractValue(section, "Component") ?: extractValue(section, "cmp")
        val extras = extractExtras(section)
        return CapturedIntent(action, data, component, extras)
    }

    fun buildAmCommand(intent: CapturedIntent): String = buildString {
        append("am start")
        if (intent.action != null) append(" -a ${intent.action}")
        if (intent.dataUri != null) append(" -d ${intent.dataUri}")
        if (intent.component != null) append(" -n ${intent.component}")
        for ((key, value) in intent.extras) {
            append(" --es \"$key\" \"$value\"")
        }
    }

    private fun extractActivitySection(output: String, packageName: String): String? {
        val regex = Regex(Regex.escape(packageName), RegexOption.IGNORE_CASE)
        val lines = output.lines()
        for ((index, line) in lines.withIndex()) {
            if (regex.containsMatchIn(line)) {
                val end = minOf(index + 30, lines.size)
                return lines.subList(index, end).joinToString("\n")
            }
        }
        return null
    }

    private val ACTION_REGEX = Regex("""Action:\s*"?([^"\n]+)"?""")
    private val DATA_REGEX = Regex("""Data:\s*([^ \n]+)""")
    private val COMPONENT_REGEX = Regex("""(?:Component|cmp):\s*([^ \n]+)""")
    private val EXTRA_REGEX = Regex("""(?:Extra|ei|es|eb):\s*(\w+)=([^\n]+)""")

    private fun extractValue(section: String, key: String): String? {
        val regex = when (key) {
            "Action" -> ACTION_REGEX
            "Data" -> DATA_REGEX
            "Component", "cmp" -> COMPONENT_REGEX
            else -> return null
        }
        return regex.find(section)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractExtras(section: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (match in EXTRA_REGEX.findAll(section)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim().removeSurrounding("\"")
            result[key] = value
        }
        return result
    }
}
