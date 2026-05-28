package com.lin.hippyagent.core.memory

enum class QueryIntent { ENTITY, TEMPORAL, EVENT, GENERAL }
enum class RetrievalDetail { LOW, MEDIUM, HIGH }

object QueryIntentClassifier {
    private val temporalPatterns = listOf(
        Regex("""(?i)(last|past|previous|recent)\s+\d+\s*(day|week|month|hour|min)"""),
        Regex("""(?i)(yesterday|today|this\s+week|this\s+month)"""),
        Regex("""(上周|上周|最近|昨天|今天|前天|这周|这个月|上个月|之前|以前)"""),
        Regex("""(?i)\d{4}[-/]\d{1,2}[-/]\d{1,2}"""),
        Regex("""(\d+天前|\d+小时前|\d+分钟前|\d+周前|\d+个月前)""")
    )

    private val eventPatterns = listOf(
        Regex("""(?i)(how\s+did|what\s+happened|process\s+of|fix|debug|resolve|solve)"""),
        Regex("""(怎么|如何|过程|修复|解决|调试|发生了什么|怎么做的|怎么处理)"""),
        Regex("""(?i)(error|bug|issue|problem|crash|fail)"""),
        Regex("""(错误|问题|崩溃|失败|异常|bug)""")
    )

    private val entityPatterns = listOf(
        Regex("""(?i)(who\s+is|what\s+is|define|explain)\s+\w+"""),
        Regex("""(的|之)\s*[\w]+$"""),
        Regex("""(?i)^[\w\s]+(系统|架构|模块|框架|库|工具|方法|原理)$"""),
        Regex("""(什么是|是谁|介绍|解释|说明).+""")
    )

    fun classify(query: String): QueryIntent {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return QueryIntent.GENERAL

        for (pattern in temporalPatterns) {
            if (pattern.containsMatchIn(trimmed)) return QueryIntent.TEMPORAL
        }

        for (pattern in eventPatterns) {
            if (pattern.containsMatchIn(trimmed)) return QueryIntent.EVENT
        }

        for (pattern in entityPatterns) {
            if (pattern.containsMatchIn(trimmed)) return QueryIntent.ENTITY
        }

        return QueryIntent.GENERAL
    }

    fun toDetailLevel(intent: QueryIntent): RetrievalDetail = when (intent) {
        QueryIntent.ENTITY -> RetrievalDetail.LOW
        QueryIntent.TEMPORAL -> RetrievalDetail.HIGH
        QueryIntent.EVENT -> RetrievalDetail.HIGH
        QueryIntent.GENERAL -> RetrievalDetail.MEDIUM
    }

    fun autoDetectDetail(query: String): RetrievalDetail = toDetailLevel(classify(query))
}

