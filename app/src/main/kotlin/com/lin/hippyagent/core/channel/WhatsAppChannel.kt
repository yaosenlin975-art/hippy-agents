package com.lin.hippyagent.core.channel

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

class WhatsAppChannel(
    private val context: Context
) : Channel {
    override val id = "whatsapp"
    override val name = "WhatsApp"
    override var isEnabled = false

    private val _messageFlow = MutableSharedFlow<ChannelMessage>()

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> {
        return runCatching {
            val phoneNumber = message.metadata["phone"]
            if (phoneNumber != null) {
                val uri = Uri.parse("https://wa.me/$phoneNumber?text=${Uri.encode(message.content)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    `package` = "com.whatsapp"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Timber.d("WhatsApp message sent to $phoneNumber")
            } else {
                Timber.w("WhatsApp phone number not provided")
            }
        }
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> {
        return runCatching {
            val pm = context.packageManager
            try {
                pm.getPackageInfo("com.whatsapp", 0)
                isEnabled = true
                Timber.d("WhatsApp connected")
            } catch (e: Exception) {
                Timber.w("WhatsApp not installed")
                isEnabled = false
            }
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        isEnabled = false
        return Result.success(Unit)
    }

    override suspend fun isConnected(): Boolean = isEnabled

    override suspend fun healthCheck(): ChannelHealthStatus {
        return ChannelHealthStatus(
            channelId = id,
            isHealthy = isEnabled,
            latencyMs = 0L,
            error = if (isEnabled) null else "WhatsApp not installed"
        )
    }

    override suspend fun restart(): Result<Unit> {
        disconnect()
        return connect()
    }
}

