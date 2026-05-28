package com.lin.hippyagent.core.agent.collaboration

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 远程 ACP 服务器配置
 */
@Serializable
data class AcpRemoteServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 8090,
    val enabled: Boolean = true,
    val discoveredAgents: List<DiscoveredAgent> = emptyList(),
    val lastDiscoveryAt: Long = 0
)

@Serializable
data class DiscoveredAgent(
    val agentId: String,
    val name: String,
    val enabled: Boolean = true
)

/**
 * ACP 客户端配置管理 — 持久化远程服务器列表
 */
class AcpClientStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("acp_client_config", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun getServers(): List<AcpRemoteServer> {
        val raw = prefs.getString("servers", "[]") ?: "[]"
        return try {
            json.decodeFromString<List<AcpRemoteServer>>(raw)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse ACP client config")
            emptyList()
        }
    }

    fun saveServers(servers: List<AcpRemoteServer>) {
        val jsonArray = kotlinx.serialization.json.buildJsonArray {
            servers.forEach { add(Json.encodeToJsonElement(AcpRemoteServer.serializer(), it)) }
        }
        prefs.edit().putString("servers", jsonArray.toString()).apply()
    }

    fun addServer(server: AcpRemoteServer) {
        val servers = getServers().toMutableList()
        servers.add(server)
        saveServers(servers)
    }

    fun updateServer(server: AcpRemoteServer) {
        val servers = getServers().toMutableList()
        val idx = servers.indexOfFirst { it.id == server.id }
        if (idx >= 0) {
            servers[idx] = server
            saveServers(servers)
        }
    }

    fun removeServer(serverId: String) {
        val servers = getServers().filter { it.id != serverId }
        saveServers(servers)
    }

    fun getEnabledServers(): List<AcpRemoteServer> {
        return getServers().filter { it.enabled }
    }

    fun getServerByHostPort(host: String, port: Int): AcpRemoteServer? {
        return getServers().find { it.host == host && it.port == port }
    }
}

