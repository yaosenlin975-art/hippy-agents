package com.lin.hippyagent.core.skill.store.provider

sealed class LobeHubException(message: String) : Exception(message)

class LobeHubAuthException(message: String) : LobeHubException(message)

class LobeHubTimeoutException(message: String) : LobeHubException(message)

class LobeHubNetworkException(message: String) : LobeHubException(message)

class LobeHubNotFoundException(message: String) : LobeHubException(message)

class LobeHubOtherException(message: String) : LobeHubException(message)
