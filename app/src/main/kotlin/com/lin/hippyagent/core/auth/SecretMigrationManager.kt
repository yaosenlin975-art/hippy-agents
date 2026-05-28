package com.lin.hippyagent.core.auth

import android.content.Context
import com.lin.hippyagent.core.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Handles migration of plaintext secrets (auth.json) to encrypted storage.
 */
class SecretMigrationManager(
    private val context: Context,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val PREFS_NAME = "secret_migration"
        private const val KEY_MIGRATED = "auth_json_migrated"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun migrateIfNeeded(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.getBoolean(KEY_MIGRATED, false)) return@runCatching false

            val authFile = File(context.filesDir, "secret/auth.json")
            if (!authFile.exists()) {
                prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
                return@runCatching false
            }

            Timber.i("Starting secret migration from auth.json")
            val content = authFile.readText()

            try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                val obj = json.parseToJsonElement(content) as? kotlinx.serialization.json.JsonObject
                if (obj == null) {
                    prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
                    return@runCatching false
                }

                // Migrate API keys
                val apiKeys = obj["api_keys"] as? kotlinx.serialization.json.JsonObject
                apiKeys?.forEach { (providerId, keyElement) ->
                    val apiKey = (keyElement as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (!apiKey.isNullOrBlank() && !secureStorage.hasApiKey(providerId)) {
                        secureStorage.saveApiKey(providerId, apiKey)
                        Timber.d("Migrated API key for: $providerId")
                    }
                }

                // Migrate tokens
                val tokens = obj["tokens"] as? kotlinx.serialization.json.JsonObject
                tokens?.forEach { (tokenKey, tokenElement) ->
                    val token = (tokenElement as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (!token.isNullOrBlank()) {
                        secureStorage.saveToken(tokenKey, token)
                        Timber.d("Migrated token: $tokenKey")
                    }
                }

                // Migrate generic secrets
                val secrets = obj["secrets"] as? kotlinx.serialization.json.JsonObject
                secrets?.forEach { (secretKey, secretElement) ->
                    val secret = (secretElement as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (!secret.isNullOrBlank()) {
                        secureStorage.saveSecret(secretKey, secret)
                        Timber.d("Migrated secret: $secretKey")
                    }
                }

                val backupFile = File(authFile.parent, "auth.json.migrated.bak")
                authFile.copyTo(backupFile, overwrite = true)
                prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
                Timber.i("Secret migration completed successfully")
                true
            } catch (e: Exception) {
                Timber.e(e, "Secret migration failed")
                prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
                false
            }
        }
    }

    fun isMigrationCompleted(): Boolean = prefs.getBoolean(KEY_MIGRATED, false)
}


