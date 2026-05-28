package com.lin.hippyagent.core.model

import com.lin.hippyagent.core.pool.FastId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.time.Instant

@Serializable
data class AuthProfile(
    val id: String = FastId.next(),
    val name: String,
    val providerId: String,
    val model: String? = null,
    val apiKey: String = "",
    val baseUrl: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val usage: ProfileUsage = ProfileUsage(),
    val cooldown: ProfileCooldown = ProfileCooldown()
)

@Serializable
data class ProfileUsage(
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCalls: Long = 0,
    val lastUsedAt: Long? = null,
    val dailyCalls: Int = 0,
    val dailyResetAt: Long? = null
)

@Serializable
data class ProfileCooldown(
    val isCoolingDown: Boolean = false,
    val cooldownUntil: Long? = null,
    val cooldownReason: String? = null
)

class AuthProfileManager(
    private val modelProviderStore: ModelProviderStore,
    private val secureStorage: com.lin.hippyagent.core.storage.SecureStorage
) {
    private val _profiles = MutableStateFlow<List<AuthProfile>>(emptyList())
    val profiles: StateFlow<List<AuthProfile>> = _profiles.asStateFlow()

    suspend fun loadProfiles() {
        val providerList = modelProviderStore.providers.first()
        val loaded = mutableListOf<AuthProfile>()
        for (provider in providerList) {
            val apiKey = secureStorage.getApiKey(provider.id) ?: provider.apiKey
            if (apiKey.isNotBlank()) {
                loaded.add(
                    AuthProfile(
                        name = provider.name,
                        providerId = provider.id,
                        apiKey = apiKey,
                        baseUrl = provider.baseUrl,
                        priority = if (provider.isDefault) 0 else 1,
                        enabled = provider.enabled
                    )
                )
            } else if (provider.models.any { it.free }) {
                loaded.add(
                    AuthProfile(
                        name = provider.name,
                        providerId = provider.id,
                        apiKey = "",
                        baseUrl = provider.baseUrl,
                        priority = if (provider.isDefault) 0 else 1,
                        enabled = provider.enabled
                    )
                )
            }
        }
        _profiles.value = loaded
    }

    fun addProfile(profile: AuthProfile) {
        _profiles.update { (it + profile).sortedBy { p -> p.priority } }
        persistApiKey(profile)
        Timber.i("AuthProfile added: ${profile.name} for provider ${profile.providerId}")
    }

    fun removeProfile(profileId: String) {
        _profiles.update { it.filter { p -> p.id != profileId } }
        Timber.i("AuthProfile removed: $profileId")
    }

    fun updateProfile(profile: AuthProfile) {
        _profiles.update { current ->
            current.map { if (it.id == profile.id) profile else it }
        }
        persistApiKey(profile)
        Timber.i("AuthProfile updated: ${profile.name}")
    }

    fun getAvailableProfiles(providerId: String): List<AuthProfile> {
        val now = System.currentTimeMillis()
        return _profiles.value
            .filter { it.providerId == providerId && it.enabled }
            .filter { !it.cooldown.isCoolingDown || (it.cooldown.cooldownUntil ?: 0) <= now }
            .sortedBy { it.priority }
    }

    fun getNextAvailableProfile(providerId: String, excludeProfileId: String? = null): AuthProfile? {
        return getAvailableProfiles(providerId)
            .firstOrNull { it.id != excludeProfileId }
    }

    fun markCooldown(profileId: String, reason: String, cooldownSeconds: Int = 60) {
        _profiles.update { current ->
            current.map { profile ->
                if (profile.id == profileId) {
                    profile.copy(
                        cooldown = ProfileCooldown(
                            isCoolingDown = true,
                            cooldownUntil = System.currentTimeMillis() + cooldownSeconds * 1000L,
                            cooldownReason = reason
                        )
                    )
                } else profile
            }
        }
        Timber.w("AuthProfile $profileId cooling down for ${cooldownSeconds}s: $reason")
    }

    fun clearCooldown(profileId: String) {
        _profiles.update { current ->
            current.map { profile ->
                if (profile.id == profileId) {
                    profile.copy(cooldown = ProfileCooldown())
                } else profile
            }
        }
    }

    fun recordUsage(profileId: String, inputTokens: Long, outputTokens: Long) {
        _profiles.update { current ->
            current.map { profile ->
                if (profile.id == profileId) {
                    val now = System.currentTimeMillis()
                    val dailyReset = profile.usage.dailyResetAt ?: 0L
                    val isSameDay = (now - dailyReset) < 86_400_000L
                    val dailyCalls = if (isSameDay) profile.usage.dailyCalls + 1 else 1
                    profile.copy(
                        usage = profile.usage.copy(
                            totalInputTokens = profile.usage.totalInputTokens + inputTokens,
                            totalOutputTokens = profile.usage.totalOutputTokens + outputTokens,
                            totalCalls = profile.usage.totalCalls + 1,
                            lastUsedAt = now,
                            dailyCalls = dailyCalls,
                            dailyResetAt = if (isSameDay) dailyReset else now
                        )
                    )
                } else profile
            }
        }
    }

    private fun persistApiKey(profile: AuthProfile) {
        if (profile.apiKey.isNotBlank()) {
            secureStorage.saveApiKey(profile.providerId, profile.apiKey)
        }
    }
}

