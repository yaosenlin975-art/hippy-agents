package com.lin.hippyagent.core.channel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 渠道健康检查与热重启服务
 *
 * SPEC §2.24: 渠道健康检查端点 + 渠道热重启
 * 提供统一 API，可在 UI 中展示或被外部系统集成。
 */
class ChannelHealthService(
    private val channelManager: ChannelManager
) {
    /**
     * 检查所有渠道健康状态
     * @return 渠道 ID → 健康状态映射
     */
    suspend fun checkAllHealth(): Map<String, ChannelHealthStatus> = withContext(Dispatchers.IO) {
        val statuses = channelManager.checkAllHealth()
        statuses.associateBy { it.channelId }
    }

    /**
     * 检查单个渠道健康状态
     */
    suspend fun checkHealth(channelId: String): ChannelHealthStatus? = withContext(Dispatchers.IO) {
        channelManager.checkHealth(channelId)
    }

    /**
     * 热重启指定渠道
     * 流程：disconnect → connect → healthCheck
     */
    suspend fun restartChannel(channelId: String): Result<ChannelHealthStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val channel = channelManager.getChannel(channelId)
                ?: throw IllegalArgumentException("Channel not found: $channelId")

            Timber.i("Restarting channel: $channelId")

            // 断开
            channel.disconnect().onFailure { e ->
                Timber.w(e, "Disconnect failed for channel $channelId (non-fatal)")
            }

            // 重连
            channel.connect().getOrElse { e ->
                throw RuntimeException("Reconnect failed for channel $channelId: ${e.message}", e)
            }

            // 验证健康状态
            val health = channel.healthCheck()
            Timber.i("Channel $channelId restarted, healthy=${health.isHealthy}")
            health
        }
    }

    /**
     * 热重启所有渠道
     * @return 渠道 ID → 重启结果
     */
    suspend fun restartAllChannels(): Map<String, Result<ChannelHealthStatus>> = withContext(Dispatchers.IO) {
        val channels = channelManager.getAllChannels()
        channels.associate { channel ->
            channel.id to restartChannel(channel.id)
        }
    }

    /**
     * 获取所有渠道的健康摘要
     */
    suspend fun getHealthSummary(): HealthSummary = withContext(Dispatchers.IO) {
        val healthMap = checkAllHealth()
        val total = healthMap.size
        val healthy = healthMap.values.count { it.isHealthy }
        val unhealthy = total - healthy

        HealthSummary(
            totalChannels = total,
            healthyChannels = healthy,
            unhealthyChannels = unhealthy,
            details = healthMap
        )
    }
}

data class HealthSummary(
    val totalChannels: Int,
    val healthyChannels: Int,
    val unhealthyChannels: Int,
    val details: Map<String, ChannelHealthStatus>
) {
    val isAllHealthy: Boolean get() = unhealthyChannels == 0 && totalChannels > 0
}

