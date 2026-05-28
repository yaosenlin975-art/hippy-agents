package com.lin.hippyagent.core.skill

import timber.log.Timber

class SkillScanner {
    companion object {
        private val promptInjectionPatterns = listOf(
            Regex("""(?i)ignore\s+previous\s+instructions"""),
            Regex("""(?i)bypass\s+safety"""),
            Regex("""(?i)override\s+system\s+prompt"""),
            Regex("""(?i)disable\s+security"""),
            Regex("""(?i)pretend\s+to\s+be"""),
            Regex("""(?i)act\s+as\s+(?!a\s+skill)"""),
            Regex("""(?i)you\s+are\s+now\s+"""),
        )
        private val commandInjectionPatterns = listOf(
            Regex("""[`$]\([^)]*\)"""),
            Regex("""(?i)exec\s*\(""") ,
            Regex("""(?i)eval\s*\(""") ,
            Regex("""(?i)system\s*\(""") ,
            Regex("""\|\s*(rm|del|format|mkfs)\s"""),
            Regex("""(?i)chmod\s+777"""),
        )
        private val hardcodedSecretPatterns = listOf(
            Regex("""(?i)(api_key|apikey|secret|token|password)\s*=\s*["'][^"']{8,}["']"""),
            Regex("""(?i)(sk-[a-zA-Z0-9]{20,})"""),
            Regex("""(?i)(ghp_[a-zA-Z0-9]{36})"""),
            Regex("""(?i)(AKIA[0-9A-Z]{16})"""),
        )
        private val dataExfiltrationPatterns = listOf(
            Regex("""(?i)https?://[^\s]+\.(webhook|requestbin|ngrok)"""),
            Regex("""(?i)fetch\s*\(\s*["']https?://"""),
            Regex("""(?i)curl\s+https?://"""),
            Regex("""(?i)send\s+data\s+to"""),
            Regex("""(?i)upload\s+to"""),
            Regex("""(?i)exfiltrate"""),
        )
    }

    fun scanForSecurityIssues(skillContent: String): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()

        issues.addAll(checkPromptInjection(skillContent))
        issues.addAll(checkCommandInjection(skillContent))
        issues.addAll(checkHardcodedSecrets(skillContent))
        issues.addAll(checkDataExfiltration(skillContent))

        return issues
    }

    private fun checkPromptInjection(content: String): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()

        promptInjectionPatterns.forEach { pattern ->
            pattern.find(content)?.let { match ->
                issues.add(
                    SecurityIssue(
                        type = "prompt_injection",
                        severity = "HIGH",
                        line = getLineNumber(content, match.range.first),
                        description = "Potential prompt injection detected: '${match.value.substring(0, 50)}...'"
                    )
                )
            }
        }

        return issues
    }

    private fun checkCommandInjection(content: String): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()

        commandInjectionPatterns.forEach { pattern ->
            pattern.find(content)?.let { match ->
                issues.add(
                    SecurityIssue(
                        type = "command_injection",
                        severity = "HIGH",
                        line = getLineNumber(content, match.range.first),
                        description = "Potential command injection: '${match.value.substring(0, 50)}...'"
                    )
                )
            }
        }

        return issues
    }

    private fun checkHardcodedSecrets(content: String): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()

        hardcodedSecretPatterns.forEach { pattern ->
            pattern.find(content)?.let { match ->
                issues.add(
                    SecurityIssue(
                        type = "hardcoded_secret",
                        severity = "MEDIUM",
                        line = getLineNumber(content, match.range.first),
                        description = "Potential hardcoded secret detected: '${match.value.substring(0, 30)}...'"
                    )
                )
            }
        }

        return issues
    }

    private fun checkDataExfiltration(content: String): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()

        dataExfiltrationPatterns.forEach { pattern ->
            pattern.find(content)?.let { match ->
                issues.add(
                    SecurityIssue(
                        type = "data_exfiltration",
                        severity = "HIGH",
                        line = getLineNumber(content, match.range.first),
                        description = "Potential data exfiltration path: '${match.value.substring(0, 50)}...'"
                    )
                )
            }
        }

        return issues
    }

    private fun getLineNumber(content: String, charIndex: Int): Int {
        return content.substring(0, charIndex).count { it == '\n' } + 1
    }
}

data class SecurityIssue(
    val type: String,
    val severity: String,
    val line: Int,
    val description: String
)

