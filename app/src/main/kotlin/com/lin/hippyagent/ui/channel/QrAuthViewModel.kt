package com.lin.hippyagent.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.channel.ChannelConfigStore
import com.lin.hippyagent.core.channel.qr.QrAuthManager
import com.lin.hippyagent.core.channel.qr.QrAuthProvider
import com.lin.hippyagent.core.channel.qr.QrAuthState
import com.lin.hippyagent.core.storage.SecureStorage
import kotlinx.coroutines.flow.StateFlow

class QrAuthViewModel(
    provider: QrAuthProvider,
    secureStorage: SecureStorage,
    configStore: ChannelConfigStore,
    agentId: String
) : ViewModel() {
    private val qrAuthManager = QrAuthManager(provider, secureStorage, configStore, agentId)
    val state: StateFlow<QrAuthState> = qrAuthManager.state

    fun startQrLogin() {
        qrAuthManager.startQrLogin(viewModelScope)
    }

    fun cancel() {
        qrAuthManager.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        qrAuthManager.cancel()
    }
}
