package com.lin.hippyagent.core.auth

import android.content.Context
import android.util.Base64
import com.lin.hippyagent.data.repository.AgentRepository
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class AuthToken(
    val token: String,
    val jti: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val permissions: List<String> = emptyList()
)

data class LocalApiConfig(
    val port: Int = 8080,
    val enabled: Boolean = false,
    val requireAuth: Boolean = true,
    val allowedOrigins: List<String> = listOf("*")
)

class AuthManager(
    private val context: Context
) {
    private val tokens = mutableMapOf<String, AuthToken>()
    private val revokedJtis = java.util.LinkedHashSet<String>()

    fun generateToken(permissions: List<String> = listOf("read", "write")): AuthToken {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val tokenString = "apaw_" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        val token = AuthToken(
            token = tokenString,
            permissions = permissions
        )
        tokens[tokenString] = token

        Timber.i("Auth token generated with permissions: $permissions")
        return token
    }

    fun validateToken(tokenString: String): Boolean {
        val token = tokens[tokenString] ?: return false

        if (token.jti in revokedJtis) {
            tokens.remove(tokenString)
            return false
        }

        if (token.expiresAt != null && token.expiresAt < System.currentTimeMillis()) {
            tokens.remove(tokenString)
            return false
        }

        return true
    }

    fun getTokenPermissions(tokenString: String): List<String> {
        return tokens[tokenString]?.permissions ?: emptyList()
    }

    fun revokeToken(tokenString: String) {
        val token = tokens.remove(tokenString)
        token?.let {
            revokedJtis.add(it.jti)
            if (revokedJtis.size > 10000) {
                val iterator = revokedJtis.iterator()
                repeat(5000) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
            }
        }
        Timber.i("Auth token revoked")
    }

    fun listTokens(): List<AuthToken> = tokens.values.toList()

    fun hasPermission(tokenString: String, permission: String): Boolean {
        val token = tokens[tokenString] ?: return false
        return "admin" in token.permissions || permission in token.permissions
    }

    /**
     * Hash password with salt using SHA-256.
     */
    fun hashPassword(password: String, salt: String = generateSalt()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("$salt:$password".toByteArray())
        return "$salt:${Base64.encodeToString(hash, Base64.NO_WRAP)}"
    }

    fun verifyPassword(password: String, hashed: String): Boolean {
        val parts = hashed.split(":", limit = 2)
        if (parts.size != 2) return false
        val salt = parts[0]
        return hashPassword(password, salt) == hashed
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

class LocalApiServer(
    private val context: Context,
    private val config: LocalApiConfig,
    private val authManager: AuthManager,
    private val agentRepository: AgentRepository? = null,
    private val channelManager: com.lin.hippyagent.core.channel.ChannelManager? = null,
    private val agentStatsManager: com.lin.hippyagent.core.stats.AgentStatsManager? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private var httpServer: NanoHTTPD? = null
    private var isRunning = false

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isRunning || !config.enabled) return@runCatching

            httpServer = object : NanoHTTPD(config.port) {
                override fun serve(session: IHTTPSession): Response {
                    val uri = session.uri
                    val method = session.method

                    // Auth check
                    if (config.requireAuth && uri != "/api/v1/health") {
                        val authHeader = session.headers["authorization"] ?: ""
                        val token = authHeader.removePrefix("Bearer ").trim()
                        if (token.isEmpty() || !authManager.validateToken(token)) {
                            return NanoHTTPD.newFixedLengthResponse(
                                Response.Status.UNAUTHORIZED, "application/json",
                                """{"error":"Unauthorized"}"""
                            )
                        }
                    }

                    return when {
                        // Health
                        uri == "/api/v1/health" && method == Method.GET ->
                            handleHealth()

                        // Agents CRUD
                        uri == "/api/v1/agents" && method == Method.GET ->
                            handleListAgents()
                        uri == "/api/v1/agents" && method == Method.POST ->
                            handleCreateAgent(session)
                        uri.matches(Regex("^/api/v1/agents/[^/]+$")) && method == Method.GET ->
                            handleGetAgent(uri)
                        uri.matches(Regex("^/api/v1/agents/[^/]+$")) && method == Method.PUT ->
                            handleUpdateAgent(uri, session)
                        uri.matches(Regex("^/api/v1/agents/[^/]+$")) && method == Method.DELETE ->
                            handleDeleteAgent(uri)

                        // Chat
                        uri == "/api/v1/chat" && method == Method.POST ->
                            handleChat(session)

                        // Tools
                        uri == "/api/v1/tools" && method == Method.GET ->
                            handleListTools()

                        // Token usage
                        uri == "/api/v1/token-usage" && method == Method.GET ->
                            handleTokenUsage()

                        // Channel health check
                        uri.matches(Regex("^/api/v1/agents/[^/]+/channels/health$")) && method == Method.GET ->
                            handleChannelHealth(uri)

                        // Channel restart
                        uri.matches(Regex("^/api/v1/agents/[^/]+/channels/restart$")) && method == Method.POST ->
                            handleChannelRestart(uri)

                        // Agent stats
                        uri == "/api/v1/agent-stats" && method == Method.GET ->
                            handleAgentStats(session)
                        uri == "/api/v1/agent-stats/sessions" && method == Method.GET ->
                            handleAgentSessionStats()
                        uri == "/api/v1/agent-stats/messages" && method == Method.GET ->
                            handleAgentMessageStats()
                        uri == "/api/v1/agent-stats/trends" && method == Method.GET ->
                            handleAgentTrends(session)

                        else ->
                            NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                                """{"error":"Not Found"}""")
                    }
                }
            }
            httpServer?.start()
            isRunning = true
            Timber.i("Local API server started on port ${config.port}")
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isRunning) return@runCatching
            httpServer?.stop()
            httpServer = null
            isRunning = false
            Timber.i("Local API server stopped")
        }
    }

    fun isRunning(): Boolean = isRunning

    fun getBaseUrl(): String = "http://localhost:${config.port}"

    // --- Route handlers ---

    private fun handleHealth(): Response {
        val body = buildJsonObject {
            put("status", JsonPrimitive("ok"))
            put("version", JsonPrimitive("1.1.3"))
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
    }

    private fun handleListAgents(): Response {
        return runBlocking(Dispatchers.IO) {
            val profiles = agentRepository?.loadAgentProfiles()?.first() ?: emptyMap()
            val agentsJson = profiles.values.joinToString(",") { profile ->
                buildJsonObject {
                    put("id", JsonPrimitive(profile.agentId))
                    put("name", JsonPrimitive(profile.name))
                    put("enabled", JsonPrimitive(profile.enabled))
                    put("model", JsonPrimitive(profile.modelName))
                    put("provider", JsonPrimitive(profile.modelProvider))
                }.toString()
            }
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"agents":[$agentsJson]}""")
        }
    }

    private fun handleGetAgent(uri: String): Response {
        val agentId = uri.substringAfterLast("/")
        return runBlocking(Dispatchers.IO) {
            val profiles = agentRepository?.loadAgentProfiles()?.first() ?: emptyMap()
            val profile = profiles[agentId]
            if (profile == null) {
                NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    """{"error":"Agent not found: $agentId"}""")
            } else {
                val body = buildJsonObject {
                    put("id", JsonPrimitive(profile.agentId))
                    put("name", JsonPrimitive(profile.name))
                    put("enabled", JsonPrimitive(profile.enabled))
                    put("model", JsonPrimitive(profile.modelName))
                    put("provider", JsonPrimitive(profile.modelProvider))
                    put("isDefault", JsonPrimitive(profile.isDefault))
                }
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
            }
        }
    }

    private fun handleCreateAgent(session: IHTTPSession): Response {
        // Stub: full implementation requires parsing POST body
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"message":"Agent creation via API - use app UI for full configuration"}""")
    }

    private fun handleUpdateAgent(uri: String, session: IHTTPSession): Response {
        val agentId = uri.substringAfterLast("/")
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"message":"Agent $agentId update - use app UI for full configuration"}""")
    }

    private fun handleDeleteAgent(uri: String): Response {
        val agentId = uri.substringAfterLast("/")
        return runBlocking(Dispatchers.IO) {
            agentRepository?.deleteAgentProfile(agentId)
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"deleted":"$agentId"}""")
        }
    }

    private fun handleChat(session: IHTTPSession): Response {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"message":"Chat via API - use Console channel for full interaction"}""")
    }

    private fun handleListTools(): Response {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"tools":["read_file","write_file","edit_file","glob_search","grep_search",
              "list_directory","execute_shell","get_working_directory","get_environment",
              "git_status","git_add","git_commit","git_push","git_pull","git_branch",
              "git_log","git_diff","git_clone","gradle_build","gradle_test","gradle_clean",
              "gradle_tasks","get_current_time","take_photo","record_video","take_screenshot",
              "read_calendar","write_calendar","make_call","read_call_log","get_wifi_info",
              "bluetooth_control","get_volume","set_volume","start_recording","read_sensor",
              "vibrate","get_screen_info","notification_read","notification_reply",
              "contact_list","contact_search","sms_list","sms_send","media_control",
              "search_media","get_current_location","set_alarm","list_apps","launch_app",
              "read_clipboard","write_clipboard","get_system_info","send_file_to_user",
              "get_token_usage"]}""")
    }

    private fun handleTokenUsage(): Response {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"totalInputTokens":0,"totalOutputTokens":0,"totalCalls":0,"byModel":[]}""")
    }

    private fun handleChannelHealth(uri: String): Response {
        return runBlocking(Dispatchers.IO) {
            val healthStatuses = channelManager?.checkAllHealth() ?: emptyList()
            val channelsJson = healthStatuses.joinToString(",") { status ->
                buildJsonObject {
                    put("channelId", JsonPrimitive(status.channelId))
                    put("isHealthy", JsonPrimitive(status.isHealthy))
                    put("lastActivityTime", JsonPrimitive(status.lastActivityTime))
                    status.latencyMs?.let { put("latencyMs", JsonPrimitive(it)) }
                    status.error?.let { put("error", JsonPrimitive(it)) }
                }.toString()
            }
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"channels":[$channelsJson]}""")
        }
    }

    private fun handleChannelRestart(uri: String): Response {
        return runBlocking(Dispatchers.IO) {
            val results = channelManager?.restartAllChannels() ?: emptyMap()
            val resultsJson = results.entries.joinToString(",") { (id, result) ->
                buildJsonObject {
                    put("channelId", JsonPrimitive(id))
                    put("success", JsonPrimitive(result.isSuccess))
                    result.exceptionOrNull()?.message?.let { put("error", JsonPrimitive(it)) }
                }.toString()
            }
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"results":[$resultsJson]}""")
        }
    }

    private fun handleAgentStats(session: IHTTPSession): Response {
        return runBlocking(Dispatchers.IO) {
            val params = session.parms ?: emptyMap()
            val agentId = params["agentId"]
            val stats = agentStatsManager?.getStats(agentId = agentId)?.getOrDefault(
                com.lin.hippyagent.core.stats.DetailedAgentStats(0, 0, 0, 0, 0, 0, 0, 0)
            ) ?: com.lin.hippyagent.core.stats.DetailedAgentStats(0, 0, 0, 0, 0, 0, 0, 0)
            val body = buildJsonObject {
                put("totalSessions", JsonPrimitive(stats.totalSessions))
                put("totalMessages", JsonPrimitive(stats.totalMessages))
                put("userMessages", JsonPrimitive(stats.userMessages))
                put("agentMessages", JsonPrimitive(stats.agentMessages))
                put("inputTokens", JsonPrimitive(stats.inputTokens))
                put("outputTokens", JsonPrimitive(stats.outputTokens))
                put("llmCalls", JsonPrimitive(stats.llmCalls))
                put("toolCalls", JsonPrimitive(stats.toolCalls))
            }
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
        }
    }

    private fun handleAgentSessionStats(): Response {
        return runBlocking(Dispatchers.IO) {
            val channelStats = agentStatsManager?.getSessionChannelStats()?.getOrDefault(emptyList()) ?: emptyList()
            val statsJson = channelStats.joinToString(",") { stat ->
                buildJsonObject {
                    put("channelId", JsonPrimitive(stat.channelId))
                    put("sessionCount", JsonPrimitive(stat.sessionCount))
                }.toString()
            }
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"stats":[$statsJson]}""")
        }
    }

    private fun handleAgentMessageStats(): Response {
        return runBlocking(Dispatchers.IO) {
            val channelStats = agentStatsManager?.getMessageChannelStats()?.getOrDefault(emptyList()) ?: emptyList()
            val statsJson = channelStats.joinToString(",") { stat ->
                buildJsonObject {
                    put("channelId", JsonPrimitive(stat.channelId))
                    put("messageCount", JsonPrimitive(stat.messageCount))
                }.toString()
            }
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"stats":[$statsJson]}""")
        }
    }

    private fun handleAgentTrends(session: IHTTPSession): Response {
        return runBlocking(Dispatchers.IO) {
            val params = session.parms ?: emptyMap()
            val agentId = params["agentId"]
            val days = params["days"]?.toIntOrNull() ?: 30
            val trends = agentStatsManager?.getTrends(agentId = agentId, days = days)?.getOrDefault(
                com.lin.hippyagent.core.stats.TrendData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            ) ?: com.lin.hippyagent.core.stats.TrendData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            val body = buildJsonObject {
                put("userMessages", JsonPrimitive(trends.userMessages.map { "${it.date}:${it.value}" }.joinToString(",")))
                put("agentMessages", JsonPrimitive(trends.agentMessages.map { "${it.date}:${it.value}" }.joinToString(",")))
                put("sessions", JsonPrimitive(trends.sessions.map { "${it.date}:${it.value}" }.joinToString(",")))
                put("inputTokens", JsonPrimitive(trends.inputTokens.map { "${it.date}:${it.value}" }.joinToString(",")))
                put("outputTokens", JsonPrimitive(trends.outputTokens.map { "${it.date}:${it.value}" }.joinToString(",")))
                put("llmCalls", JsonPrimitive(trends.llmCalls.map { "${it.date}:${it.value}" }.joinToString(",")))
                put("toolCalls", JsonPrimitive(trends.toolCalls.map { "${it.date}:${it.value}" }.joinToString(",")))
            }
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
        }
    }
}

