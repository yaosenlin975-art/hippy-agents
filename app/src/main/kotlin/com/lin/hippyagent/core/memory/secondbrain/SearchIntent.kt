package com.lin.hippyagent.core.memory.commonmemory

enum class SearchIntent {
    PRECISE,
    RECENT,
    BALANCED,
    BROAD
}

fun com.lin.hippyagent.core.memory.QueryIntent.toSearchIntent(): SearchIntent = when (this) {
    com.lin.hippyagent.core.memory.QueryIntent.ENTITY -> SearchIntent.PRECISE
    com.lin.hippyagent.core.memory.QueryIntent.TEMPORAL -> SearchIntent.RECENT
    com.lin.hippyagent.core.memory.QueryIntent.EVENT -> SearchIntent.BALANCED
    com.lin.hippyagent.core.memory.QueryIntent.GENERAL -> SearchIntent.BROAD
}
