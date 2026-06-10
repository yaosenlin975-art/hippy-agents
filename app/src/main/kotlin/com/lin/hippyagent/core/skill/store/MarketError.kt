package com.lin.hippyagent.core.skill.store

enum class MarketErrorType {
    AUTH,
    TIMEOUT,
    NETWORK,
    NOT_FOUND,
    CLI_NOT_INSTALLED,
    OTHER
}

data class MarketError(
    val type: MarketErrorType,
    val message: String
)
