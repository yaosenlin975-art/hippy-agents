package com.lin.hippyagent.core.channel.qr

import androidx.compose.runtime.Immutable
import com.lin.hippyagent.core.channel.ChannelConfigStore
import com.lin.hippyagent.core.storage.SecureStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Immutable
sealed class QrAuthState {
    data object Idle : QrAuthState()
    data object Loading : QrAuthState()
    data class QrReady(val qrcodeBase64: String, val qrcodeText: String) : QrAuthState()
    data object Scanned : QrAuthState()
    data class Success(val credentials: Map<String, String>) : QrAuthState()
    data class Error(val message: String, val retryable: Boolean = true) : QrAuthState()
}

@Immutable
sealed class QrPollResult {
    data object Waiting : QrPollResult()
    data object Scanned : QrPollResult()
    data class Confirmed(val credentials: Map<String, String>) : QrPollResult()
    data object Expired : QrPollResult()
}

interface QrAuthProvider {
    val channelId: String
    suspend fun fetchQrcode(): Pair<String, String>
    suspend fun pollStatus(qrcodeText: String): QrPollResult
}

class QrAuthManager(
    private val provider: QrAuthProvider,
    private val secureStorage: SecureStorage,
    private val configStore: ChannelConfigStore,
    private val agentId: String
) {
    private val _state = MutableStateFlow<QrAuthState>(QrAuthState.Idle)
    val state: StateFlow<QrAuthState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun startQrLogin(scope: CoroutineScope) {
        pollJob?.cancel()
        _state.value = QrAuthState.Loading

        pollJob = scope.launch {
            try {
                val (qrcodeImg, qrcodeText) = provider.fetchQrcode()
                _state.value = QrAuthState.QrReady(qrcodeImg, qrcodeText)

                val startTime = System.currentTimeMillis()
                val maxPollDuration = 5 * 60 * 1000L

                while (coroutineContext.isActive) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > maxPollDuration) {
                        _state.value = QrAuthState.Error("二维码已过期，请重新获取")
                        break
                    }
                    when (val result = provider.pollStatus(qrcodeText)) {
                        is QrPollResult.Waiting -> delay(1500)
                        is QrPollResult.Scanned -> {
                            _state.value = QrAuthState.Scanned
                            delay(500)
                        }
                        is QrPollResult.Confirmed -> {
                            val creds = result.credentials
                            _state.value = QrAuthState.Success(creds)
                            saveCredentials(provider.channelId, creds)
                            break
                        }
                        is QrPollResult.Expired -> {
                            _state.value = QrAuthState.Error("二维码已过期，请重新获取")
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "QrAuth error for ${provider.channelId}")
                _state.value = QrAuthState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun saveCredentials(channelId: String, credentials: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val sensitiveKeys = setOf("botToken", "appSecret", "clientSecret", "secret")
            credentials.forEach { (key, value) ->
                if (value.isNotEmpty()) {
                    secureStorage.saveSecret("channel_${channelId}_$key", value)
                }
            }
            val nonSecretConfig = credentials.filterKeys { key ->
                key !in sensitiveKeys && credentials[key]?.isNotEmpty() == true
            }
            configStore.saveConfig(channelId, nonSecretConfig + mapOf(
                "_authMethod" to "qr_code",
                "_authTime" to System.currentTimeMillis().toString()
            ))
            Timber.i("Credentials saved for channel=$channelId agent=$agentId")
        }
    }

    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        _state.value = QrAuthState.Idle
    }
}
